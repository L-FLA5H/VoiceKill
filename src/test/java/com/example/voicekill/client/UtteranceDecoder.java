package com.example.voicekill.client;

import java.util.ArrayDeque;
import java.util.List;

/** 将语音分段和整段转写解耦；多段是先后事件，不能混成同一句的备用候选。 */
public final class UtteranceDecoder implements CaptureSession.Decoder {
    public interface Segmenter extends AutoCloseable {
        List<float[]> accept(byte[] pcm);
        @Override void close();
    }
    public interface Transcriber extends AutoCloseable {
        String recognize(float[] speech);
        @Override void close();
    }
    private final Segmenter segmenter;
    private final Transcriber transcriber;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean closed;

    public UtteranceDecoder(Segmenter segmenter, Transcriber transcriber) {
        this.segmenter = segmenter;
        this.transcriber = transcriber;
    }

    @Override public List<String> accept(byte[] pcm) {
        if (closed) throw new IllegalStateException("语音识别会话已关闭");
        for (float[] speech : segmenter.accept(pcm)) {
            String text = transcriber.recognize(speech).trim();
            if (!text.isEmpty()) {
                if (pending.size() >= 32) throw new IllegalStateException("语音处理队列积压，请降低识别负载");
                pending.addLast(text);
            }
        }
        return pending.isEmpty() ? List.of() : List.of(pending.removeFirst());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        try { segmenter.close(); } finally { transcriber.close(); }
    }
}
