package com.example.voicekill.client;

import com.google.gson.GsonBuilder;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Native integration check using unchanged continuous WAV; optional vocabulary churn. */
public final class NaturalBoundaryReplay {
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]);byte[] pcm;
        try(var in=AudioSystem.getAudioInputStream(file.toFile());var converted=AudioCapture.convert(in)){pcm=converted.readAllBytes();}
        var words=new AtomicReference<>(List.of("quartz"));
        var events=new ArrayList<Map<String,Object>>();
        try(var decoder=new NaturalSpeechDecoder(Path.of(".local/models/zipformer-bilingual-small"),Path.of(".local/models/sensevoice-int8/silero_vad.onnx"),1,words::get,new ZipformerDecoder.Settings(.6f,true,1.5f,10f))) {
            int previousRound=0;
            for(int offset=0;offset<pcm.length;offset+=1280) {
                int round=offset/(32000*4);
                if(round!=previousRound){words.set(List.of(round%2==0?"quartz":"xray"));previousRound=round;}
                var result=decoder.accept(Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+1280)));
                if(!result.isEmpty())events.add(Map.of("audioSeconds",(offset+1280)/32000.,"text",result,"epoch",decoder.epoch()));
            }
        }
        if(events.isEmpty()||((Number)events.getFirst().get("audioSeconds")).doubleValue()>11.5)
            throw new AssertionError("Continuous speech failed to settle under vocabulary churn: "+events);
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("source",file.toString(),"vocabularyChangesEverySeconds",4,"firstSettlementBudgetSeconds",11.5,"events",events)));
        System.out.println("NATIVE_WATCHDOG_PASS "+events.size());
    }
}
