package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import com.example.voicekill.speech.WordMatcher;
import com.example.voicekill.util.WordAliases;
import com.google.gson.*;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.util.*;

/** 离线识别能力对照，不将整段回放耗时当作流式触发延迟。 */
public final class SenseVoiceProbe {
    public static void main(String[] args) throws Exception {
        var model=OfflineModelConfig.builder().setTokens(".local/models/sensevoice-int8/tokens.txt")
                .setSenseVoice(OfflineSenseVoiceModelConfig.builder().setModel(".local/models/sensevoice-int8/model.int8.onnx")
                        .setLanguage("auto").setInverseTextNormalization(false).build())
                .setNumThreads(1).setDebug(false).build();
        var recognizer=new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build());
        List<Map<String,Object>> rows=new ArrayList<>();
        List<String> targets=args.length>2 && !args[2].isBlank() ? List.of(args[2].split(",")) : DecoderAblation.TARGETS;
        try {
            for(String folder:args.length>0 ? List.of(args[0]) : List.of(".local/diagnostics/nickname-replay/corpus",".local/diagnostics/nickname-replay/negative-controls")) {
                Path root=Path.of(folder);
                var manifest=JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
                for(var element:manifest.getAsJsonArray("items")) {
                    var clip=element.getAsJsonObject();
                    String target=clip.has("target")&&!clip.get("target").isJsonNull()?clip.get("target").getAsString():null;
                    byte[] pcm;
                    try(var raw=AudioSystem.getAudioInputStream(root.resolve(clip.get("file").getAsString()).toFile());
                        var converted=AudioCapture.convert(raw)) {pcm=converted.readAllBytes();}
                    var stream=recognizer.createStream();
                    long start=System.nanoTime();String text;
                    try {
                        stream.acceptWaveform(ZipformerDecoder.pcmSamples(pcm),16000);
                        recognizer.decode(stream);text=recognizer.getResult(stream).getText().trim();
                    } finally {stream.release();}
                    Map<String,Object> hits=new LinkedHashMap<>();
                    for(String word:targets) {
                        var hit=WordMatcher.find(List.of(new WordMatcher.Word(word,"manual",WordAliases.variants(word,null))),text,false,0);
                        if(hit!=null)hits.put(word,hit);
                    }
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("folder",folder);row.put("id",clip.get("id").getAsString());row.put("target",target);row.put("text",text);
                    row.put("hits",hits);row.put("hit",hits.containsKey(target));
                    row.put("literalTarget",target!=null && com.example.voicekill.util.TextUtil.normalize(text).contains(com.example.voicekill.util.TextUtil.normalize(target)));
                    row.put("falseKills",hits.keySet().stream().filter(word->!word.equals(target)).count());
                    row.put("decodeMs",(System.nanoTime()-start)/1e6);rows.add(row);
                }
            }
        } finally {recognizer.release();}
        Files.writeString(Path.of(args.length>1 ? args[1] : ".local/diagnostics/nickname-replay/optimization/sensevoice-probe.json"),
                new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(rows));
        System.out.println("SENSEVOICE_PROBE_COMPLETED");
    }
}
