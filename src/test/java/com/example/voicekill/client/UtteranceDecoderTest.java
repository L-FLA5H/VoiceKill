package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UtteranceDecoderTest {
    @Test void completedUtterancesAreSeparateEventsRatherThanAlternativeCandidates() {
        var calls=new AtomicInteger();
        var closed=new AtomicInteger();
        var decoder=new UtteranceDecoder(new UtteranceDecoder.Segmenter() {
            public List<float[]> accept(byte[] pcm) { return calls.getAndIncrement()==0 ? List.of(new float[]{1},new float[]{2}) : List.of(); }
            public void close() { closed.incrementAndGet(); }
        },new UtteranceDecoder.Transcriber() {
            public String recognize(float[] speech) { return speech[0]==1 ? "first" : "second"; }
            public void close() { closed.incrementAndGet(); }
        });
        assertEquals(List.of("first"),decoder.accept(new byte[0]));
        assertEquals(List.of("second"),decoder.accept(new byte[0]));
        assertTrue(decoder.accept(new byte[0]).isEmpty());
        decoder.close();decoder.close();
        assertEquals(2,closed.get());
        assertThrows(IllegalStateException.class,()->decoder.accept(new byte[0]));
    }

    @Test void silenceIsNotTranscribedAndBothResourcesCloseOnFailure() {
        var transcribed=new AtomicInteger();
        var closed=new AtomicInteger();
        var decoder=new UtteranceDecoder(new UtteranceDecoder.Segmenter() {
            public List<float[]> accept(byte[] pcm) { return List.of(); }
            public void close() { throw new IllegalStateException("segmenter close"); }
        },new UtteranceDecoder.Transcriber() {
            public String recognize(float[] speech) { transcribed.incrementAndGet(); return "hallucinated"; }
            public void close() { closed.incrementAndGet(); }
        });
        assertTrue(decoder.accept(new byte[1280]).isEmpty());
        assertEquals(0,transcribed.get());
        assertThrows(IllegalStateException.class,decoder::close);
        assertEquals(1,closed.get());
    }
}
