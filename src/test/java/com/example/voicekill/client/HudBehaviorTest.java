package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HudBehaviorTest {
    @Test void mixedLanguageHighlightPreservesSpacesCaseAndUnicode() {
        String text="🎤 我和 P 老板 测试 Q A Q，然后才回家。";
        var runs=SpeechHighlight.runs(text,List.of("p老板","qaq","干"));
        assertEquals(text,runs.stream().map(SpeechHighlight.Run::text).reduce("",String::concat));
        assertEquals(List.of("P 老板","Q A Q"),runs.stream().filter(SpeechHighlight.Run::danger).map(SpeechHighlight.Run::text).toList());
        assertTrue(SpeechHighlight.runs("才",List.of("干")).stream().noneMatch(SpeechHighlight.Run::danger));
        assertTrue(SpeechHighlight.runs("扣哎扣",List.of("qaq")).stream().noneMatch(SpeechHighlight.Run::danger));
    }
    @Test void overlappingNamesAreColoredOnceWithoutDroppingCharacters() {
        var runs=SpeechHighlight.runs("P老板和P老板",List.of("老板","p老板"));
        assertEquals(List.of("P老板","P老板"),runs.stream().filter(SpeechHighlight.Run::danger).map(SpeechHighlight.Run::text).toList());
    }
    @Test void boardStaysInsideTheScreenAcrossGuiScales() {
        for(int[] size:List.of(new int[]{320,240},new int[]{854,480},new int[]{1920,1080},new int[]{80,60}))
            for(float x:new float[]{0,.5f,1,Float.NaN})for(float y:new float[]{0,.5f,1,Float.NaN}) {
                var r=HudPlacement.board(size[0],size[1],220,100,x,y);
                assertTrue(r.x()>=0&&r.y()>=0);
                assertTrue(r.x()+r.width()<=size[0]&&r.y()+r.height()<=size[1]);
            }
    }
    @Test void boardCanAvoidTopPanelsWithoutLeavingViewport() {
        var r=HudPlacement.board(640,360,100,70,1,0);
        var moved=HudPlacement.below(r,34,360);
        assertEquals(34,moved.y());assertEquals(r.x(),moved.x());
        assertTrue(HudPlacement.below(r,400,360).y()+r.height()<=354);
    }
    @Test void savingClientDraftCannotOverwriteWordsOrNewlyChosenDevice() {
        var live=new ModConfig();live.wordsPerRound=20;live.dangerWords=List.of("保留词库");live.audioDevice="new microphone";live.deathMessages=List.of("server template");live.customDeathMessages=false;live.deathMessageStyle="classic";
        var draft=ClientSettings.draft(live);draft.wordsPerRound=1;draft.dangerWords=List.of("must not overwrite");draft.audioDevice="stale microphone";draft.deathMessages=List.of("stale template");draft.customDeathMessages=true;draft.deathMessageStyle="friends";
        draft.hudBoardX=.25f;draft.hudTranscript=false;
        live.silencePenaltyEnabled=true;live.silenceTimeoutSeconds=45;live.silenceDamageAmount=3;live.silenceDamageIntervalSeconds=7;
        draft.silencePenaltyEnabled=false;draft.silenceTimeoutSeconds=5;draft.silenceDamageAmount=20;draft.silenceDamageIntervalSeconds=.5f;
        live.phoneticMatching=false;live.phoneticThreshold=.15f;draft.phoneticMatching=true;draft.phoneticThreshold=.65f;
        assertEquals(1f,live.hudBoardX);
        ClientSettings.apply(draft,live);
        assertEquals(.25f,live.hudBoardX);assertFalse(live.hudTranscript);
        assertEquals(20,live.wordsPerRound);assertEquals(List.of("保留词库"),live.dangerWords);assertEquals("new microphone",live.audioDevice);
        assertEquals(List.of("server template"),live.deathMessages);assertFalse(live.customDeathMessages);assertEquals("classic",live.deathMessageStyle);
        assertTrue(live.silencePenaltyEnabled);assertEquals(45,live.silenceTimeoutSeconds);assertEquals(3,live.silenceDamageAmount);assertEquals(7,live.silenceDamageIntervalSeconds);
        assertFalse(live.phoneticMatching);assertEquals(.15f,live.phoneticThreshold);
    }
}
