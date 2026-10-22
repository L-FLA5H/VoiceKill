package com.example.voicekill.speech;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameTermsTest {
    @BeforeAll static void ready(){GameTerms.awaitReady();}
    private List<WordMatcher.Word> words(String target){return List.of(new WordMatcher.Word(target,"random",List.of()));}
    private SpeechWindow.Result decide(String raw,String review,String target,boolean confirmed) {
        var packet=SpeechPacket.parse(SpeechPacket.encode(raw.equals(review)?List.of(raw):List.of(raw,review),raw.equals(review)?Set.of():Set.of(1),confirmed));
        return new SpeechWindow().accept("p",packet,words(target),100);
    }
    @Test void incompleteOrSameSoundReviewDoesNotVetoACompleteFirstTranscript() {
        assertTrue(GameTerms.retainPrimary("Q A Q","Q A"));
        assertTrue(GameTerms.retainPrimary("干","赶"));
        assertTrue(GameTerms.retainPrimary("红石中继器","红石中继"));
        assertTrue(GameTerms.retainPrimary("红十种记起","红十种记"));
        assertFalse(GameTerms.retainPrimary("好吃的","how much the shirt is"));
        assertFalse(GameTerms.retainPrimary("才","干"));
        assertFalse(GameTerms.retainPrimary("ABC","amc"));
        assertFalse(GameTerms.retainPrimary("但是","但事"));
        assertFalse(GameTerms.retainPrimary("红石中继","红石"));
    }
    @Test void wholeMinecraftTermsNormalizeHomophonesWithoutPerNameAliases() {
        assertNotNull(decide("红十种记起","红十种记起","红石中继器",true).hit());
        assertNotNull(decide("莫隐之夜","莫影之眼","末影之眼",true).hit());
        assertEquals("红石中继器。",GameTerms.display("红十种记起","红十种记起。",true));
        assertEquals("末影之眼。",GameTerms.display("莫隐之夜","莫影之眼。",true));
        assertEquals("Please give me 红石中继器。",GameTerms.display("红十种记起","Please give me 红 十 种 记 起。",true));
    }
    @Test void oneAccentChangeNeedsConfirmationAndWholeWordAnchors() {
        assertNotNull(decide("下届之星","下界之心","下界之星",true).hit());
        assertNotNull(decide("僵尸朱林","僵尸朱林","僵尸猪灵",true).hit());
        assertNull(decide("僵尸朱林","僵尸朱林","僵尸猪灵",false).hit());
        assertNotNull(decide("流浪商人","牛浪商人","流浪商人",true).hit());
        assertNotNull(decide("深板岩砖","生板岩砖","深板岩砖",true).hit());
        assertNotNull(decide("荧光墨囊","荧光墨南","荧光墨囊",true).hit());
    }
    @Test void unrelatedSentencesCompetitorsShortWordsAndHighFrequencyStayProtected() {
        assertNull(decide("好吃的","how much the shirt is","吃的",true).hit());
        assertNull(decide("我今天很好","莫影之眼","末影之眼",true).hit());
        assertNull(decide("红石比较器","红石比较器","红石中继器",true).hit());
        assertNull(decide("红石比较器","红十种记起","红石中继器",true).hit());
        assertNull(decide("我要红石比较器。","我要红十种记起。","红石中继器",true).hit());
        assertNull(decide("海洋之心","海洋之心","下界之星",true).hit());
        assertNull(decide("才","赶","干",true).hit());
        var high=List.of(new WordMatcher.Word("末影之眼","highfreq",List.of()));
        assertNull(GameTerms.find(high,"莫影之眼","莫影之眼",true));
        var smart=List.of(new WordMatcher.Word("末影之眼","smart",List.of()));
        assertNull(GameTerms.find(smart,"莫影之眼","莫影之眼",true));
    }
    @Test void phoneticCorrectionDoesNotCompleteMissingOrDifferentSyllables() {
        assertNull(decide("红石","红石","红石中继器",true).hit());
        assertNull(decide("红石中继","红石中继","红石中继器",true).hit());
        assertNull(decide("末影之夜","末影之夜","末影之眼",true).hit());
    }
    @Test void confirmationRoundTripsWithoutDuplicatingTheOriginalSentence() {
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("僵尸朱林"),Set.of(),true));
        assertTrue(packet.reviewConfirmed());assertEquals(List.of("僵尸朱林"),packet.candidates());
        assertTrue(packet.literalCandidates().isEmpty());assertEquals("僵尸朱林",packet.finalText());
        assertFalse(SpeechPacket.parse(SpeechPacket.encode(List.of("僵尸朱林"))).reviewConfirmed());
        String wire=SpeechPacket.encode(List.of("僵尸朱林"),Set.of(),true);
        assertTrue(SpeechPacket.parse(wire.replace("C=1","C=2")).candidates().isEmpty());
        assertTrue(SpeechPacket.parse(wire.replace("C=1","C=1|C=1")).candidates().isEmpty());
    }
}
