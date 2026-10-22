package com.example.voicekill;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeechHandlerNoticeTest {
    @Test void reviewedNoticeShowsBothOriginalAndActualDecisionSentence() {
        assertEquals("[VoiceKill] Alex 说出了危险词「mc」（识别：ABC）（复核：amc）",SpeechHandler.killNotice("mc","Alex","ABC","amc").getString());
    }
    @Test void noticeRetainsTheOriginalTranscriptIncludingCaseSpacingAndPunctuation() {
        String raw="我刚刚说的是 Q A Q，不是别的！";
        assertEquals("[VoiceKill] Alex 说出了危险词「qaq」（识别："+raw+"）",SpeechHandler.killNotice("qaq","Alex",raw).getString());
    }
}
