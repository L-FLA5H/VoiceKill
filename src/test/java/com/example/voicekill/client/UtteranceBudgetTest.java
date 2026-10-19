package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtteranceBudgetTest {
    @Test void idleDoesNotConsumeTheNextSpeakersBudget() {
        var budget=new UtteranceBudget(5);
        assertFalse(budget.advance(16000*60,false));
        assertFalse(budget.advance(16000*4,true));
        assertTrue(budget.advance(16000,true));
        budget.reset();
        assertFalse(budget.advance(16000*20,false));
        assertFalse(budget.advance(16000,true));
    }
    @Test void revisionsDoNotRestartTheTimerAndFrameSizeDoesNotChangeTheBudget() {
        var budget=new UtteranceBudget(5);
        assertFalse(budget.advance(640,true));
        for(int i=0;i<123;i++) assertFalse(budget.advance(640,false));
        assertTrue(budget.advance(640,false));
        assertThrows(IllegalArgumentException.class,()->new UtteranceBudget(Float.NaN));
    }
}
