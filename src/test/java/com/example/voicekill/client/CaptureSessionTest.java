package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.LinkedBlockingQueue;
import com.example.voicekill.speech.RecognitionHealth;
import com.example.voicekill.speech.RecognitionHealth.Issue;
import static org.junit.jupiter.api.Assertions.*;

class CaptureSessionTest {
    @Test void waitingForTheFirstFrameIsUnconfirmedRatherThanAMicrophoneFailure() throws Exception {
        var entered=new CountDownLatch(1);var firstFrame=new CountDownLatch(1);var closed=new CountDownLatch(1);var progressed=new CountDownLatch(1);
        var source=new CaptureSession.Source(){boolean first=true;public int read(byte[] bytes)throws Exception{if(first){first=false;entered.countDown();firstFrame.await();return 2;}closed.await();return -1;}public void close(){firstFrame.countDown();closed.countDown();}};
        var decoder=new CaptureSession.Decoder(){public List<String> accept(byte[] bytes){return List.of();}public void close(){}};
        var session=new CaptureSession(source,decoder,ignored->{},ignored->fail("unexpected failure"),ignored->progressed.countDown());
        try{session.start();assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(Issue.UNKNOWN,session.issue());firstFrame.countDown();assertTrue(progressed.await(1,TimeUnit.SECONDS));assertEquals(Issue.READY,session.issue());}finally{assertTrue(session.stop());}
    }
    @Test void modelLoadingAndFailureSuppressInferenceAndRecoveryStartsWithFreshAudio() throws Exception {
        var input=new LinkedBlockingQueue<byte[]>();var readiness=new AtomicReference<>(Issue.MODEL_LOADING);
        var paused=new CountDownLatch(1);var sent=new CountDownLatch(1);var failed=new CountDownLatch(1);var calls=new AtomicInteger();var discarded=new AtomicInteger();
        var source=new CaptureSession.Source(){public int read(byte[] bytes)throws Exception{byte[] frame=input.take();System.arraycopy(frame,0,bytes,0,frame.length);return frame.length;}public void close(){}};
        var decoder=new CaptureSession.Decoder(){public List<String> accept(byte[] bytes){calls.incrementAndGet();return List.of("正常");}public void discard(){discarded.incrementAndGet();}public void close(){}};
        var session=new CaptureSession(source,decoder,ignored->sent.countDown(),ignored->fail("unexpected failure"),text->{if(readiness.get()==Issue.MODEL_LOADING)paused.countDown();if(readiness.get()==Issue.MODEL_FAILED)failed.countDown();},readiness::get);
        try {
            session.start();input.add(new byte[2]);assertTrue(paused.await(1,TimeUnit.SECONDS));assertEquals(0,calls.get());assertEquals(Issue.MODEL_LOADING,session.issue());
            readiness.set(Issue.READY);input.add(new byte[2]);assertTrue(sent.await(1,TimeUnit.SECONDS));assertEquals(1,calls.get());assertTrue(discarded.get()>=2);
            readiness.set(Issue.MODEL_FAILED);input.add(new byte[2]);assertTrue(failed.await(1,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(Issue.MODEL_FAILED,session.issue());
        } finally {assertTrue(session.stop());}
    }
    @Test void postModelRuntimeFailureCannotLeakTheFallbackTranscript() throws Exception {
        var calls=new AtomicInteger();
        var base=new CaptureSession.Decoder(){public List<String> accept(byte[] bytes){calls.incrementAndGet();return List.of("危险词");}public void close(){}};
        var post=new SentencePostprocessor(new SentencePostprocessor.Reviewer(){public String recognize(float[] audio){throw new IllegalStateException("model failed");}public void close(){}},null);
        var enhanced=new EnhancedDecoder(base,post,10);
        var source=new CaptureSession.Source(){int count;public int read(byte[] bytes){java.util.Arrays.fill(bytes,(byte)32);return count++<3?1280:-1;}public void close(){}};
        var session=new CaptureSession(source,enhanced,ignored->fail("failed model published raw text"));
        session.start();awaitStopped(session);assertTrue(post.failed());assertEquals(1,calls.get());
    }
    @Test void aLiveThreadWithoutFreshAudioProcessingIsNotHealthy() throws Exception {
        var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);
        var source=new CaptureSession.Source(){boolean first=true;public int read(byte[] bytes)throws Exception{if(first){first=false;return 2;}waiting.countDown();release.await();return -1;}public void close(){release.countDown();}};
        var decoder=new CaptureSession.Decoder(){public List<String> accept(byte[] bytes){return List.of();}public void close(){}};
        var session=new CaptureSession(source,decoder,ignored->{});
        try{session.start();assertTrue(waiting.await(1,TimeUnit.SECONDS));assertEquals(Issue.READY,session.issue());TimeUnit.NANOSECONDS.sleep(RecognitionHealth.FRAME_TIMEOUT_NANOS+20_000_000L);assertTrue(session.isRunning());assertEquals(Issue.STALLED,session.issue());}finally{assertTrue(session.stop());}
    }
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
