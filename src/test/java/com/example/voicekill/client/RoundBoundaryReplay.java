package com.example.voicekill.client;

import com.google.gson.GsonBuilder;
import com.example.voicekill.speech.SpeechPacket.RoundSpan;
import com.example.voicekill.speech.SpeechPacket;
import com.example.voicekill.speech.RoundSpeechWindow;
import com.example.voicekill.speech.WordMatcher;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Same WAV, native Zipformer+Silero: a rotation must neither truncate nor delay it. */
public final class RoundBoundaryReplay {
    private record Event(double audioSeconds,List<String> text,int firstRound,int lastRound,long epoch,String matched) {}
    private static List<WordMatcher.Word> words(int round){return List.of(new WordMatcher.Word(List.of("豆腐汤宝宝","门口","设置").get(round%3),"manual",List.of()));}
    private static List<Event> replay(byte[] pcm,boolean rotate) throws Exception {
        var server=new RoundSpeechWindow();String session=UUID.randomUUID().toString();var ids=new ArrayList<String>();ids.add(server.rotate(words(0),0));
        var context=new AtomicReference<>(new RoundTrackingDecoder.Context(session,ids.getFirst(),true));
        var words=new AtomicReference<>(words(0).stream().map(WordMatcher.Word::text).toList());var events=new ArrayList<Event>();int round=0;
        try(var decoder=new RoundTrackingDecoder(new NaturalSpeechDecoder(Path.of(".local/models/zipformer-bilingual-small"),Path.of(".local/models/sensevoice-int8/silero_vad.onnx"),1,words::get,new ZipformerDecoder.Settings(.6f,true,1.5f,10f)),context::get)) {
            for(int offset=0;offset<pcm.length;offset+=1280) {
                int next=rotate?offset/(32000*4):0;
                if(next!=round){round=next;ids.add(server.rotate(words(round),offset/32));context.set(new RoundTrackingDecoder.Context(session,ids.getLast(),true));words.set(words(round).stream().map(WordMatcher.Word::text).toList());}
                var result=decoder.accept(Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+1280)));
                if(!result.isEmpty()){
                    RoundSpan span=decoder.rounds();
                    var decision=server.accept("p",SpeechPacket.parse(SpeechPacket.encode(result,Set.of(),false,span)),(offset+1280)/32);
                    events.add(new Event(Math.min(pcm.length,offset+1280)/32000.,result,ids.indexOf(span.first()),ids.indexOf(span.last()),decoder.epoch(),decision.hit()==null?"":decision.hit().word()));
                }
            }
        }
        return events;
    }
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]);byte[] pcm;
        try(var source=AudioSystem.getAudioInputStream(file.toFile());var converted=AudioCapture.convert(source)){pcm=converted.readAllBytes();}
        var control=replay(pcm,false);var changed=replay(pcm,true);
        if(control.isEmpty()||changed.isEmpty())throw new AssertionError("No complete utterance");
        var a=control.getFirst();var b=changed.getFirst();
        if(a.audioSeconds()!=b.audioSeconds()||!a.text().equals(b.text())||b.audioSeconds()>11.5||b.firstRound()!=0||b.lastRound()<2||b.epoch()!=0||!a.matched().equals("豆腐汤宝宝")||!b.matched().equals("设置"))throw new AssertionError("Round change truncated/delayed/reset the sentence or wrong round won: "+a+" / "+b);
        var report=Map.of("source",file.toString(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),"rotationSeconds",4,"control",control,"changed",changed,"note","accelerated native integration; input-audio timing, not live latency");
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("ROUND_BOUNDARY_PASS first="+b.audioSeconds()+" rounds="+b.firstRound()+".."+b.lastRound());
    }
}
