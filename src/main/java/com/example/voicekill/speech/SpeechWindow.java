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

    public Result accept(String speaker, SpeechPacket packet, List<WordMatcher.Word> words, long now) {
        speakers.entrySet().removeIf(entry -> now - entry.getValue().at > 60_000);
        State state = speakers.computeIfAbsent(speaker, ignored -> new State());
        if (packet.eventId() != null && !state.events.add(packet.eventId())) return new Result(null, -1, true);
        while (state.events.size() > 64) state.events.remove(state.events.iterator().next());
        List<Path> old = now - state.at <= 5_000 ? state.tails : List.of();
        state.at = now;
        WordMatcher.Hit found = null;
        int foundCandidate = -1;
        Map<String, Path> next = new LinkedHashMap<>();
        for (int i = 0; i < packet.candidates().size(); i++) {
            if(packet.literalCandidates().contains(i)) {
                var reviewed=ReviewMatcher.find(words,packet.candidates().get(i),packet.candidates().getFirst());
                if(found==null&&reviewed!=null){found=reviewed;foundCandidate=i;}
                continue;
            }
            String fresh = TextUtil.normalize(packet.candidates().get(i));
            boolean secondary = i != 0;
            WordMatcher.Hit hit = WordMatcher.find(words, fresh, secondary, 0);
            if (found == null && hit != null) { found = hit; foundCandidate = i; }
            addTail(next, new Path(fresh, secondary));
            for (Path tail : old) {
                String joined = tail.text() + fresh;
                boolean branchSecondary = secondary || tail.secondary();
                hit = WordMatcher.find(words, joined, branchSecondary, tail.text().length());
                if (found == null && hit != null) { found = hit; foundCandidate = i; }
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
