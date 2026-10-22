package com.example.voicekill.speech;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WordPriorityTest {
    private WordMatcher.Word word(String text,String source) {return new WordMatcher.Word(text,source,List.of());}
    @Test void explicitAliasBeatsInferredPronunciationEvenForAPreferredName() {
        var words=List.of(word("豆腐汤宝宝","name"),new WordMatcher.Word("频道","random",List.of("豆腐唐包包"),false));
        var hit=WordMatcher.find(words,"豆腐唐包包",false,0);
        assertEquals("频道",hit.word());assertEquals("alias",hit.mode());
    }
    @Test void completeNameBeatsAnOrdinarySubstringRegardlessOfListOrder() {
        var words=List.of(word("豆腐汤","random"),word("豆腐汤宝宝","name"));
        assertEquals("豆腐汤宝宝",WordMatcher.find(words,"豆腐汤宝宝来了",false,0).word());
    }
    @Test void manualWordsBeatOrdinaryWordsWithinTheExactEvidenceTier() {
        var words=List.of(word("我","highfreq"),word("p老板","manual"));
        assertEquals("p老板",WordMatcher.find(words,"我和p老板一起走",false,0).word());
        assertEquals("p老板",WordMatcher.find(List.of(word("p","manual"),word("p老板","name")),"p老板",false,0).word());
    }
    @Test void priorityCannotBeatStrongerEvidenceOrExpandAcceptance() {
        var words=List.of(word("abcde","manual"),word("钻石","random"));
        assertEquals("钻石",WordMatcher.find(words,"abcdx钻石",false,0).word());
        for(String input:List.of("今天很好","才","abcdx","钻石","我在这里")) {
            boolean any=words.stream().anyMatch(w->WordMatcher.find(List.of(w),input,false,0)!=null);
            assertEquals(any,WordMatcher.find(words,input,false,0)!=null,input);
        }
    }
    @Test void aLaterExactCandidateBeatsAnEarlierFuzzyName() {
        var words=List.of(word("abcde","name"),word("钻石","random"));
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("abcdx","钻石")));
        assertEquals("钻石",new SpeechWindow().accept("p",packet,words,100).hit().word());
    }
    @Test void audioReviewExactBeatsPrimaryFuzzyButKeepsItsRestrictions() {
        var words=List.of(word("abcde","name"),word("p老板","manual"));
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("abcdx老板","p老板"),Set.of(1)));
        assertEquals("p老板",new SpeechWindow().accept("p",packet,words,100).hit().word());
        assertNull(new SpeechWindow().accept("p",SpeechPacket.parse(SpeechPacket.encode(List.of("才","干"),Set.of(1))),List.of(word("干","manual")),100).hit());
    }
}
