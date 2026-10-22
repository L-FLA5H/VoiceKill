package com.example.voicekill.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;

/** 保留 Shriek 文本通道；带编号的新包只按事件去重，不按说出的文字去重。 */
public record SpeechPacket(String eventId, List<String> candidates, Set<Integer> literalCandidates, boolean reviewConfirmed, RoundSpan rounds) {
    public record RoundSpan(String first,String last) {
        public RoundSpan {first=UUID.fromString(first).toString();last=UUID.fromString(last).toString();}
    }
    public SpeechPacket(String eventId,List<String> candidates,Set<Integer> literalCandidates,boolean reviewConfirmed){this(eventId,candidates,literalCandidates,reviewConfirmed,null);}
    public SpeechPacket(String eventId,List<String> candidates){this(eventId,candidates,Set.of(),false);}
    public SpeechPacket(String eventId,List<String> candidates,Set<Integer> literalCandidates){this(eventId,candidates,literalCandidates,!literalCandidates.isEmpty());}
    public SpeechPacket {
        candidates=List.copyOf(candidates);literalCandidates=Set.copyOf(literalCandidates);
        for(int index:literalCandidates)if(index<1||index>=candidates.size())throw new IllegalArgumentException("无效复核候选索引");
    }
    /** Accepted review is authoritative; primary remains available solely as original evidence. */
    public String finalText() {
        if(candidates.isEmpty())return "";
        return candidates.get(literalCandidates.stream().min(Integer::compareTo).orElse(0));
    }
    public static final String SEPARATOR = "\u0001";
    private static final String PREFIX = "[voicekill:2:";

    public static SpeechPacket parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) return new SpeechPacket(null, List.of());
        String[] parts = raw.split(SEPARATOR, -1);
        int first = 0;
        String id = null;
        Set<Integer> literal=new LinkedHashSet<>();boolean confirmed=false;RoundSpan rounds=null;
        if (parts[0].startsWith(PREFIX) && parts[0].endsWith("]")) {
            String candidateId = parts[0].substring(PREFIX.length(), parts[0].length() - 1);
            try {
                String[] metadata=candidateId.split("\\|",-1);
                if(metadata.length>4)throw new IllegalArgumentException("无效候选来源");
                id=UUID.fromString(metadata[0]).toString();first=1;
                for(int i=1;i<metadata.length;i++) {
                    if(metadata[i].equals("C=1")){if(confirmed)throw new IllegalArgumentException("重复确认");confirmed=true;}
                    else if(metadata[i].startsWith("R=")&&rounds==null){var ends=metadata[i].substring(2).split(",",-1);if(ends.length!=2)throw new IllegalArgumentException("无效轮次");rounds=new RoundSpan(ends[0],ends[1]);}
                    else if(metadata[i].matches("L=[12](,[12])?")&&literal.isEmpty())for(String index:metadata[i].substring(2).split(","))literal.add(Integer.parseInt(index));
                    else throw new IllegalArgumentException("无效候选来源");
                }
            }
            catch (IllegalArgumentException ignored) { return new SpeechPacket(null, List.of()); }
        }
        List<String> candidates = new ArrayList<>();
        Set<Integer> mapped=new LinkedHashSet<>();
        if (first >= parts.length || parts[first].isBlank()||parts[first].trim().length()>512) return new SpeechPacket(id, List.of());
        for(int index:literal)if(index>=parts.length-first)return new SpeechPacket(id,List.of());
        for (int i = first; i < parts.length && candidates.size() < 3; i++) {
            String text = parts[i].trim();
            if (text.isEmpty() || text.length() > 512) continue;
            if (!candidates.contains(text)) {
                if(literal.contains(i-first))mapped.add(candidates.size());
                candidates.add(text);
            }
        }
        return new SpeechPacket(id, List.copyOf(candidates),mapped,confirmed||!mapped.isEmpty(),rounds);
    }

    public static String encode(List<String> candidates) {
        return encode(candidates,Set.of());
    }
    public static String encode(List<String> candidates,Set<Integer> literal) {return encode(candidates,literal,!literal.isEmpty());}
    public static String encode(List<String> candidates,Set<Integer> literal,boolean confirmed) {
        return encode(candidates,literal,confirmed,null);
    }
    public static String encode(List<String> candidates,Set<Integer> literal,boolean confirmed,RoundSpan rounds) {
        for(int index:literal)if(index<1||index>=candidates.size()||index>2)throw new IllegalArgumentException("无效复核候选索引");
        String metadata=literal.isEmpty()?"":"|L="+literal.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        if(confirmed&&literal.isEmpty())metadata+="|C=1";
        if(rounds!=null)metadata+="|R="+rounds.first()+","+rounds.last();
        return PREFIX + UUID.randomUUID()+metadata + "]" + SEPARATOR + String.join(SEPARATOR, candidates);
    }
}
