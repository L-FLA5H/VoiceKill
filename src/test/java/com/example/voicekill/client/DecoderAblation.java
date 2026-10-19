package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import com.example.voicekill.speech.*;
import com.example.voicekill.util.WordAliases;
import com.google.gson.*;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.util.*;

/** 固定语料的解码侧消融；快速模式只比较识别，不用其墙钟时间声称触发延迟。 */
public final class DecoderAblation {
    static final List<String> TARGETS = List.of("qaq","p老板","豆腐汤宝宝","干","才");

    public static void main(String[] args) throws Exception {
        Path model = Path.of(args.length>3 ? args[3] : ".local/models/zipformer-bilingual-small");
        Path corpus = Path.of(args[0]);
        List<String> targets=args.length>4 && !args[4].isBlank() ? List.of(args[4].split(",")) : TARGETS;
        Path output = Path.of(args[1]).toAbsolutePath().getParent();
        Files.createDirectories(output);
        Path vocabulary = output.resolve("hotwords.txt");
        Files.writeString(vocabulary,"QAQ\nQ A Q\n扣哎扣\nP老板\n");
        var manifest = JsonParser.parseString(Files.readString(corpus.resolve("manifest.json"))).getAsJsonObject();
        List<Map<String,Object>> rows = new ArrayList<>();
        boolean letterStage=args[2].equals("letters");
        List<String> profiles=letterStage ? List.of("dev3", "letters", "letters8")
                : args[2].equals("latency") ? List.of("fast0.6","fast0.45","fast0.35","fast0.25")
                : args[2].equals("adaptive") ? List.of("adaptive1.0","adaptive1.2")
                : List.of("greedy", "beam", "hotwords", "phonetic", "phonetic2.5");
        for (String profile : profiles) {
            boolean adaptive=profile.startsWith("adaptive") || letterStage;
            boolean fast=profile.startsWith("fast") || adaptive;
            Set<String> terms = new LinkedHashSet<>(List.of("QAQ", "Q A Q", "扣哎扣", "P老板"));
            if (profile.startsWith("phonetic") || fast) {
                terms.addAll(WordAliases.variants("qaq", null));
                terms.addAll(WordAliases.variants("p老板", null));
            }
            if(letterStage) {
                terms.clear();
                if(profile.equals("dev3")) terms.addAll(Arrays.asList(RecognitionHints.encode(targets).split("/")));
                else for(String name:RecognitionHints.names(targets)) {
                    terms.add(name.toUpperCase(Locale.ROOT));
                    StringBuilder spelled=new StringBuilder();
                    for(int i=0;i<name.length();i++) {
                        char c=name.charAt(i);
                        if(i>0 && (c<128 || name.charAt(i-1)<128)) spelled.append(' ');
                        spelled.append(Character.toUpperCase(c));
                    }
                    terms.add(spelled.toString());
                }
            }
            Files.writeString(vocabulary, String.join("\n",terms));
            var transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(model.resolve("encoder.int8.onnx").toString())
                    .setDecoder(model.resolve("decoder.onnx").toString())
                    .setJoiner(model.resolve("joiner.int8.onnx").toString()).build();
            var config = OnlineModelConfig.builder().setTransducer(transducer)
                    .setTokens(model.resolve("tokens.txt").toString()).setNumThreads(1).setDebug(false)
                    .setModelingUnit("cjkchar+bpe").setBpeVocab(model.resolve("bpe.vocab").toString()).build();
            var endpoint = EndpointConfig.builder()
                    .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(2.4f).build())
                    .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(profile.startsWith("fast") ? Float.parseFloat(profile.substring(4)) : .6f).build())
                    .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinUtteranceLength(15).build()).build();
            if(adaptive) endpoint=EndpointConfig.builder()
                    .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinUtteranceLength(15).build())
                    .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(.35f)
                            .setMinUtteranceLength(letterStage ? 1.2f : Float.parseFloat(profile.substring(8))).build())
                    .setRule3(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(.6f).build()).build();
            var builder = OnlineRecognizerConfig.builder().setOnlineModelConfig(config).setEnableEndpoint(true)
                    .setEndpointConfig(endpoint).setDecodingMethod(profile.equals("greedy") ? "greedy_search" : "modified_beam_search")
                    .setMaxActivePaths(profile.equals("letters8") ? 8 : 4);
            if (profile.equals("hotwords") || profile.startsWith("phonetic") || fast) builder.setHotwordsFile(vocabulary.toString()).setHotwordsScore(profile.endsWith("2.5") ? 2.5f : 1.5f);
            var recognizer = new OnlineRecognizer(builder.build());
            try {
                for (var element : manifest.getAsJsonArray("items")) {
                    var clip=element.getAsJsonObject();
                    String id=clip.get("id").getAsString();
                    String target=clip.has("target") && !clip.get("target").isJsonNull() ? clip.get("target").getAsString() : null;
                    byte[] pcm;
                    try (var raw=AudioSystem.getAudioInputStream(corpus.resolve(clip.get("file").getAsString()).toFile());
                         var converted=AudioCapture.convert(raw)) { pcm=converted.readAllBytes(); }
                    var stream=recognizer.createStream();
                    Map<String,SpeechWindow> windows=new LinkedHashMap<>();
                    Map<String,List<WordMatcher.Word>> words=new LinkedHashMap<>();
                    for(String word:targets) {
                        windows.put(word,new SpeechWindow());
                        words.put(word,List.of(new WordMatcher.Word(word,"manual",WordAliases.variants(word,null))));
                    }
                    Map<String,Object> hits=new LinkedHashMap<>();
                    Map<String,Double> delays=new LinkedHashMap<>();
                    List<String> texts=new ArrayList<>();
                    long start=System.nanoTime();
                    try {
                        int frame=fast ? 1280 : 4096;
                        for(int offset=0;offset<pcm.length;offset+=frame) {
                            int end=Math.min(pcm.length,offset+frame);
                            stream.acceptWaveform(ZipformerDecoder.pcmSamples(Arrays.copyOfRange(pcm,offset,end)),16000);
                            while(recognizer.isReady(stream)) recognizer.decode(stream);
                            if(!recognizer.isEndpoint(stream)) continue;
                            String text=recognizer.getResult(stream).getText().trim();
                            if (fast) {
                                stream.release(); stream=recognizer.createStream();
                            } else recognizer.reset(stream);
                            if(text.isEmpty()) continue;
                            texts.add(text);
                            for(String word:targets) {
                                var decision=windows.get(word).accept("probe",SpeechPacket.parse(text),words.get(word),end/32);
                                if(decision.hit()!=null) {
                                    hits.putIfAbsent(word,decision.hit());
                                    delays.putIfAbsent(word,end/32.0 - clip.get("speechEndSample").getAsLong()/16.0);
                                }
                            }
                        }
                    } finally {stream.release();}
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("profile",profile);row.put("id",id);row.put("target",target);
                    row.put("texts",texts);row.put("hits",hits);row.put("hit",hits.containsKey(target));
                    row.put("literalTarget", target!=null && texts.stream().anyMatch(t ->
                            com.example.voicekill.util.TextUtil.normalize(t).contains(com.example.voicekill.util.TextUtil.normalize(target))));
                    row.put("audioDelayMs",delays);
                    row.put("falseKills",hits.keySet().stream().filter(w->!w.equals(target)).count());
                    row.put("decodeMs",(System.nanoTime()-start)/1e6);rows.add(row);
                }
            } finally {recognizer.release();}
            System.out.println("PROBE_COMPLETED="+profile);
        }
        Files.writeString(Path.of(args[1]),new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(rows));
    }
}
