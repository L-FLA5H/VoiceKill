package com.example.voicekill.speech;

import com.example.voicekill.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语音文本的词频统计（按「周期」分桶）。
 *
 * 关键难点：中文没有空格，而 Vosk 的中文识别结果又经常是「苦 力 怕」这种
 * 单字用空格隔开的形式，所以既不能按空格切词，也不能把每个字当词。
 *
 * 做法：把连续的中文片段拼回去，再取 2~4 字的滑动 n-gram；
 * 输出时把「碎片」压掉 —— 比如「苦力」和「苦力怕」频次相同，就只留「苦力怕」。
 */
public final class SpeechStats {

    /** 一个词 + 出现次数 */
    public record WordCount(String word, int count) {
    }

    private static final int MAX_GRAM = 4;
    private static final int MIN_LATIN = 3;

    /** 停用词：这些词就算高频也毫无节目效果，而且会淹掉真正的词 */
    private static final Set<String> STOP_WORDS = Set.of(
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "咱们",
            "的", "了", "是", "在", "有", "就", "不", "也", "都", "和", "跟",
            "这", "那", "这个", "那个", "这样", "那样", "这里", "那里",
            "什么", "怎么", "为什么", "因为", "所以", "但是", "然后", "就是",
            "一个", "一下", "一点", "可以", "没有", "不是", "还是", "已经",
            "吗", "啊", "呢", "吧", "呀", "哦", "嗯", "唉", "哈", "嘿",
            "the", "and", "you", "your", "that", "this", "with", "for",
            "have", "are", "was", "were", "not", "but", "they", "what",
            "there", "here", "just", "like", "yeah", "okay", "ok", "well",
            "know", "think", "going", "gonna", "want", "now", "then"
    );

    private final Map<String, Integer> current = new HashMap<>();
    private Map<String, Integer> lastCycle = Map.of();
    /** 整个运行过程的累计词频：换轮**不清空**，越常说分越高 */
    private final Map<String, Integer> cumulative = new HashMap<>();
    private int utterances = 0;

    // ------------------------------------------------------------------
    // 录入
    // ------------------------------------------------------------------

    /** 记录一句识别结果 */
    public synchronized void record(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        utterances++;
        tokenize(text, current);
        tokenize(text, cumulative);
    }

    /** 一个周期结束：把当前桶冻结成「上一周期」，然后清空继续统计 */
    public synchronized void finishCycle() {
        lastCycle = Map.copyOf(current);
        current.clear();
        utterances = 0;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 本周期（还没结束的这一轮）的高频词 */
    public synchronized List<WordCount> currentTop(int n, Set<String> knownWords) {
        return topN(current, n, knownWords);
    }

    /** 上一周期的高频词 —— 智能选词就吃这个 */
    public synchronized List<WordCount> lastCycleTop(int n, Set<String> knownWords) {
        return topN(lastCycle, n, knownWords);
    }

    /**
     * 累计高频词（整局不清空）。
     * 用来跟「上一周期」合并加权 —— 开局说过一次的词，后面再被提到权重会叠加，
     * 而不是只能活一轮。
     */
    public synchronized List<WordCount> cumulativeTop(int n, Set<String> knownWords) {
        return topN(cumulative, n, knownWords);
    }

    public synchronized int utterances() {
        return utterances;
    }

    // ------------------------------------------------------------------
    // 分词
    // ------------------------------------------------------------------

    private void tokenize(String text, Map<String, Integer> out) {
        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                latin.append(Character.toLowerCase(c));
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                // 空白只切断英文，不切断中文 ——
                // Vosk 中文会把「苦力怕」输出成「苦 力 怕」，空格断句就永远拼不回来
                flushLatin(latin, out);
            } else {
                // 标点、符号才真正断句
                flushCjk(cjk, out);
                flushLatin(latin, out);
            }
        }
        flushCjk(cjk, out);
        flushLatin(latin, out);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** 中文片段 → 2~4 字 n-gram；独立单字另外处理，整段不重复计数 */
    private void flushCjk(StringBuilder sb, Map<String, Integer> out) {
        if (sb.isEmpty()) {
            return;
        }
        String s = sb.toString();
        sb.setLength(0);

        if (s.length() == 1) {
            addWord(s, out);
        }
        for (int n = 2; n <= MAX_GRAM; n++) {
            for (int i = 0; i + n <= s.length(); i++) {
                addWord(s.substring(i, i + n), out);
            }
        }
    }

