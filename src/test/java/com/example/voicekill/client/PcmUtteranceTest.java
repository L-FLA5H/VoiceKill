package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PcmUtteranceTest {
    @Test void boundedHistoryPreservesTheTailAndIdleLookahead() {
        var audio=new PcmUtterance(4);
        audio.append(new byte[]{1,0,2,0,3,0,4,0,5,0});
        assertArrayEquals(new float[]{2/32768f,3/32768f,4/32768f,5/32768f},audio.samples());
        audio.keepLast(2);
        assertEquals(2,audio.samples().length);
        audio.clear();assertEquals(0,audio.samples().length);
    }
    @Test void canonicalAudioChangesSilenceNotSpeechSamples() {
        float[] input=new float[1600];Arrays.fill(input,320,640,.2f);
        float[] result=PcmUtterance.canonical(input,16000);
        assertEquals(4000+320+16000,result.length);
        assertEquals(.2f,result[4000]);assertEquals(.2f,result[4319]);assertEquals(0,result[4320]);
        assertEquals(0,PcmUtterance.canonical(new float[1600],16000).length);
    }
}
