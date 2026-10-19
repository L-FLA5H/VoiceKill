package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EnhancedDecoderTest {
    @Test void closingDuringModelLoadReleasesLateModelsAndDoesNotWaitForDownload() throws Exception {
        var entered=new CountDownLatch(1);var allow=new CountDownLatch(1);var released=new CountDownLatch(1);
        var baseClosed=new AtomicInteger();
        var base=new CaptureSession.Decoder() {
            public List<String> accept(byte[] pcm){return List.of("原文");}
            public void close(){baseClosed.incrementAndGet();}
        };
        var decoder=new EnhancedDecoder(base,()->{
            entered.countDown();
            while(allow.getCount()>0)try{allow.await();}catch(InterruptedException ignored){}
            return new SentencePostprocessor(null,new SentencePostprocessor.Punctuator(){
                public String punctuate(String text){return text+"。";}
                public void close(){released.countDown();}
            });
        },5);
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        assertEquals(List.of("原文"),decoder.accept(new byte[1280]));
        decoder.close();decoder.close();assertEquals(1,baseClosed.get());
        allow.countDown();assertTrue(released.await(1,TimeUnit.SECONDS));
        assertEquals("closed",decoder.snapshot().get("state"));
    }
    @Test void punctuationDoesNotModifyTheMatchingCandidates() throws Exception {
        var base=new CaptureSession.Decoder(){
            public List<String> accept(byte[] pcm){return List.of("我说的是QAQ");}
            public void close(){}
        };
        var processor=new SentencePostprocessor(null,new SentencePostprocessor.Punctuator(){
            public String punctuate(String text){return text+"。";}
            public void close(){}
        });
        try(var decoder=new EnhancedDecoder(base,processor,5)) {
            assertEquals(List.of("我说的是QAQ"),decoder.accept(new byte[1280]));
            assertEquals("我说的是QAQ。",processor.snapshot().punctuated());
        }
    }
}
