package com.example.voicekill.client;

import com.example.voicekill.speech.SpeechPacket.RoundSpan;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundTrackingDecoderTest {
    private final String session=UUID.randomUUID().toString(),a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),c=UUID.randomUUID().toString();
    private RoundTrackingDecoder.Context context(String round){return new RoundTrackingDecoder.Context(session,round,true);}
    @Test void rotationRetainsTheWholeSentenceAndUsesItsAudioRange() throws Exception {
        var ctx=new AtomicReference<>(context(a));var discards=new AtomicInteger();var frames=new AtomicInteger();
        var base=new CaptureSession.Decoder(){
            public List<String> accept(byte[] pcm){return frames.incrementAndGet()==3?List.of("完整的一句话"):List.of();}
            public CaptureSession.SampleRange utteranceRange(){return new CaptureSession.SampleRange(0,1280);}
            public void discard(){discards.incrementAndGet();}public void close(){}
        };
        try(var decoder=new RoundTrackingDecoder(base,ctx::get)) {
            decoder.accept(new byte[1280]);ctx.set(context(b));decoder.accept(new byte[1280]);ctx.set(context(c));
            assertEquals(List.of("完整的一句话"),decoder.accept(new byte[1280]));
            assertEquals(new RoundSpan(a,b),decoder.rounds());assertEquals(0,discards.get());assertEquals(0,decoder.epoch());
        }
    }
    @Test void idleBeforeAWordChangeDoesNotMakeTheOldRoundEligible() throws Exception {
        var ctx=new AtomicReference<>(context(a));var frames=new AtomicInteger();
        var base=new CaptureSession.Decoder(){
            public List<String> accept(byte[] pcm){return frames.incrementAndGet()==3?List.of("面包"):List.of();}
            public CaptureSession.SampleRange utteranceRange(){return new CaptureSession.SampleRange(640,1280);}
            public void close(){}
        };
        try(var decoder=new RoundTrackingDecoder(base,ctx::get)){decoder.accept(new byte[1280]);ctx.set(context(b));decoder.accept(new byte[1280]);decoder.accept(new byte[1280]);assertEquals(new RoundSpan(b,b),decoder.rounds());}
    }
    @Test void reconnectAndStopStartDiscardTheOldUtterance() throws Exception {
        var ctx=new AtomicReference<>(context(a));var discards=new AtomicInteger();
        var base=new CaptureSession.Decoder(){public List<String> accept(byte[] pcm){return List.of();}public void discard(){discards.incrementAndGet();}public void close(){}};
        try(var decoder=new RoundTrackingDecoder(base,ctx::get)){
            decoder.accept(new byte[1280]);ctx.set(new RoundTrackingDecoder.Context(session,a,false));decoder.accept(new byte[1280]);
            ctx.set(new RoundTrackingDecoder.Context(UUID.randomUUID().toString(),b,true));decoder.accept(new byte[1280]);
            ctx.set(RoundTrackingDecoder.Context.empty());decoder.accept(new byte[1280]);assertEquals(3,discards.get());assertEquals(3,decoder.epoch());
        }
    }
    @Test void reviewTimeCannotMoveTheUtteranceIntoTheNextRound() throws Exception {
        var ctx=new AtomicReference<>(context(a));
        var base=new CaptureSession.Decoder(){public List<String> accept(byte[] pcm){return List.of("面包");}public CaptureSession.SampleRange utteranceRange(){return new CaptureSession.SampleRange(0,640);}public void close(){}};
        try(var decoder=new EnhancedDecoder(new RoundTrackingDecoder(base,ctx::get),new SentencePostprocessor(new SentencePostprocessor.Reviewer(){
            public String recognize(float[] audio){ctx.set(context(b));return "面包";}public void close(){}
        },null),10)) {
            byte[] pcm=new byte[1280];Arrays.fill(pcm,(byte)32);decoder.accept(pcm);assertEquals(new RoundSpan(a,a),decoder.rounds());
        }
    }
}
