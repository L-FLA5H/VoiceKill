package com.example.voicekill.speech;

import com.example.voicekill.util.WordAliases;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpeechWindowTest {
    private List<WordMatcher.Word> words(String text) {
        return List.of(new WordMatcher.Word(text, "manual", WordAliases.variants(text, null)));
    }
    private SpeechWindow.Result accept(SpeechWindow window, String text, String target, long time) {
        return window.accept("player", SpeechPacket.parse(text), words(target), time);
    }

    @Test void pronunciationDoesNotDependOnAliasEnumerationLimit() {
        for (String input : List.of("扣哎扣", "球哎球", "秋爱求", "q哎q")) {
            assertNotNull(WordMatcher.find(words("qaq"), input, false, 0), input);
        }
        assertNotNull(WordMatcher.find(words("p老板"), "劈老板", false, 0));
        assertNull(WordMatcher.find(words("qaq"), "球哎", false, 0));
        assertNull(WordMatcher.find(words("p老板"), "老板", false, 0));
    }

    @Test void longNamesRetainHomophoneAndEditTolerance() {
        assertEquals("pronunciation", WordMatcher.find(words("豆腐汤宝宝"), "豆腐唐包包", false, 0).mode());
        assertNotNull(WordMatcher.find(words("你好呀装装"), "你好呀中中", false, 0));
        assertNull(WordMatcher.find(words("豆腐汤宝宝"), "今天天气好", false, 0));
    }

    @Test void shortWordsRequirePrimaryCandidate() {
        var window = new SpeechWindow();
        assertNull(accept(window, "才", "干", 100).hit());
        assertNull(accept(window, "才\u0001干", "干", 200).hit());
        assertNotNull(accept(window, "干", "干", 300).hit());
        assertNull(accept(new SpeechWindow(), "他", "她", 400).hit());
    }

    @Test void alternativesOfOneUtteranceCannotFormANewWord() {
        assertNull(accept(new SpeechWindow(), "秒\u0001切", "秒切", 100).hit());
    }

    @Test void distinctFragmentsAndRepeatedCharactersArePreserved() {
        var window = new SpeechWindow();
        assertNull(accept(window, "哈", "哈哈", 100).hit());
        assertNotNull(accept(window, "哈", "哈哈", 200).hit());
        window.clear();
        assertNull(accept(window, "豆腐汤", "豆腐汤宝宝", 300).hit());
        accept(window, "宝", "豆腐汤宝宝", 400);
        var result = accept(window, "宝", "豆腐汤宝宝", 500);
        assertNotNull(result.hit());
        assertEquals("豆腐汤宝宝", result.hit().evidence());
    }

    @Test void alternativeCanContinueAcrossRealPackets() {
        var window = new SpeechWindow();
        accept(window, "妙\u0001秒", "秒切", 100);
        assertNotNull(accept(window, "切", "秒切", 200).hit());
    }

    @Test void oldTailCannotTriggerWithoutFreshEvidence() {
        var window = new SpeechWindow();
        accept(window, "干", "钻石", 100);
        assertNull(accept(window, "才", "干", 200).hit());
        window.clear();
        accept(window, "秒", "秒切", 300);
        window.clear();
        assertNull(accept(window, "切", "秒切", 400).hit());
    }

    @Test void timeoutAndPlayersAreIsolated() {
        var window = new SpeechWindow();
        accept(window, "秒", "秒切", 100);
        assertNull(window.accept("another", SpeechPacket.parse("切"), words("秒切"), 200).hit());
        assertNull(accept(window, "切", "秒切", 6000).hit());
    }

    @Test void onlyPacketIdentityIsDeduplicated() {
        String raw = SpeechPacket.encode(List.of("哈"));
        var window = new SpeechWindow();
        assertFalse(accept(window, raw, "哈哈", 100).duplicate());
        assertTrue(accept(window, raw, "哈哈", 200).duplicate());
        assertNotNull(accept(window, SpeechPacket.encode(List.of("哈")), "哈哈", 300).hit());
    }

    @Test void malformedAndOversizedPacketsAreBounded() {
        assertTrue(SpeechPacket.parse("[voicekill:2:invalid]\u0001干").candidates().isEmpty());
        assertTrue(SpeechPacket.parse("x".repeat(513)).candidates().isEmpty());
        assertEquals(3, SpeechPacket.parse("一\u0001二\u0001三\u0001四").candidates().size());
        assertTrue(SpeechPacket.parse("\u0001").candidates().isEmpty());
    }
}
