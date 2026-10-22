package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndpointPolicyTest {
    @Test void shortSyllablesKeepConservativeSilenceConfirmation() {
        var endpoint = ZipformerDecoder.endpointConfig(new ZipformerDecoder.Settings(.35f,true,1.5f,5f));
        assertEquals(.6f, endpoint.getRule3().getMinTrailingSilence());
        assertEquals(0, endpoint.getRule3().getMinUtteranceLength());
        assertTrue(endpoint.getRule3().getMustContainNonSilence());
        assertEquals(.35f, endpoint.getRule2().getMinTrailingSilence());
        assertEquals(1.2f, endpoint.getRule2().getMinUtteranceLength());
    }
    @Test void slowSettingDoesNotGetBypassedByShortWordFallback() {
        var endpoint = ZipformerDecoder.endpointConfig(new ZipformerDecoder.Settings(.9f, true, 1.5f));
        assertEquals(.9f, endpoint.getRule3().getMinTrailingSilence());
    }
    @Test void biasCannotExceedAcceptedRange() {
        assertThrows(IllegalArgumentException.class, () -> new ZipformerDecoder.Settings(.35f,true,2.5f));
        assertThrows(IllegalArgumentException.class, () -> new ZipformerDecoder.Settings(Float.NaN,true,1.5f));
    }
    @Test void continuousSpeechHasAFiveSecondSettlementBudget() {
        var legacy=new ZipformerDecoder.Settings(.35f,true,1.5f,5f);
        var rule=ZipformerDecoder.endpointConfig(legacy).getRule1();
        assertEquals(5f,legacy.maxUtteranceSeconds());
        assertEquals(0f,rule.getMinUtteranceLength());
        assertEquals(2.4f,rule.getMinTrailingSilence());
        assertFalse(rule.getMustContainNonSilence());
        assertThrows(IllegalArgumentException.class,()->new ZipformerDecoder.Settings(.35f,true,1.5f,Float.NaN));
    }

    @Test void sentenceDefaultsAllowBreathingAndTenSecondsOfContext() {
        var settings=ZipformerDecoder.Settings.defaults();
        var endpoint=ZipformerDecoder.endpointConfig(settings);
        assertEquals(10f,settings.maxUtteranceSeconds());
        assertEquals(.6f,endpoint.getRule2().getMinTrailingSilence());
        assertEquals(.6f,endpoint.getRule3().getMinTrailingSilence());
    }
}
