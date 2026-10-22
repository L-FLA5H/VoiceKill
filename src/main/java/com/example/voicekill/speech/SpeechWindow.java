package com.example.voicekill.speech;

import com.example.voicekill.util.TextUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 每位玩家的有界断句窗口；同一包的候选互斥，只有不同包才能衔接。仅服务端线程使用。 */
public final class SpeechWindow {
    private record Path(String text, boolean secondary) {}
    private static final class State {
        long at;
        List<Path> tails = List.of();
        final LinkedHashSet<String> events = new LinkedHashSet<>();
    }
    public record Result(WordMatcher.Hit hit, int candidate, boolean duplicate) {}
    private final Map<String, State> speakers = new HashMap<>();

    public Result acceptFinal(String speaker,SpeechPacket packet,List<WordMatcher.Word> words,long now,PhoneticMatcher.Settings settings) {
        speakers.entrySet().removeIf(e->now-e.getValue().at>60_000);
        State state=speakers.computeIfAbsent(speaker,ignored->new State());
        if(packet.eventId()!=null&&!state.events.add(packet.eventId()))return new Result(null,-1,true);
        while(state.events.size()>64)state.events.remove(state.events.iterator().next());
        String fresh=TextUtil.normalize(packet.finalText());
        int candidate=packet.literalCandidates().stream().min(Integer::compareTo).orElse(0);
        WordMatcher.Hit found=PhoneticMatcher.find(words,fresh,settings);
        var next=new LinkedHashMap<String,Path>();addTail(next,new Path(fresh,false));
        var stitchable=words.stream().filter(w->!"highfreq".equals(w.source())&&!"smart".equals(w.source())).toList();
        if(now-state.at<=5000)for(var tail:state.tails) {
            String joined=tail.text()+fresh;
            var hit=PhoneticMatcher.find(stitchable,joined,settings,tail.text().length());
            if(WordMatcher.prefer(hit,found,words))found=hit;
            addTail(next,new Path(joined,false));
        }
        state.at=now;state.tails=new ArrayList<>(next.values());
        return new Result(found,found==null?-1:candidate,false);
    }

    public Result accept(String speaker, SpeechPacket packet, List<WordMatcher.Word> words, long now) {
        speakers.entrySet().removeIf(entry -> now - entry.getValue().at > 60_000);
        State state = speakers.computeIfAbsent(speaker, ignored -> new State());
        if (packet.eventId() != null && !state.events.add(packet.eventId())) return new Result(null, -1, true);
        while (state.events.size() > 64) state.events.remove(state.events.iterator().next());
        WordMatcher.Hit found=null;int foundCandidate=-1;
        boolean reviewed=!packet.literalCandidates().isEmpty();
        if(reviewed) {
            boolean rawSupported=false;String primary=TextUtil.normalize(packet.candidates().getFirst());
            for(int index:packet.literalCandidates()) {
                String sentence=packet.candidates().get(index);
                var hit=ReviewMatcher.find(words,sentence,packet.candidates().getFirst());
                if(WordMatcher.prefer(hit,found,words)){found=hit;foundCandidate=index;}
                if(TextUtil.normalize(sentence).contains(primary))rawSupported=true;
            }
            // Withdraw contradicted raw text and its history. Only fully corroborated raw
            // fragments may continue the existing long-name window; review text is never joined.
            if(!rawSupported){state.at=now;state.tails=List.of();return new Result(found,foundCandidate,false);}
        }
        List<WordMatcher.Word> stitchable=words.stream().filter(word->!"highfreq".equals(word.source())&&!"smart".equals(word.source())).toList();
        List<Path> old = now - state.at <= 5_000 ? state.tails : List.of();
        state.at = now;
        Map<String, Path> next = new LinkedHashMap<>();
        for (int i = 0; i < packet.candidates().size(); i++) {
            if(reviewed&&i!=0)continue;
            String fresh = TextUtil.normalize(packet.candidates().get(i));
            boolean secondary = i != 0;
            WordMatcher.Hit hit = WordMatcher.find(words, fresh, secondary, 0);
            if (WordMatcher.prefer(hit,found,words)) { found = hit; foundCandidate = i; }
            if(i==0&&!reviewed){var domain=GameTerms.find(words,fresh,fresh,packet.reviewConfirmed());if(WordMatcher.prefer(domain,found,words)){found=domain;foundCandidate=i;}}
            addTail(next, new Path(fresh, secondary));
            for (Path tail : old) {
                String joined = tail.text() + fresh;
                boolean branchSecondary = secondary || tail.secondary();
                hit = WordMatcher.find(stitchable, joined, branchSecondary, tail.text().length());
                if (WordMatcher.prefer(hit,found,words)) { found = hit; foundCandidate = i; }
                addTail(next, new Path(joined, branchSecondary));
            }
        }
        state.tails = new ArrayList<>(next.values());
        return new Result(found, foundCandidate, false);
    }

    private static void addTail(Map<String, Path> paths, Path path) {
        String text = path.text().length() > 64 ? path.text().substring(path.text().length() - 64) : path.text();
        Path previous = paths.get(text);
        if (previous != null && !previous.secondary()) return;
        if (previous != null || paths.size() < 32) paths.put(text, new Path(text, path.secondary()));
    }

    public void forget(String speaker) { speakers.remove(speaker); }
    public void clear() { speakers.clear(); }
}
