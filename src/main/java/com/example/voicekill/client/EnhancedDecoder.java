package com.example.voicekill.client;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 结算仍由基础识别器决定；可选模型后台准备，失败时沿用原始识别。 */
public final class EnhancedDecoder implements CaptureSession.Decoder {
    @FunctionalInterface public interface Loader { SentencePostprocessor load() throws Exception; }
    private final CaptureSession.Decoder base;
    private final PcmUtterance audio;
    private final Object lock=new Object();
    private volatile SentencePostprocessor post;
    private volatile String state,error="";
    private volatile SentencePostprocessor.Snapshot latest;
    private Thread loader;
    private volatile boolean closed;
    private long epoch;
    private Set<Integer> literalCandidates=Set.of();
    public Set<Integer> literalCandidates(){return literalCandidates;}

    public EnhancedDecoder(CaptureSession.Decoder base,SentencePostprocessor post,float seconds) {
        this.base=base;this.post=post;this.state="ready";this.epoch=base.epoch();
        this.audio=new PcmUtterance((int)((seconds+5)*16000));this.latest=post.snapshot();
    }
    public EnhancedDecoder(CaptureSession.Decoder base,Loader factory,float seconds) {
        this.base=base;this.state="loading";this.epoch=base.epoch();
        this.audio=new PcmUtterance((int)((seconds+5)*16000));
        loader=new Thread(()->{
            try {
                SentencePostprocessor ready=factory.load();
                synchronized(lock) {
                    if(closed)ready.close();
                    else {post=ready;state="ready";}
                }
            } catch(Throwable failure) {synchronized(lock){if(!closed){state="failed";error=CaptureSession.rootCause(failure);}}}
        },"VoiceKill-SentenceModels");
        loader.setDaemon(true);loader.start();
    }
    @Override public List<String> accept(byte[] bytes) throws Exception {
        if(closed)throw new IllegalStateException("识别会话已关闭");
        List<String> raw=base.accept(bytes);
        literalCandidates=Set.of();
        if(base.epoch()!=epoch){audio.clear();epoch=base.epoch();}
        audio.append(bytes);
        if(raw.isEmpty()) {
            if(base.boundary()==CaptureSession.Boundary.IDLE)audio.keepLast(16000);
            else if(base.boundary()==CaptureSession.Boundary.DISCARD)audio.clear();
            return raw;
        }
        try {
            SentencePostprocessor current=post;
            if(current==null) {
                latest=new SentencePostprocessor.Snapshot(raw.getFirst(),"",raw.getFirst(),false,state,0,0,error,0);
                return raw;
            }
            List<String> result=current.process(raw,audio.samples());latest=current.snapshot();
            if(latest.accepted())literalCandidates=Set.of(result.size()-1);return result;
        } finally {audio.clear();}
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("state",state);result.put("error",error);
        if(latest!=null)result.put("latest",latest);return result;
    }
    @Override public CaptureSession.Boundary boundary(){return base.boundary();}
    @Override public long epoch(){return base.epoch();}
    @Override public void close() {
        SentencePostprocessor current;
        synchronized(lock){if(closed)return;closed=true;state="closed";current=post;post=null;}
        if(loader!=null)loader.interrupt();
        try {base.close();}finally{if(current!=null)current.close();}
    }
}
