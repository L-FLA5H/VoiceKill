package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CaptureSessionTest {
    @Test void captureUsesFortyMillisecondPcmFrames() throws Exception {
        var sizes = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var source = new CaptureSession.Source() {
            boolean sent;
            public int read(byte[] bytes) { if (sent) return -1; sent=true; sizes.add(bytes.length); return bytes.length; }
            public void close() {}
        };
        var decoder = new CaptureSession.Decoder() {
            public List<String> accept(byte[] bytes) { sizes.add(bytes.length); return List.of(); }
            public void close() {}
        };
        var session = new CaptureSession(source, decoder, ignored -> {});
        session.start(); awaitStopped(session);
        assertEquals(List.of(1280,1280), sizes);
    }
    private static void awaitStopped(CaptureSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (session.isRunning() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(session.isRunning());
        assertTrue(session.stop());
    }

    @Test void repeatedUtterancesAreSentAndResourcesClosedAtEof() throws Exception {
        var closes = new AtomicInteger();
        var source = new CaptureSession.Source() {
            int reads;
            public int read(byte[] bytes) { return reads++ < 2 ? 2 : -1; }
            public void close() { closes.incrementAndGet(); }
        };
        var decoder = new CaptureSession.Decoder() {
            public List<String> accept(byte[] bytes) { return List.of("宝"); }
            public void close() { closes.incrementAndGet(); }
        };
        List<List<String>> sent = new CopyOnWriteArrayList<>();
        var session = new CaptureSession(source, decoder, sent::add);
        session.start(); awaitStopped(session);
        assertEquals(List.of(List.of("宝"), List.of("宝")), sent);
        assertEquals("stopped", session.state());
        assertEquals(2, closes.get());
    }

    @Test void deviceFailureCannotLeaveRecordingState() throws Exception {
        var source = new CaptureSession.Source() {
            public int read(byte[] bytes) { throw new IllegalStateException("device disconnected"); }
            public void close() {}
        };
        var closed = new AtomicInteger();
        var decoder = new CaptureSession.Decoder() {
            public List<String> accept(byte[] bytes) { return List.of(); }
            public void close() { closed.incrementAndGet(); }
        };
        var session = new CaptureSession(source, decoder, ignored -> fail("no audio"));
        session.start(); awaitStopped(session);
        assertEquals("failed", session.state());
        assertTrue(session.error().contains("device disconnected"));
        assertEquals(1, closed.get());
    }

    @Test void stopUnblocksReadAndDoesNotSendPendingText() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        var source = new CaptureSession.Source() {
            public int read(byte[] bytes) throws Exception { reading.countDown(); release.await(); return 2; }
            public void close() { release.countDown(); }
        };
        var decoder = new CaptureSession.Decoder() {
            public List<String> accept(byte[] bytes) { return List.of("stop"); }
            public void close() {}
        };
        var session = new CaptureSession(source, decoder, ignored -> fail("sent after stopping"));
        session.start(); assertTrue(reading.await(1, TimeUnit.SECONDS));
        assertTrue(session.stop());
        assertEquals("stopped", session.state());
    }
}
