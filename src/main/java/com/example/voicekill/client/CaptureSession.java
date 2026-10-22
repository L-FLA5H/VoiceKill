package com.example.voicekill.client;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import com.example.voicekill.speech.RecognitionHealth;
import com.example.voicekill.speech.RecognitionHealth.Issue;

/** 单次录音会话独占输入和识别器；可用合成输入测试退出、重复语句和清理。 */
public final class CaptureSession {
    public static final int FRAME_BYTES = 1280; // 40ms @ 16kHz mono PCM16
    public enum Boundary { NONE, IDLE, DISCARD, FINAL }
    public record SampleRange(long start,long end) {}
    public interface Source extends AutoCloseable {
        int read(byte[] bytes) throws Exception;
        @Override void close();
    }
    public interface Decoder extends AutoCloseable {
        List<String> accept(byte[] bytes) throws Exception;
        default Issue issue(){return Issue.READY;}
        default Boundary boundary() { return Boundary.NONE; }
        default long epoch() { return 0; }
        default void discard() throws Exception {}
        default SampleRange utteranceRange(){return null;}
        default com.example.voicekill.speech.SpeechPacket.RoundSpan rounds(){return null;}
        default java.util.Set<Integer> literalCandidates() { return java.util.Set.of(); }
        default float[] utteranceSamples() { return null; }
        default boolean reviewConfirmed(){return false;}
        default String partial() { return ""; }
        default String displayFinal(List<String> candidates) { return candidates.isEmpty()?"":candidates.getFirst(); }
        @Override void close();
    }
    private final Source source;
    private final Decoder decoder;
    private final Consumer<List<String>> sink;
    private final Consumer<String> onFailure;
    private final Consumer<Transcript> progress;
    public record Transcript(String text,boolean interim) {}
    private final Thread thread;
    private final AtomicBoolean sourceClosed = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile String state = "ready";
    private volatile String error = "";
    private volatile double level;
    private volatile long chunks;
    private volatile long lastProgressNanos;
    private final Supplier<Issue> readiness;

    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink) {
        this(source, decoder, sink, ignored -> {});
    }

    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink, Consumer<String> onFailure) {
        this(source,decoder,sink,onFailure,ignored -> {});
    }

    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink, Consumer<String> onFailure,Consumer<Transcript> progress) {
        this(source,decoder,sink,onFailure,progress,decoder::issue);
    }
    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink, Consumer<String> onFailure,Consumer<Transcript> progress,Supplier<Issue> readiness) {
        this.source = source;
        this.decoder = decoder;
        this.sink = sink;
        this.onFailure = onFailure;
        this.progress = progress;
        this.readiness=readiness;
        thread = new Thread(this::loop, "VoiceKill-MicCapture");
        thread.setDaemon(true);
    }

    public void start() { state = "recording"; thread.start(); }
    public boolean isRunning() { return "recording".equals(state) && thread.isAlive(); }
    public String state() { return state; }
    public String error() { return error; }
    public double level() { return level; }
    public long chunks() { return chunks; }
    public Issue issue() {
        if(!isRunning())return Issue.MICROPHONE;
        Issue model=readiness.get();if(model!=Issue.READY)return model;
        if(lastProgressNanos==0)return Issue.UNKNOWN;
        return System.nanoTime()-lastProgressNanos>RecognitionHealth.FRAME_TIMEOUT_NANOS?Issue.STALLED:Issue.READY;
    }

    public boolean stop() {
        stopping = true;
        try { closeSource(); } catch (RuntimeException failure) { error = failure.toString(); }
        thread.interrupt();
        try { thread.join(2000); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        if (thread.isAlive()) {
            state = "failed";
            error = "录音线程未退出，请重启游戏后重试";
            return false;
        }
        return true;
    }

    private void loop() {
        try {
            byte[] buffer = new byte[FRAME_BYTES];
            boolean paused=false;
            while (!stopping) {
                int count = source.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                long now=System.nanoTime();
                if(readiness.get()!=Issue.READY) {
                    if(!paused){decoder.discard();paused=true;}
                    lastProgressNanos=now;progress.accept(new Transcript("",true));continue;
                }
                if(paused||(lastProgressNanos!=0&&now-lastProgressNanos>RecognitionHealth.FRAME_TIMEOUT_NANOS)){decoder.discard();paused=false;}
                chunks++;
                long energy = 0;
                for (int i = 0; i + 1 < count; i += 2) {
                    int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                    energy += (long) sample * sample;
                }
                level = Math.min(1, Math.sqrt((double) energy / Math.max(1, count / 2)) / 32768);
                List<String> candidates = decoder.accept(Arrays.copyOf(buffer, count));
                lastProgressNanos=System.nanoTime();
                if(readiness.get()!=Issue.READY||lastProgressNanos-now>RecognitionHealth.FRAME_TIMEOUT_NANOS) {
                    decoder.discard();paused=true;progress.accept(new Transcript("",true));continue;
                }
                // 不按文字去重：两个连续相同的端点结果可以是两个真实音节/语句。
                if (!stopping) {
                    if (!candidates.isEmpty()) {
                        progress.accept(new Transcript(decoder.displayFinal(candidates),false));
                        sink.accept(candidates);
                    } else progress.accept(new Transcript(decoder.partial(),true));
                }
            }
        } catch (Throwable failure) {
            if (!stopping) { state = "failed"; error = rootCause(failure); }
        } finally {
            try { closeSource(); } catch (Throwable failure) { if (error.isEmpty()) error = rootCause(failure); }
            try { decoder.close(); } catch (Throwable failure) { if (error.isEmpty()) error = rootCause(failure); }
            level = 0;
            if (!error.isEmpty() && !stopping) state = "failed";
            else if (!"failed".equals(state)) state = "stopped";
            if ("failed".equals(state)) onFailure.accept(error);
        }
    }

    private void closeSource() {
        if (sourceClosed.compareAndSet(false, true)) source.close();
    }

    public static String rootCause(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }
}
