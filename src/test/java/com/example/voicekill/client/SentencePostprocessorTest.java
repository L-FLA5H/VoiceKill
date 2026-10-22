package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SentencePostprocessorTest {
    @BeforeAll static void ready(){com.example.voicekill.speech.GameTerms.awaitReady();}
    @Test void stableLetterReviewAddsAnAlternativeWithoutReplacingPrimary() {
        assertEquals(List.of("TV Q","q a q"),SentencePostprocessor.candidates(List.of("TV Q"),"q a q","qaq"));
        assertEquals(List.of("老板","P老板"),SentencePostprocessor.candidates(List.of("老板"),"P老板","p 老板"));
    }
    @Test void stableChineseIsTaggedAndContradictoryLettersAreRejected() {
        assertEquals(List.of("干"),SentencePostprocessor.candidates(List.of("干"),"赶","赶"));
        assertEquals(List.of("干"),SentencePostprocessor.candidates(List.of("干"),"が","が"));
        assertEquals(List.of("qaq"),SentencePostprocessor.candidates(List.of("qaq"),"qa","qa"));
        assertEquals(List.of("才","干"),SentencePostprocessor.candidates(List.of("才"),"干","干"));
        assertEquals(List.of("D站"),SentencePostprocessor.candidates(List.of("D站"),"B站","b站"));
        assertEquals(List.of("豆腐Q汤宝宝"),SentencePostprocessor.candidates(List.of("豆腐Q汤宝宝"),"豆腐汤宝宝P","豆腐汤宝宝p"));
    }
    @Test void disagreementAndFullNativeAlternativesLeaveTheBaselineAlone() {
        assertEquals(List.of("Q A"),SentencePostprocessor.candidates(List.of("Q A"),"QAQ","QA"));
        var raw=List.of("one","two","three");
        assertEquals(raw,SentencePostprocessor.candidates(raw,"qaq","qaq"));
    }
    @Test void punctuationMayOnlyInsertFormatting() {
        assertTrue(SentencePostprocessor.sameContent("我说的是 Q A Q","我说的是QAQ。"));
        assertFalse(SentencePostprocessor.sameContent("我才回来","我干回来。"));
        assertFalse(SentencePostprocessor.sameContent("P老板","老板。"));
    }
    @Test void incompleteReviewRetainsThePrimaryForBothDisplayAndJudgment() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var post=new SentencePostprocessor(new SentencePostprocessor.Reviewer(){
            public String recognize(float[] audio){calls.incrementAndGet();return "Q A";}
            public void close(){}
        },null)) {
            float[] audio=new float[16000];java.util.Arrays.fill(audio,.02f);
            assertEquals(List.of("Q A Q"),post.process(List.of("Q A Q"),audio));
            assertEquals("primary-retained",post.snapshot().reason());
            assertEquals("Q A Q",post.snapshot().punctuated());
            assertFalse(post.snapshot().accepted());assertEquals(1,calls.get());
        }
    }
}
