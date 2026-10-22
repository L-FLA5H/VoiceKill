package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundSpeechWindowTest {
    private List<WordMatcher.Word> words(String word){return List.of(new WordMatcher.Word(word,"manual",List.of()));}
    private SpeechPacket packet(String text,String first,String last){return SpeechPacket.parse(SpeechPacket.encode(List.of(text),Set.of(),false,new SpeechPacket.RoundSpan(first,last)));}
    @Test void overlappingSentenceUsesBothRoundsAndPrefersTheNewerRound() {
        var window=new RoundSpeechWindow();String first=window.rotate(words("钻石"),0),last=window.rotate(words("面包"),100);
        assertEquals("钻石",window.accept("p",packet("我拿钻石",first,last),110).hit().word());
        assertEquals("面包",window.accept("p",packet("我拿钻石和面包",first,last),120).hit().word());
    }
    @Test void newRoundWinsOverAnOlderExactOrPreferredHit() {
        var window=new RoundSpeechWindow();String first=window.rotate(words("钻石"),0),last=window.rotate(words("qaq"),100);
        assertEquals("qaq",window.accept("p",packet("钻石和扣哎扣",first,last),110).hit().word());
    }
    @Test void sentencesStartedAfterRotationCannotTriggerOldWords() {
        var window=new RoundSpeechWindow();window.rotate(words("钻石"),0);String last=window.rotate(words("面包"),100);
        assertNull(window.accept("p",packet("钻石",last,last),110).hit());
        assertNull(window.accept("p",SpeechPacket.parse("钻石"),120).hit());
    }
    @Test void slowReviewDoesNotIncludeRoundsAfterTheAudioEnded() {
        var window=new RoundSpeechWindow();String a=window.rotate(words("钻石"),0),b=window.rotate(words("面包"),100);
        window.rotate(words("蛋糕"),200);
        assertNull(window.accept("p",packet("蛋糕",a,b),210).hit());
        assertEquals("面包",window.accept("p",packet("钻石和面包",a,b),220).hit().word());
    }
    @Test void successiveRotationsUseTheLatestOverlappingRound() {
        var window=new RoundSpeechWindow();String a=window.rotate(words("钻石"),0);window.rotate(words("面包"),100);String c=window.rotate(words("蛋糕"),200);
        assertEquals("蛋糕",window.accept("p",packet("钻石面包蛋糕",a,c),210).hit().word());
    }
    @Test void rotateDoesNotJoinOldSentenceFragmentsAndDuplicatesRemainSuppressed() {
        var window=new RoundSpeechWindow();String a=window.rotate(words("豆腐汤宝宝"),0);
        var first=packet("豆腐汤",a,a);assertNull(window.accept("p",first,10).hit());
        String b=window.rotate(words("豆腐汤宝宝"),100);
        assertNull(window.accept("p",packet("宝宝",a,b),110).hit());
        assertTrue(window.accept("p",first,120).duplicate());
    }
    @Test void unknownReversedStoppedAndExpiredRoundRangesAreIgnored() {
        var window=new RoundSpeechWindow();String a=window.rotate(words("钻石"),0),b=window.rotate(words("面包"),100);
        assertTrue(window.accept("p",packet("钻石",b,a),110).stale());
        assertTrue(window.accept("p",packet("钻石",UUID.randomUUID().toString(),b),120).stale());
        assertTrue(window.accept("p",packet("钻石",a,b),60200).stale());
        window.clear();assertTrue(window.accept("p",packet("面包",b,b),60201).stale());
    }
    @Test void roundMetadataRoundTripsWithReviewAndRejectsInvalidTokens() {
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        String wire=SpeechPacket.encode(List.of("才","干"),Set.of(1),true,new SpeechPacket.RoundSpan(a,b));
        var packet=SpeechPacket.parse(wire);assertEquals(new SpeechPacket.RoundSpan(a,b),packet.rounds());assertTrue(packet.reviewConfirmed());assertEquals(Set.of(1),packet.literalCandidates());
        assertTrue(SpeechPacket.parse(wire.replace(a,"invalid")).candidates().isEmpty());
        assertTrue(SpeechPacket.parse(wire.replace("|R=","|R="+a+","+b+"|R=")).candidates().isEmpty());
    }
    @Test void oldRoundAliasesRemainFrozenWhenSettingsAreEdited() {
        var aliases=new ArrayList<>(List.of("老外号"));var window=new RoundSpeechWindow();
        String a=window.rotate(List.of(new WordMatcher.Word("老朋友","manual",aliases)),0);
        aliases.clear();aliases.add("新外号");String b=window.rotate(words("面包"),100);
        assertEquals("老朋友",window.accept("p",packet("老外号",a,b),110).hit().word());
        assertNull(window.accept("p",packet("新外号",a,b),120).hit());
    }
}