    /** 英文片段：整词计数，长度至少 3 */
    private void flushLatin(StringBuilder sb, Map<String, Integer> out) {
        if (sb.isEmpty()) {
            return;
        }
        String s = sb.toString();
        sb.setLength(0);
        if (s.length() >= MIN_LATIN) {
            addWord(s, out);
        }
    }

    private void addWord(String word, Map<String, Integer> out) {
        if (word == null || word.isEmpty() || STOP_WORDS.contains(word)) {
            return;
        }
        out.merge(word, 1, Integer::sum);
    }

    // ------------------------------------------------------------------
    // 排序 + 压碎片
    // ------------------------------------------------------------------

    /**
     * 首/尾出现这些字，基本可以判定是「跨词碎片」而不是一个词。
     * 例如「这 个 苦 力 怕」切出的 4-gram「这个苦力」，首字是「这」，直接扔掉。
     * 只查首尾、不查中间 —— 中间含虚字的词（比如「不要」「可以」）是正常词。
     */
    private static final String EDGE_FILLERS =
            "这个那就很太了吧吗啊的呢呀哦嗯唉哈嘿我你他她它在有又都还和跟把被让给对从到说着过们想会要能";

    private static boolean looksLikeFragment(String word) {
        if (word.isEmpty()) {
            return true;
        }
        return EDGE_FILLERS.indexOf(word.charAt(0)) >= 0
                || EDGE_FILLERS.indexOf(word.charAt(word.length() - 1)) >= 0;
    }

    private static boolean isKnown(String word, Set<String> knownWords) {
        if (knownWords == null || knownWords.isEmpty()) {
            return false;
        }
        return knownWords.contains(TextUtil.normalize(word));
    }

    private static List<WordCount> topN(Map<String, Integer> source, int n, Set<String> knownWords) {
        // 有明显的高频词时，只保留出现 2 次以上的，压掉 n-gram 跨词产生的垃圾
        int maxCount = 0;
        for (int count : source.values()) {
            if (count > maxCount) {
                maxCount = count;
            }
        }
        int threshold = maxCount >= 2 ? 2 : 1;

        List<WordCount> sorted = new ArrayList<>(source.size());
        for (Map.Entry<String, Integer> e : source.entrySet()) {
            String word = e.getKey();
            if (STOP_WORDS.contains(word) || word.length() < 2 || e.getValue() < threshold) {
                continue;
            }
            if (looksLikeFragment(word)) {
                continue;
            }
            sorted.add(new WordCount(word, e.getValue()));
        }
        // 排序优先级：词库里认识的词 > 频次高 > 更长
        // 「认识」这个词库先验很关键：只有一句话时，4 字碎片「天又被苦」和真词「苦力怕」
        // 频次都是 1，光靠长度会把真词挤掉
        sorted.sort(Comparator
                .comparingInt((WordCount w) -> isKnown(w.word(), knownWords) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(WordCount::count).reversed())
                .thenComparing(Comparator.comparingInt((WordCount w) -> w.word().length()).reversed()));

        List<WordCount> result = new ArrayList<>(n);
        Set<String> seen = new HashSet<>();
        for (WordCount candidate : sorted) {
            if (result.size() >= n) {
                break;
            }
            if (!seen.add(candidate.word())) {
                continue;
            }
            // 碎片过滤：短词被已收录的长词完整覆盖，且频次不高于它 → 丢掉
            boolean fragment = false;
            for (WordCount kept : result) {
                if (kept.count() >= candidate.count() && kept.word().contains(candidate.word())) {
                    fragment = true;
                    break;
                }
            }
            if (!fragment) {
                result.add(candidate);
            }
        }
        return result;
    }
}
