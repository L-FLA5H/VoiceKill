package com.example.voicekill.client;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次录音会话独占输入和识别器；可用合成输入测试退出、重复语句和清理。 */
public final class CaptureSession {
    public static final int FRAME_BYTES = 1280; // 40ms @ 16kHz mono PCM16
    public enum Boundary { NONE, IDLE, DISCARD, FINAL }
    public interface Source extends AutoCloseable {
        int read(byte[] bytes) throws Exception;
        @Override void close();
    }
    public interface Decoder extends AutoCloseable {
        List<String> accept(byte[] bytes) throws Exception;
        default Boundary boundary() { return Boundary.NONE; }
        default long epoch() { return 0; }
        default java.util.Set<Integer> literalCandidates() { return java.util.Set.of(); }
        @Override void close();
    }
    private final Source source;
    private final Decoder decoder;
    private final Consumer<List<String>> sink;
    private final Consumer<String> onFailure;
    private final Thread thread;
    private final AtomicBoolean sourceClosed = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile String state = "ready";
    private volatile String error = "";
    private volatile double level;
    private volatile long chunks;

    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink) {
        this(source, decoder, sink, ignored -> {});
    }

    public CaptureSession(Source source, Decoder decoder, Consumer<List<String>> sink, Consumer<String> onFailure) {
        this.source = source;
        this.decoder = decoder;
        this.sink = sink;
        this.onFailure = onFailure;
        thread = new Thread(this::loop, "VoiceKill-MicCapture");
        thread.setDaemon(true);
    }

    public void start() { state = "recording"; thread.start(); }
    public boolean isRunning() { return "recording".equals(state) && thread.isAlive(); }
    public String state() { return state; }
    public String error() { return error; }
    public double level() { return level; }
    public long chunks() { return chunks; }

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
            while (!stopping) {
                int count = source.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                chunks++;
                long energy = 0;
                for (int i = 0; i + 1 < count; i += 2) {
                    int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                    energy += (long) sample * sample;
                }
                level = Math.min(1, Math.sqrt((double) energy / Math.max(1, count / 2)) / 32768);
                List<String> candidates = decoder.accept(Arrays.copyOf(buffer, count));
                // 不按文字去重：两个连续相同的端点结果可以是两个真实音节/语句。
                if (!stopping && !candidates.isEmpty()) sink.accept(candidates);
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
