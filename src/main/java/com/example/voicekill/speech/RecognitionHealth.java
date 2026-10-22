package com.example.voicekill.speech;

import java.util.HashMap;
import java.util.Map;

/** A ready heartbeat permits play, but never counts as recognized speech. Owner-thread only. */
public final class RecognitionHealth {
    public static final long LEASE_TICKS=80,NOTICE_TICKS=200,CONFIRM_TICKS=40,FRAME_TIMEOUT_NANOS=3_000_000_000L;
    public enum Issue {
        READY(""),MICROPHONE("麦克风可能出现问题"),MODEL_LOADING("模型尚未就绪"),
        MODEL_FAILED("模型可能出现问题"),STALLED("语音处理可能卡住"),UNKNOWN("语音状态未确认");
        private final String description;
        Issue(String description){this.description=description;}
        public String warning(){return description+"，可能无法正常游戏（已暂停识别/扣血）";}
    }
    private record Report(Issue issue,long tick) {}
    private record Pending(Issue issue,long since) {}
    private final Map<String,Report> reports=new HashMap<>();
    private final Map<String,Long> notices=new HashMap<>();
    private final Map<String,Pending> pending=new HashMap<>();
    private String session="";
    public void reset(String session){clear();this.session=session;}
    /** A new gameplay token must not discard a connection's already verified health or renew its lease. */
    public void bindSession(String session){this.session=session;}
    public boolean report(String player,String session,Issue issue,long tick) {
        if(this.session.isEmpty()||!this.session.equals(session))return false;
        reports.put(player,new Report(issue,tick));
        if(issue==Issue.READY){pending.remove(player);notices.remove(player);}
        return true;
    }
    public Issue issue(String player,long tick) {
        var report=reports.get(player);
        return report==null||tick-report.tick()>LEASE_TICKS?Issue.UNKNOWN:report.issue();
    }
    public boolean ready(String player,long tick){return issue(player,tick)==Issue.READY;}
    public boolean noticeDue(String player,long tick) {
        Issue current=issue(player,tick);
        if(current==Issue.READY){pending.remove(player);notices.remove(player);return false;}
        Pending waiting=pending.get(player);
        if(waiting==null||waiting.issue()!=current){waiting=new Pending(current,tick);pending.put(player,waiting);}
        // Safety pauses immediately; warnings wait for a stable fault or the initial handshake.
        if(tick-waiting.since()<(current==Issue.UNKNOWN?LEASE_TICKS:CONFIRM_TICKS))return false;
        Long previous=notices.get(player);
        if(previous!=null&&tick-previous<NOTICE_TICKS)return false;
        notices.put(player,tick);return true;
    }
    public void forget(String player){reports.remove(player);notices.remove(player);pending.remove(player);}
    public void clear(){reports.clear();notices.clear();pending.clear();session="";}
}
