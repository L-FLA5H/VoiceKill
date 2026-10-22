package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionActivityTest {
    @Test void emptyNoiseAndStalePartialsCannotKeepAPlayerAlive() {
        var activity=new RecognitionActivity();assertFalse(activity.observe("s","",true,0));assertFalse(activity.observe("s","？！",true,100));
        assertTrue(activity.observe("s","你好",true,200));assertFalse(activity.observe("s","你好",true,5000));assertFalse(activity.observe("s","你 好。",true,10000));
    }
    @Test void actualNewTextIsRateLimitedWithoutLosingPendingActivity() {
        var activity=new RecognitionActivity();assertTrue(activity.observe("s","你",true,0));assertFalse(activity.observe("s","你好",true,400));assertTrue(activity.observe("s","你好",true,1000));
        assertFalse(activity.observe("s","你好",true,3000));
    }
    @Test void repeatedFinalSentencesAndNewSessionsStillCount() {
        var activity=new RecognitionActivity();assertTrue(activity.observe("a","你好",false,0));assertTrue(activity.observe("a","你好",false,1000));assertTrue(activity.observe("b","你好",true,1100));
    }
    @Test void anOldPendingPartialIsNotReportedAfterALongStall() {
        var activity=new RecognitionActivity();activity.observe("s","你",true,0);activity.observe("s","你好",true,100);
        assertFalse(activity.observe("s","",true,10000));
    }
}
