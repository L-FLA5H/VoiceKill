package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.voicekill.speech.RecognitionHealth.Issue.*;

class RecognitionHealthTest {
    private RecognitionSilence timer(){var timer=new RecognitionSilence();timer.configure(new RecognitionSilence.Rules(true,5,2,1));return timer;}
    @Test void unavailableCaptureAndModelsCannotAccumulateSilenceDamage() {
        for(var issue:new RecognitionHealth.Issue[]{MICROPHONE,MODEL_LOADING,MODEL_FAILED,STALLED,UNKNOWN}) {
            var health=new RecognitionHealth();health.reset("game");var timer=timer();
            for(int tick=0;tick<1000;tick++){health.report("p","game",issue,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)),issue.name());}
        }
    }
    @Test void healthyHeartbeatIsNotSpeechAndDoesNotPreventTheNormalPenalty() {
        var health=new RecognitionHealth();health.reset("game");var timer=timer();
        for(int tick=0;tick<100;tick++){health.report("p","game",READY,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)));}
        assertTrue(timer.step("p",100,health.ready("p",100)));
    }
    @Test void expiredHeartbeatAndRecoveryNeverCarryOldDamageDebt() {
        var health=new RecognitionHealth();health.reset("game");var timer=timer();
        health.report("p","game",READY,0);assertFalse(timer.step("p",0,health.ready("p",0)));
        assertFalse(health.ready("p",81));assertFalse(timer.step("p",1000,health.ready("p",1000)));
        for(int tick=1001;tick<1101;tick++){health.report("p","game",READY,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)));}
        health.report("p","game",READY,1101);assertTrue(timer.step("p",1101,health.ready("p",1101)));
    }
    @Test void staleSessionsAndOnePlayersFailureCannotAffectAnotherPlayer() {
        var health=new RecognitionHealth();health.reset("a");health.report("one","a",READY,0);health.report("two","a",MODEL_FAILED,0);
        assertTrue(health.ready("one",1));assertFalse(health.ready("two",1));
        health.reset("b");assertFalse(health.report("one","a",READY,2));assertFalse(health.ready("one",2));
        assertTrue(health.report("one","b",READY,3));health.forget("one");assertFalse(health.ready("one",3));
        health.clear();assertFalse(health.report("one","b",READY,4));
    }
    @Test void bottomWarningsAreRateLimitedAndRecoveryClearsTheWarningSchedule() {
        var health=new RecognitionHealth();health.reset("a");
        assertFalse(health.noticeDue("p",0));assertFalse(health.noticeDue("p",79));assertTrue(health.noticeDue("p",80));
        assertFalse(health.noticeDue("p",279));assertTrue(health.noticeDue("p",280));
        health.report("p","a",READY,281);assertFalse(health.noticeDue("p",281));
        health.report("p","a",MODEL_FAILED,282);assertFalse(health.noticeDue("p",282));assertTrue(health.noticeDue("p",322));
        assertTrue(MODEL_FAILED.warning().contains("可能无法正常游戏"));
    }
    @Test void startingAndReceivingTheFirstHealthyHeartbeatNeverShowsAFalseWarning() {
        var health=new RecognitionHealth();health.reset("a");
        for(int tick=0;tick<20;tick++){assertFalse(health.ready("p",tick));assertFalse(health.noticeDue("p",tick));}
        for(int tick=20;tick<300;tick++){health.report("p","a",READY,tick);assertFalse(health.noticeDue("p",tick));}
        health.reset("b");assertFalse(health.noticeDue("p",0));assertFalse(health.report("p","a",READY,1));
        health.report("p","b",READY,10);assertFalse(health.noticeDue("p",10));
    }
    @Test void shortMicrophoneGlitchesDoNotWarnButARealFailureStillPausesImmediately() {
        var health=new RecognitionHealth();health.reset("a");
        health.report("p","a",MICROPHONE,0);
        for(int tick=0;tick<30;tick++){assertFalse(health.ready("p",tick));assertFalse(health.noticeDue("p",tick));}
        health.report("p","a",READY,30); // Recovery must clear pending warnings even between notice ticks.
        health.report("p","a",MICROPHONE,31);assertFalse(health.noticeDue("p",31));
        assertFalse(health.noticeDue("p",70));assertTrue(health.noticeDue("p",71));
        health.report("p","a",MODEL_FAILED,72);assertFalse(health.noticeDue("p",72));assertFalse(health.noticeDue("p",112));
    }
    @Test void gameplayStartKeepsPreflightHealthWithoutRenewingItOrAcceptingOldReports() {
        var health=new RecognitionHealth();health.reset("joined");
        health.report("p","joined",READY,100);assertTrue(health.ready("p",120));
        health.bindSession("playing");assertTrue(health.ready("p",120));assertFalse(health.noticeDue("p",120));
        assertFalse(health.report("p","joined",READY,170));assertFalse(health.ready("p",181));
        assertTrue(health.report("p","playing",READY,182));assertTrue(health.ready("p",182));
        health.reset("different-world");assertFalse(health.ready("p",0));
    }
    @Test void monitoringBeforeStartAndAfterStopCannotApplyGameplayDamage() {
        var health=new RecognitionHealth();health.reset("joined");var timer=timer();
        for(int tick=0;tick<200;tick++){health.report("p","joined",READY,tick);assertFalse(timer.step("p",tick,false));}
        health.bindSession("playing");
        for(int tick=200;tick<300;tick++){health.report("p","playing",READY,tick);assertFalse(timer.step("p",tick,health.ready("p",tick)));}
        assertTrue(timer.step("p",300,true));
        for(int tick=301;tick<500;tick++){health.report("p","playing",READY,tick);assertTrue(health.ready("p",tick));assertFalse(timer.step("p",tick,false));}
        health.bindSession("restarted");assertTrue(health.ready("p",500));assertFalse(timer.step("p",500,true));
    }
}
