package com.example.voicekill.speech;

import com.example.voicekill.util.FuzzyMatch;
import com.example.voicekill.util.TextUtil;
import com.example.voicekill.util.WordAliases;
import java.util.List;
import java.util.Comparator;

/** 无游戏依赖的匹配规则；每次返回命中证据的位置与类型。 */
public final class WordMatcher {
    public record Word(String text, String source, List<String> aliases, boolean preferred) {
        public Word(String text,String source,List<String> aliases) {
            this(text,source,aliases,"manual".equals(source)||"name".equals(source));
        }
    }
    public record Hit(String word, String mode, String evidence, int start, int end) {}

    private WordMatcher() {}

    public static List<Word> ordered(List<Word> words) {
        return words.stream().sorted(Comparator.comparing(Word::preferred).reversed()
                .thenComparing(Comparator.comparingInt((Word w)->TextUtil.normalize(w.text()).length()).reversed())).toList();
    }

    /** 只在已经成立的命中之间选择，不扩大任何匹配谓词。 */
    public static boolean prefer(Hit candidate,Hit current,List<Word> words) {
        if(candidate==null)return false;
        if(current==null)return true;
        int evidence=Integer.compare(strength(candidate.mode()),strength(current.mode()));
        if(evidence!=0)return evidence<0;
        boolean a=words.stream().anyMatch(w->w.text().equals(candidate.word())&&w.preferred());
        boolean b=words.stream().anyMatch(w->w.text().equals(current.word())&&w.preferred());
        if(a!=b)return a;
        return TextUtil.normalize(candidate.word()).length()>TextUtil.normalize(current.word()).length();
    }

    private static int strength(String mode) {
        return switch(mode) {case "exact","review-exact"->0;case "alias"->1;case "pronunciation"->2;default->3;};
    }

    public static Hit find(List<Word> words, String input, boolean secondary, int previousLength) {
        String text = TextUtil.normalize(input);
        words = ordered(words);
        // 先精确，再别名/读音，最后编辑距离；避免早先的模糊词抢走精确命中。
        for (Word word : words) {
            String target = TextUtil.normalize(word.text());
            if (target.isEmpty() || (secondary && target.length() == 1)) continue;
            Hit hit = literal(word.text(), "exact", target, text, previousLength);
            if (hit != null) return hit;
        }
        for (Word word : words) {
            String target = TextUtil.normalize(word.text());
            if (target.isEmpty() || (secondary && target.length() == 1)) continue;
            if (word.aliases() != null) {
                for (String alias : word.aliases()) {
                    Hit hit = literal(word.text(), "alias", TextUtil.normalize(alias), text, previousLength);
                    if (hit != null) return hit;
                }
            }
        }
        for (Word word : words) {
            String target = TextUtil.normalize(word.text());
            if (target.isEmpty() || (secondary && target.length() == 1)) continue;
            var pattern = WordAliases.pronunciationPattern(target, fuzzyEligible(word));
            if (pattern != null) {
                var matcher = pattern.matcher(text);
                while (matcher.find()) {
                    if (matcher.end() > previousLength) {
                        return new Hit(word.text(), "pronunciation", matcher.group(), matcher.start(), matcher.end());
                    }
                }
            }
        }
        for (Word word : words) {
            if (!fuzzyEligible(word)) continue;
            String target = TextUtil.normalize(word.text());
            for (int size : new int[]{target.length(), target.length() - 1}) {
                for (int start = 0; start + size <= text.length(); start++) {
                    if (start + size <= previousLength) continue;
                    String fragment = text.substring(start, start + size);
                    if (FuzzyMatch.isClose(target, fragment)) {
                        return new Hit(word.text(), "edit-distance", fragment, start, start + size);
                    }
                }
            }
        }
        return null;
    }

    private static boolean fuzzyEligible(Word word) {
        return ("name".equals(word.source()) || "manual".equals(word.source()))
                && TextUtil.normalize(word.text()).length() >= 4;
    }

    private static Hit literal(String word, String mode, String target, String text, int boundary) {
        if (target.isEmpty()) return null;
        int start = text.indexOf(target);
        while (start >= 0) {
            int end = start + target.length();
            if (end > boundary) return new Hit(word, mode, text.substring(start, end), start, end);
            start = text.indexOf(target, start + 1);
        }
        return null;
    }
}
