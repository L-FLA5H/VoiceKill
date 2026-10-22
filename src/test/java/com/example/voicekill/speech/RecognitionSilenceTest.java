package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionSilenceTest {
    private RecognitionSilence enabled(){var timer=new RecognitionSilence();timer.configure(new RecognitionSilence.Rules(true,5,2,.5f));return timer;}
    @Test void firstDamageWaitsForTimeoutAndThenUsesTheConfiguredInterval() {
        var timer=enabled();assertFalse(timer.step("p",0,true));
        assertFalse(timer.step("p",99,true));assertTrue(timer.step("p",100,true));
        assertFalse(timer.step("p",109,true));assertTrue(timer.step("p",110,true));
        assertEquals(2,timer.rules().damageAmount());
    }
    @Test void recognizedTextStopsRecurringDamageAndRestartsTheFullWait() {
        var timer=enabled();timer.step("p",0,true);assertTrue(timer.step("p",100,true));
        timer.recognized("p",105);assertFalse(timer.step("p",110,true));assertFalse(timer.step("p",204,true));assertTrue(timer.step("p",205,true));
    }
    @Test void playersHaveIndependentGracePeriods() {
        var timer=enabled();timer.step("a",0,true);timer.step("b",40,true);timer.recognized("a",70);
        assertFalse(timer.step("a",140,true));assertTrue(timer.step("b",140,true));assertTrue(timer.step("a",170,true));
    }
    @Test void disabledAndIneligiblePlayersCannotTakeDamageAndRespawnGetsFreshGrace() {
        var off=new RecognitionSilence();assertFalse(off.step("p",0,true));assertFalse(off.step("p",100000,true));
        var timer=enabled();timer.step("p",0,true);assertFalse(timer.step("p",200,false));
        assertFalse(timer.step("p",201,true));assertFalse(timer.step("p",300,true));assertTrue(timer.step("p",301,true));
    }
    @Test void stoppingDisconnectingAndRuleChangesDoNotKeepOldDebt() {
        var timer=enabled();timer.step("p",0,true);timer.clear();assertFalse(timer.step("p",1000,true));
        timer.forget("p");assertFalse(timer.step("p",2000,true));
        timer.configure(new RecognitionSilence.Rules(true,5,3,.5f));assertFalse(timer.step("p",3000,true));assertTrue(timer.step("p",3100,true));
    }
    @Test void identicalRulesIncludingWordRotationsDoNotResetTheDeadline() {
        var timer=enabled();timer.step("p",0,true);
        for(int i=1;i<100;i++){timer.configure(new RecognitionSilence.Rules(true,5,2,.5f));assertFalse(timer.step("p",i,true));}
        assertTrue(timer.step("p",100,true));
    }
    @Test void lagNeverProducesABurstOfCatchUpDamage() {
        var timer=enabled();timer.step("p",0,true);assertTrue(timer.step("p",10000,true));assertFalse(timer.step("p",10000,true));assertFalse(timer.step("p",10009,true));assertTrue(timer.step("p",10010,true));
    }
    @Test void invalidNumbersAreClampedAndOnlyActualCharactersCount() {
        var fallback=new RecognitionSilence.Rules(true,Float.NaN,Float.POSITIVE_INFINITY,Float.NaN);
        assertEquals(20,fallback.timeoutSeconds());assertEquals(2,fallback.damageAmount());assertEquals(4,fallback.intervalSeconds());
        var bounds=new RecognitionSilence.Rules(true,-1,500,0);assertEquals(5,bounds.timeoutSeconds());assertEquals(20,bounds.damageAmount());assertEquals(.5f,bounds.intervalSeconds());
        assertFalse(RecognitionSilence.hasText(" ？！…"));assertFalse(RecognitionSilence.hasText(null));
        assertTrue(RecognitionSilence.hasText("我"));assertTrue(RecognitionSilence.hasText("Q A Q"));assertTrue(RecognitionSilence.hasText("123"));
    }
}
