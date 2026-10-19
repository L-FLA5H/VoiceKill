package com.example.voicekill.client;

/** 按输入采样计时；空闲不耗费讲话预算，不检查具体词或匹配结果。 */
public final class UtteranceBudget {
    private final long limitSamples;
    private long samples;
    private long firstHypothesis = -1;

    public UtteranceBudget(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 2 || seconds > 15)
            throw new IllegalArgumentException("连续讲话结算上限必须在 2～15 秒之间");
        limitSamples = (long)(seconds * 16000);
    }
    public boolean advance(int sampleCount, boolean hasHypothesis) {
        if (sampleCount < 0) throw new IllegalArgumentException("采样数不能为负");
        if (firstHypothesis < 0 && hasHypothesis) firstHypothesis = samples;
        samples += sampleCount;
        return firstHypothesis >= 0 && samples - firstHypothesis >= limitSamples;
    }
    public void reset() { samples = 0; firstHypothesis = -1; }
}
