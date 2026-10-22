package com.example.voicekill.speech;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PhoneticMatcherTest {
    @BeforeAll static void ready(){GameTerms.awaitReady();}
    private List<WordMatcher.Word> words(String... values){return Arrays.stream(values).map(v->new WordMatcher.Word(v,"random",List.of())).toList();}
    private RoundSpeechWindow.Result judge(String raw,String reviewed,String target) {
        var window=new RoundSpeechWindow();String round=window.rotate(words(target),0);
        var packet=SpeechPacket.parse(SpeechPacket.encode(raw.equals(reviewed)?List.of(raw):List.of(raw,reviewed),raw.equals(reviewed)?Set.of():Set.of(1),true,new SpeechPacket.RoundSpan(round,round)));
        return window.accept("p",packet,10,PhoneticMatcher.Settings.defaults());
    }
    @Test void actualLoggedFinalSentencesAreJudgedWithoutTheWrongPrimaryVeto() {
        assertEquals("天哪",judge("天呐","天哪","天哪").hit().word());
        assertEquals("海归",judge("艾硅","海归","海归").hit().word());
        assertEquals("镐子",judge("稿子","稿子","镐子").hit().word());
        assertEquals("镐子",judge("稿子","老子","镐子").hit().word());
        assertEquals("铁锭",judge("写的","铁定","铁锭").hit().word());
        assertEquals("青金石",judge("新京时","清津石","青金石").hit().word());
        assertEquals("下界之星",judge("下界之心","下界之心","下界之星").hit().word());
        assertEquals("僵尸猪灵",judge("僵尸朱林","僵尸朱林","僵尸猪灵").hit().word());
        assertEquals("末影之眼",judge("莫影之眼","莫影之眼","末影之眼").hit().word());
        assertEquals("红石中继器",judge("红十种记起","红十种记起","红石中继器").hit().word());
        assertEquals("干",judge("才","干","干").hit().word());
    }
    @Test void commonAccentPairsHaveLowDistanceWithoutPerWordAliases() {
        var tight=new PhoneticMatcher.Settings(true,.15f);
        for(String[] pair:List.of(new String[]{"牛奶","刘来"},new String[]{"发火","花火"},new String[]{"金星","津心"},new String[]{"森林","僧林"},new String[]{"知道","资道"}))
            assertNotNull(PhoneticMatcher.find(words(pair[0]),pair[1],tight),Arrays.toString(pair));
    }
    @Test void literalHomophonesWinAndTheMostSimilarPronunciationWins() {
        assertEquals("海归",PhoneticMatcher.find(words("海龟","海归"),"海归",PhoneticMatcher.Settings.defaults()).word());
        assertEquals("海归",PhoneticMatcher.find(words("海归","牛奶"),"艾硅",PhoneticMatcher.Settings.defaults()).word());
        assertNull(PhoneticMatcher.find(words("镐子"),"how much the shirt is",PhoneticMatcher.Settings.defaults()));
    }
    @Test void sensitivityAndSwitchAreRealControlsRatherThanHiddenSafetyExceptions() {
        assertEquals(.30f,PhoneticMatcher.Settings.defaults().threshold());
        assertEquals(.30f,new PhoneticMatcher.Settings(true,Float.NaN).threshold());
        assertNull(PhoneticMatcher.find(words("镐子"),"老子",new PhoneticMatcher.Settings(true,.05f)));
        assertNotNull(PhoneticMatcher.find(words("镐子"),"老子",new PhoneticMatcher.Settings(true,.35f)));
        assertNull(PhoneticMatcher.find(words("镐子"),"稿子",new PhoneticMatcher.Settings(false,.35f)));
        var window=new RoundSpeechWindow();window.rotate(words("海归"),0);
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("艾硅","海归"),Set.of(1),true));
        assertNotNull(window.accept("p",packet,10,new PhoneticMatcher.Settings(false,.35f)).hit());
    }
    @Test void allWordSourcesUseTheSamePronunciationRulesAndOffsetsStayInTheSentence() {
        for(String source:List.of("random","manual","name","highfreq","smart")) {
            var hit=PhoneticMatcher.find(List.of(new WordMatcher.Word("铁锭",source,List.of())),"我拿了铁定。",PhoneticMatcher.Settings.defaults());
            assertEquals("铁定",hit.evidence());assertEquals(3,hit.start());assertEquals(5,hit.end());
        }
    }
    @Test void newerOverlappingRoundWinsWithFuzzyMatchingToo() {
        var window=new RoundSpeechWindow();String a=window.rotate(words("海归"),0),b=window.rotate(words("镐子"),100);
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("海归和稿子"),Set.of(),false,new SpeechPacket.RoundSpan(a,b)));
        assertEquals("镐子",window.accept("p",packet,110,PhoneticMatcher.Settings.defaults()).hit().word());
    }
    @Test void finalSentenceHistoryKeepsEventAndPlayerIsolationAndWithdrawsRawText() {
        var window=new SpeechWindow();var settings=PhoneticMatcher.Settings.defaults();
        var names=words("豆腐汤宝宝");
        var revised=SpeechPacket.parse(SpeechPacket.encode(List.of("豆腐汤","今天天气"),Set.of(1),true));
        assertNull(window.acceptFinal("a",revised,names,100,settings).hit());
        assertNull(window.acceptFinal("a",SpeechPacket.parse("宝宝"),names,200,settings).hit());
        window.clear();
        assertNull(window.acceptFinal("a",SpeechPacket.parse("豆腐汤"),names,300,settings).hit());
        assertNull(window.acceptFinal("b",SpeechPacket.parse("宝宝"),names,400,settings).hit());
        assertNotNull(window.acceptFinal("a",SpeechPacket.parse("宝宝"),names,400,settings).hit());
        window.clear();
        var packet=SpeechPacket.parse(SpeechPacket.encode(List.of("铁定")));
        assertNotNull(window.acceptFinal("a",packet,words("铁锭"),500,settings).hit());
        assertTrue(window.acceptFinal("a",packet,words("铁锭"),600,settings).duplicate());
        assertFalse(window.acceptFinal("a",SpeechPacket.parse(SpeechPacket.encode(List.of("铁定"))),words("铁锭"),700,settings).duplicate());
    }
    @Test void unrelatedInitialsAreNotTheSameAsCommonAccentConfusions() {
        var settings=PhoneticMatcher.Settings.defaults();
        assertNull(PhoneticMatcher.find(words("干"),"老板",settings));
        assertNotNull(PhoneticMatcher.find(words("干"),"刚才",settings)); // gan/gang: requested nasal confusion.
        assertNull(PhoneticMatcher.find(words("干"),"才",settings));
        assertNotNull(PhoneticMatcher.find(words("才"),"菜市场",settings));
    }
}
