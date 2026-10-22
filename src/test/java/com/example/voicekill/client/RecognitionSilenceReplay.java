package com.example.voicekill.client;

import com.example.voicekill.speech.RecognitionSilence;
import com.google.gson.GsonBuilder;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual model partials keep a 5-second silence deadline alive across 10-second segments. */
public final class RecognitionSilenceReplay {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]);byte[] original;
        try(var in=AudioSystem.getAudioInputStream(source.toFile());var converted=AudioCapture.convert(in)){original=converted.readAllBytes();}
        byte[] pcm=Arrays.copyOf(original,original.length+8*32000);
        var activity=new RecognitionActivity();var timer=new RecognitionSilence();timer.configure(new RecognitionSilence.Rules(true,5,2,1));
        var notices=new ArrayList<Double>();var damages=new ArrayList<Double>();var finals=new ArrayList<Double>();long lastTick=-1;Double firstPartial=null;
        try(var decoder=new NaturalSpeechDecoder(Path.of(".local/models/zipformer-bilingual-small"),Path.of(".local/models/sensevoice-int8/silero_vad.onnx"),1,List::of,new ZipformerDecoder.Settings(.6f,true,1.5f,10f))) {
            for(int offset=0;offset<pcm.length;offset+=1280) {
                int end=Math.min(pcm.length,offset+1280);var result=decoder.accept(Arrays.copyOfRange(pcm,offset,end));
                boolean interim=result.isEmpty();String text=interim?decoder.partial():result.getFirst();long ms=end/32,tick=ms/50;
                if(interim&&RecognitionSilence.hasText(text)&&firstPartial==null)firstPartial=ms/1000.;
                if(!interim)finals.add(ms/1000.);
                if(activity.observe("session",text,interim,ms)){timer.recognized("p",tick);notices.add(ms/1000.);}
                if(tick!=lastTick&&timer.step("p",tick,true))damages.add(ms/1000.);lastTick=tick;
            }
        }
        if(firstPartial==null||finals.isEmpty()||firstPartial>=finals.getFirst()||damages.isEmpty()||damages.getFirst()<original.length/32000.)throw new AssertionError("Missing real partials, or damaged continuous speech: "+damages);
        var report=Map.of("source",source.toString(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))),"timeoutSeconds",5,"intervalSeconds",1,"addedSilenceSeconds",8,"firstPartialSeconds",firstPartial,"finalSeconds",finals,"activitySeconds",notices,"damageSeconds",damages,"note","accelerated native replay; audio time, no real player damage");
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SILENCE_REPLAY_PASS partial="+firstPartial+" firstFinal="+finals.getFirst()+" firstDamage="+damages.getFirst());
    }
}
