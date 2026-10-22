package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CaptureProgressTest {
    @Test void dangerousInterimTextIsDisplayOnlyAndNeverSentAsFinal() throws Exception {
        var ended=new CountDownLatch(1);
        var source=new CaptureSession.Source(){int n;public int read(byte[] pcm){return n++<3?pcm.length:-1;}public void close(){ended.countDown();}};
        var decoder=new CaptureSession.Decoder(){
            int n;
            public List<String> accept(byte[] pcm){return ++n==3?List.of("才"):List.of();}
            public String partial(){return "干";}
            public String displayFinal(List<String> candidates){return "才。";}
            public void close(){}
        };
        var sent=new CopyOnWriteArrayList<List<String>>();var displayed=new CopyOnWriteArrayList<CaptureSession.Transcript>();
        var session=new CaptureSession(source,decoder,sent::add,failure->fail(failure),displayed::add);
        session.start();assertTrue(ended.await(2,TimeUnit.SECONDS));assertTrue(session.stop());
        assertEquals(List.of(List.of("才")),sent);
        assertTrue(displayed.contains(new CaptureSession.Transcript("干",true)));
        assertEquals(new CaptureSession.Transcript("才。",false),displayed.getLast());
    }
}
