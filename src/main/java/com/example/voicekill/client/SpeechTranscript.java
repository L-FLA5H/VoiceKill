package com.example.voicekill.client;

/** 本机显示状态；临时识别不进入网络或处罚路径。 */
public final class SpeechTranscript {
    public record View(String text,boolean interim,long updatedNanos) {}
    private static volatile View latest=new View("",false,0);
    private SpeechTranscript() {}
    public static void update(CaptureSession.Transcript value) {
        if(value.text().isBlank()) {
            if(latest.interim())clear();
        } else latest=new View(value.text(),value.interim(),System.nanoTime());
    }
    public static View view(){return latest;}
    public static void clear(){latest=new View("",false,0);}
}
