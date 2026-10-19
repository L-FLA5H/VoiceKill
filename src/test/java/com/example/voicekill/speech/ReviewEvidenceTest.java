package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReviewEvidenceTest {
    private List<WordMatcher.Word> word(String text){return List.of(new WordMatcher.Word(text,"manual",List.of()));}
    private SpeechWindow.Result review(SpeechWindow window,String raw,String revised,String target,long time) {
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of(raw,revised),Set.of(1)));
        return window.accept("p",packet,word(target),time);
    }
    @Test void reviewedLettersUseContainmentAsRequested() {
        assertNotNull(review(new SpeechWindow(),"ABC","amc","mc",100).hit());
        assertEquals("review-exact",review(new SpeechWindow(),"TV Q","q a q","qaq",100).hit().mode());
        assertNotNull(review(new SpeechWindow(),"我说的视频老板","我说的是P老板","p老板",100).hit());
    }
    @Test void reviewDoesNotGainFuzzyAliasesOrChinesePunishments() {
        assertNull(review(new SpeechWindow(),"今天很好","豆腐汤宝宝","豆腐汤宝宝",100).hit());
        assertNull(review(new SpeechWindow(),"才","干","干",100).hit());
        assertNull(review(new SpeechWindow(),"今天","P老板","p老板",100).hit());
        assertNull(review(new SpeechWindow(),"今天","扣哎扣","qaq",100).hit());
        assertNull(review(new SpeechWindow(),"xxxxxx","abcdeg","abcdef",100).hit());
    }
    @Test void reviewIsNotStitchedIntoFutureSegments() {
        var window=new SpeechWindow();
        assertNull(review(window,"今天","qa","qaq",100).hit());
        assertNull(window.accept("p",SpeechPacket.parse("q"),word("qaq"),200).hit());
    }
    @Test void nativeAlternativesAndOldPacketsRemainSupported() {
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("今天","豆腐汤宝宝")));
        assertTrue(packet.literalCandidates().isEmpty());
        assertNotNull(new SpeechWindow().accept("p",packet,word("豆腐汤宝宝"),100).hit());
    }
    @Test void provenanceIsValidatedAndSurvivesDeduplication() {
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("raw","raw","QAQ"),Set.of(2)));
        assertEquals(List.of("raw","QAQ"),packet.candidates());assertEquals(Set.of(1),packet.literalCandidates());
        assertThrows(IllegalArgumentException.class,()->SpeechPacket.encode(List.of("raw"),Set.of(0)));
        String wire=SpeechPacket.encode(List.of("raw","QAQ"),Set.of(1));
        assertTrue(SpeechPacket.parse(wire.replace("|L=1","|L=0")).candidates().isEmpty());
    }
}
