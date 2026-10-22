package com.example.voicekill.speech;

import com.example.voicekill.config.ModConfig;
import com.example.voicekill.util.WordAliases;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighFrequencyRulesTest {
    @Test void highFrequencyChineseHasNoImplicitHomophonesButExplicitAliasesRemain() {
        var config=new ModConfig();
        var aliases=WordAliases.variants("但是",config,false);
        var words=List.of(new WordMatcher.Word("但是","highfreq",aliases));
        assertNotNull(WordMatcher.find(words,"但是",false,0));
        assertNull(WordMatcher.find(words,"但事",false,0));
        config.aliases.put("但是",List.of("但事"));
        var explicit=List.of(new WordMatcher.Word("但是","highfreq",WordAliases.variants("但是",config,false)));
        assertNotNull(WordMatcher.find(explicit,"但事",false,0));
    }
    @Test void frequentWordsCannotBeAssembledFromDifferentSentencesOrSecondarySingleCharacters() {
        var words=List.of(new WordMatcher.Word("但是","highfreq",List.of()));var window=new SpeechWindow();
        assertNull(WordMatcher.find(words,"但，是",false,0));
        var smart=List.of(new WordMatcher.Word("但是","smart",List.of()));
        var smartWindow=new SpeechWindow();assertNull(smartWindow.accept("p",SpeechPacket.parse("但"),smart,100).hit());
        assertNull(smartWindow.accept("p",SpeechPacket.parse("是"),smart,200).hit());
        assertNull(window.accept("p",SpeechPacket.parse("但"),words,100).hit());
        assertNull(window.accept("p",SpeechPacket.parse("是"),words,200).hit());
        assertNotNull(window.accept("p",SpeechPacket.parse("但是"),words,300).hit());
        assertNull(new SpeechWindow().accept("p",SpeechPacket.parse("才\u0001干"),List.of(new WordMatcher.Word("干","highfreq",List.of())),100).hit());
    }
    @Test void shortChineseIsCountedOnceAndCyclesPreserveAccurateTotals() {
        var stats=new SpeechStats();var known=Set.of("钻石");stats.record("钻石");
        assertEquals(1,stats.currentTop(5,known).getFirst().count());
        stats.record("钻石");assertEquals(2,stats.currentTop(5,known).getFirst().count());stats.finishCycle();
        assertTrue(stats.currentTop(5,known).isEmpty());assertEquals(2,stats.lastCycleTop(5,known).getFirst().count());
        assertEquals(2,stats.cumulativeTop(5,known).getFirst().count());
    }
    @Test void correctedRawChineseDoesNotPolluteFinalFrequencyCounts() {
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("好吃的","how much the shirt is"),Set.of(1)));
        var stats=new SpeechStats();stats.record(packet.finalText());
        assertTrue(stats.currentTop(20,Set.of()).stream().noneMatch(w->w.word().contains("好吃")));
        assertTrue(stats.currentTop(20,Set.of()).stream().anyMatch(w->w.word().equals("shirt")&&w.count()==1));
    }
}
