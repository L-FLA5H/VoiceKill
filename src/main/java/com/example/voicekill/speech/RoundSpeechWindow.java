package com.example.voicekill.speech;

import java.util.*;

/** Server-owned round snapshots. Only rounds overlapping one utterance are eligible. */
public final class RoundSpeechWindow {
    private record Round(String id,List<WordMatcher.Word> words,SpeechWindow window,long at) {}
    public record Result(WordMatcher.Hit hit,int candidate,boolean duplicate,boolean stale) {}
    private static final int MAX_ROUNDS=32;
    private static final long RETENTION_MS=60_000;
    private final List<Round> rounds=new ArrayList<>();
    private final SpeechWindow dedup=new SpeechWindow();

    public String rotate(List<WordMatcher.Word> words,long now) {
        // Keep the round dictionaries, but never stitch separate sentences across a rotation.
        for(var round:rounds)round.window().clear();
        String id=UUID.randomUUID().toString();
        var snapshot=words.stream().map(w->new WordMatcher.Word(w.text(),w.source(),w.aliases()==null?List.of():List.copyOf(w.aliases()),w.preferred())).toList();
        rounds.add(new Round(id,snapshot,new SpeechWindow(),now));
        prune(now);return id;
    }
    private void prune(long now) {
        while(rounds.size()>MAX_ROUNDS||(rounds.size()>1&&now-rounds.get(1).at()>RETENTION_MS))rounds.removeFirst();
    }
    public Result accept(String speaker,SpeechPacket packet,long now) {
        return accept(speaker,packet,now,null);
    }
    public Result accept(String speaker,SpeechPacket packet,long now,PhoneticMatcher.Settings settings) {
        prune(now);int first=rounds.size()-1,last=first;
        if(packet.rounds()!=null) {
            first=index(packet.rounds().first());last=index(packet.rounds().last());
        }
        if(first<0||last<first)return new Result(null,-1,false,true);
        if(dedup.accept(speaker,packet,List.of(),now).duplicate())return new Result(null,-1,true,false);
        WordMatcher.Hit hit=null;int candidate=-1;
        for(int i=last;i>=first;i--) {
            var round=rounds.get(i);
            var result=settings==null?round.window().accept(speaker,packet,round.words(),now):round.window().acceptFinal(speaker,packet,round.words(),now,settings);
            // Newer round wins even if an older round has stronger/longer evidence.
            if(hit==null&&result.hit()!=null){hit=result.hit();candidate=result.candidate();}
        }
        return new Result(hit,candidate,false,false);
    }
    private int index(String id){for(int i=0;i<rounds.size();i++)if(rounds.get(i).id().equals(id))return i;return -1;}
    public void forget(String speaker){dedup.forget(speaker);for(var round:rounds)round.window().forget(speaker);}
    public void clear(){rounds.clear();dedup.clear();}
}
