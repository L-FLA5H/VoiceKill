package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionHintsTest {
    @Test void sourcePriorityDoesNotAllowInjectionOrBiasShortChineseWords() {
        var words=List.of(new com.example.voicekill.speech.WordMatcher.Word("cpu","random",List.of()),
                new com.example.voicekill.speech.WordMatcher.Word("p老板","name",List.of()),
                new com.example.voicekill.speech.WordMatcher.Word("豆腐汤宝宝","manual",List.of()),
                new com.example.voicekill.speech.WordMatcher.Word("干","manual",List.of()),
                new com.example.voicekill.speech.WordMatcher.Word("qaq/干:99","manual",List.of()));
        String hints=RecognitionHints.encodePriority(words);
        assertTrue(hints.contains("P老板 :1.5"));assertTrue(hints.contains("CPU :0.75"));
        assertTrue(hints.contains("豆腐汤宝宝 :0.5"));assertFalse(hints.contains(":99"));
        assertFalse(hints.contains("干 :"));
    }
    @Test void onlyMultiCharacterLatinNamesAreBiased() {
        assertEquals(List.of("p老板", "qaq"), RecognitionHints.names(List.of("qaq","p老板","干","才","豆腐汤宝宝","P","p老板")));
    }
    @Test void punctuationCannotInjectNativeHotwordScoresOrSeparators() {
        assertTrue(RecognitionHints.names(List.of("qaq/干:99","p老板\n干:3","x:y")).isEmpty());
    }
    @Test void onlyLiteralNamesAndLetterSpellingsAreEncoded() {
        String encoded = RecognitionHints.encode(List.of("qaq","p老板"));
        assertTrue(encoded.contains("QAQ"));
        assertTrue(encoded.contains("Q A Q"));
        assertTrue(encoded.contains("P老板"));
        assertTrue(encoded.contains("P 老板"));
        assertFalse(encoded.contains("劈老板"));
        assertFalse(encoded.contains("扣"));
    }
    @Test void arbitraryNamesUseTheSameScriptBoundaryRules() {
        assertEquals("ABC主播/A B C 主播/B站/B 站/MC/M C", RecognitionHints.encode(List.of("abc主播","b站","mc")));
    }
    @Test void changingOnlyDisplayOrderDoesNotResetRecognition() {
        assertEquals(RecognitionHints.encode(List.of("qaq","p老板")), RecognitionHints.encode(List.of("p老板","qaq")));
    }
}
