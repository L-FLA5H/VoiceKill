package com.example.voicekill.client;

import com.example.voicekill.speech.SpeechPacket;
import com.example.voicekill.speech.SpeechWindow;
import com.example.voicekill.speech.WordMatcher;
import com.example.voicekill.speech.PhoneticMatcher;
import com.example.voicekill.util.WordAliases;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** 真实时间节奏的文件源 → 实际 CaptureSession/Decoder → 实际 SpeechWindow 匹配。 */
public final class NicknameReplay {
    private static SentencePostprocessor.Reviewer sharedReviewer;
    private static SentencePostprocessor.Punctuator sharedPunctuator;
    private static ZipformerDecoder.Settings replaySettings = ZipformerDecoder.Settings.defaults();
    private static List<WordMatcher.Word> evaluationWords=List.of();
    private static final List<String> TARGETS = List.of("qaq", "p老板", "豆腐汤宝宝", "干", "才");
    record Clip(String id, String target, String spokenText, String voice, String kind, String file,
                long speechStartSample, long speechEndSample, double durationSeconds, String sha256,List<String> expectedWords) {
        List<String> expected(){return expectedWords!=null?List.copyOf(expectedWords):target==null?List.of():List.of(target);}
    }
    record Corpus(String source, int sampleRate, String endpointAnnotation, List<Clip> items) {}

    private static final class PacedSource implements CaptureSession.Source {
        final byte[] pcm;
        volatile long startNanos;
        volatile int deliveredBytes;
        volatile boolean closed;
        final boolean realtime;
        PacedSource(byte[] pcm, boolean realtime) { this.pcm = pcm; this.realtime = realtime; }
        @Override public int read(byte[] bytes) throws InterruptedException {
            if (closed || deliveredBytes == pcm.length) return -1;
            int count = Math.min(bytes.length, pcm.length - deliveredBytes);
            long due = startNanos + (deliveredBytes + count) * 1_000_000_000L / 32000;
            while (realtime && !closed && System.nanoTime() < due) {
                LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(10), due - System.nanoTime()));
                if (Thread.interrupted()) throw new InterruptedException();
            }
            if (closed) return -1;
            System.arraycopy(pcm, deliveredBytes, bytes, 0, count);
            deliveredBytes += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    public static void main(String[] args) throws Exception {
        com.example.voicekill.speech.GameTerms.awaitReady();
        String engine=args.length>4 ? args[4] : "zipformer";
        List<String> targets=args.length>5 && !args[5].isBlank() ? List.of(args[5].split(",")) : TARGETS;
        boolean realtime=args.length<7 || Boolean.parseBoolean(args[6]);
        replaySettings = new ZipformerDecoder.Settings(args.length>9 ? Float.parseFloat(args[9]) : .35f,
                true, 1.5f, args.length>8 ? Float.parseFloat(args[8]) : 5f);
        if(args.length>10&&!args[10].isBlank()) {
            var entries=new Gson().fromJson(Files.readString(Path.of(args[10])),WordMatcher.Word[].class);
            evaluationWords=java.util.Arrays.stream(entries).map(w->new WordMatcher.Word(w.text(),w.source(),
                    w.aliases()==null?WordAliases.variants(w.text(),null):w.aliases(),w.preferred()||"manual".equals(w.source())||"name".equals(w.source()))).toList();
            targets=evaluationWords.stream().map(WordMatcher.Word::text).toList();
        } else evaluationWords=targets.stream().map(t->new WordMatcher.Word(t,"manual",WordAliases.variants(t,null))).toList();
        Path model = Path.of(args[0]);
        if (engine.equals("sensevoice")) {
            try (var resource=NicknameReplay.class.getResourceAsStream("/voicekill/asr/sensevoice-int8.json")) {
                if(resource==null) throw new IllegalStateException("缺少实验模型清单");
                var manifest=new Gson().fromJson(new java.io.InputStreamReader(resource,java.nio.charset.StandardCharsets.UTF_8),SpeechModels.Manifest.class);
                for(var asset:manifest.files()) SpeechModels.validate(model.resolve(asset.name()),asset);
            }
        } else if (!engine.equals("vosk")) SpeechModels.prepare(model,false);
        if(engine.equals("enhanced")||(engine.equals("natural")||engine.equals("priority"))) {
            sharedReviewer=NativeSentenceModels.reviewer(SpeechModels.prepare(Path.of(".local/models/sensevoice-int8"),false,"sensevoice-review-int8"),1);
            sharedPunctuator=NativeSentenceModels.punctuator(SpeechModels.prepare(Path.of(".local/models/punctuation-int8"),false,"punctuation-zh-en-int8"),1);
        }
        try {
        Path corpusDirectory = Path.of(args[1]);
        Corpus corpus = new Gson().fromJson(Files.readString(corpusDirectory.resolve("manifest.json")), Corpus.class);
        if(args.length>7 && !args[7].isBlank()) {
            var selected=List.of(args[7].split(","));
            corpus=new Corpus(corpus.source(),corpus.sampleRate(),corpus.endpointAnnotation(),corpus.items().stream().filter(c -> selected.contains(c.id())).toList());
            if(corpus.items().isEmpty()) throw new IllegalArgumentException("所选样本不存在");
        }
        int rounds = Integer.parseInt(args[2]);
        Path output = Path.of(args[3]);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (Clip clip : corpus.items()) {
                Map<String, Object> row = replay(model, corpusDirectory, clip, round, engine, targets, realtime);
                rows.add(row);
                save(output, corpus, rows, engine, targets, realtime);
                System.out.println("REPLAY " + rows.size() + "/" + (rounds * corpus.items().size()) + " "
                        + clip.id() + " expectedHit=" + row.get("targetHit") + " falseKills=" + row.get("falseKillCount"));
            }
        }
        System.out.println("REPLAY_RESULT=" + output.toAbsolutePath());
        } finally {if(sharedReviewer!=null)sharedReviewer.close();if(sharedPunctuator!=null)sharedPunctuator.close();}
    }

    private static Map<String, Object> replay(Path model, Path directory, Clip clip, int round, String engine, List<String> targets, boolean realtime) throws Exception {
        Path file = directory.resolve(clip.file());
        SpeechModels.validate(file, new SpeechModels.Asset(clip.file(), "", Files.size(file), clip.sha256()));
        byte[] pcm;
        try (var input = AudioSystem.getAudioInputStream(file.toFile()); var converted = AudioCapture.convert(input)) {
            pcm = converted.readAllBytes();
        }
        PacedSource source = new PacedSource(pcm, realtime);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        long initStart = System.nanoTime();
        CaptureSession.Decoder nativeDecoder = engine.equals("vosk") ? new MicCapture.VoskDecoder(model, replaySettings.maxUtteranceSeconds())
                : engine.equals("sensevoice") ? new SenseVoiceDecoder(model,1,.35f)
                : (engine.equals("natural")||engine.equals("priority")) ? new NaturalSpeechDecoder(model,Path.of(".local/models/sensevoice-int8/silero_vad.onnx"),1,()->targets,replaySettings,engine.equals("priority")?()->RecognitionHints.encodePriority(evaluationWords):null)
                : new ZipformerDecoder(model, 1, () -> targets, replaySettings);
        nativeDecoder=new RoundTrackingDecoder(nativeDecoder,RoundTrackingDecoder.Context::empty);
        if(engine.equals("enhanced")||(engine.equals("natural")||engine.equals("priority"))) {
            var post=new SentencePostprocessor(new SentencePostprocessor.Reviewer() {
                public String recognize(float[] audio) throws Exception{return sharedReviewer.recognize(audio);}
                public void close(){}
            },new SentencePostprocessor.Punctuator() {
                public String punctuate(String text) throws Exception{return sharedPunctuator.punctuate(text);}
                public void close(){}
            });
            nativeDecoder=new EnhancedDecoder(nativeDecoder,post,replaySettings.maxUtteranceSeconds());
        }
        final CaptureSession.Decoder activeDecoder=nativeDecoder;
        double initMs = (System.nanoTime() - initStart) / 1e6;
        CaptureSession.Decoder decoder = new CaptureSession.Decoder() {
            @Override public List<String> accept(byte[] bytes) throws Exception { return activeDecoder.accept(bytes); }
            @Override public String partial(){return activeDecoder.partial();}
            @Override public String displayFinal(List<String> candidates){return activeDecoder.displayFinal(candidates);}
            @Override public void close() { try { activeDecoder.close(); } finally { released.countDown(); } }
        };
        Map<String, SpeechWindow> windows = new LinkedHashMap<>();
        Map<String, List<WordMatcher.Word>> configured = new LinkedHashMap<>();
        Map<String, Object> hits = new LinkedHashMap<>();
        for (String target : targets) {
            windows.put(target, new SpeechWindow());
            configured.put(target, evaluationWords.stream().filter(w->w.text().equals(target)).toList());
        }
        List<Map<String, Object>> events = new ArrayList<>();
        var firstPartial=new AtomicReference<Map<String,Object>>();
        SpeechWindow allWords=new SpeechWindow();
        var combined=new AtomicReference<String>();
        CaptureSession session = new CaptureSession(source, decoder, candidates -> {
            double wallMs = (System.nanoTime() - source.startNanos) / 1e6;
            double audioMs = source.deliveredBytes / 32.0;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("candidates", candidates); event.put("wallMs", wallMs); event.put("audioMs", audioMs);
            event.put("afterSpeechEndMs", wallMs - clip.speechEndSample() / 16.0); events.add(event);
            event.put("reviewConfirmed",activeDecoder.reviewConfirmed());
            if(activeDecoder instanceof EnhancedDecoder enhanced)event.put("postprocess",enhanced.snapshot());
            SpeechPacket packet = SpeechPacket.parse(SpeechPacket.encode(candidates,
                    activeDecoder instanceof EnhancedDecoder enhanced?enhanced.literalCandidates():java.util.Set.of(),activeDecoder.reviewConfirmed()));
            long matchingTime = realtime ? System.currentTimeMillis() : 1_000_000L + Math.round(audioMs);
            if(combined.get()==null) {
                var best=allWords.acceptFinal("combined",packet,evaluationWords,matchingTime,PhoneticMatcher.Settings.defaults());
                if(best.hit()!=null)combined.set(best.hit().word());
            }
            for (String target : targets) {
                if (hits.containsKey(target)) continue; // 对应玩家在第一次命中时已被处决。
                // 加速回放也必须推进断句历史时间，不能把几秒的音频压成毫秒后错误拼接。
                var decision = windows.get(target).acceptFinal("replay-player",packet,configured.get(target),matchingTime,PhoneticMatcher.Settings.defaults());
                if (decision.hit() != null) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("wallMs", (System.nanoTime() - source.startNanos) / 1e6);
                    hit.put("afterSpeechEndMs", (System.nanoTime() - source.startNanos) / 1e6 - clip.speechEndSample() / 16.0);
                    hit.put("audioMs", audioMs); hit.put("mode", decision.hit().mode());
                    hit.put("evidence", decision.hit().evidence()); hit.put("candidate", decision.candidate() + 1);
                    hits.put(target, hit); windows.get(target).forget("replay-player");
                }
            }
        }, error::set,progress->{
            if(progress.interim()&&!progress.text().isBlank()&&firstPartial.get()==null)
                firstPartial.compareAndSet(null,Map.of("text",progress.text(),"wallMs",(System.nanoTime()-source.startNanos)/1e6,"audioMs",source.deliveredBytes/32.0));
        });
        source.startNanos = System.nanoTime();
        session.start();
        if (!released.await((long) Math.ceil(clip.durationSeconds()) + 10, TimeUnit.SECONDS)) {
            session.stop(); throw new IllegalStateException("replay timeout: " + clip.id());
        }
        if (!session.stop()) throw new IllegalStateException("capture thread failed to stop");
        if (error.get() != null || !session.error().isEmpty()) throw new IllegalStateException(session.error());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("round", round); row.put("clip", clip); row.put("initMs", initMs);
        row.put("events", events); row.put("hits", hits);
        row.put("targetHit", clip.target() != null && hits.containsKey(clip.target()));
        row.put("expectedWords",clip.expected());
        row.put("expectedHitCount",clip.expected().stream().filter(hits::containsKey).count());
        row.put("falseKillCount", hits.keySet().stream().filter(target -> !clip.expected().contains(target)).count());
        row.put("firstCombinedHit",combined.get());
        row.put("firstPartial",firstPartial.get());
        return row;
    }

    private static void save(Path output, Corpus corpus, List<Map<String, Object>> rows, String engine, List<String> targets, boolean realtime) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", corpus.source()); report.put("endpointAnnotation", corpus.endpointAnnotation());
        report.put("model", engine.equals("vosk") ? "vosk-model-small-cn-0.22; Shriek native; thread count not configured" : engine + "; sherpa-onnx 1.13.5; CPU 1 thread");
        report.put("timing", (realtime ? "wall clock paced " : "UNPACED CAPABILITY PROBE; NO LATENCY CLAIM; ") + CaptureSession.FRAME_BYTES + "-byte capture chunks; excludes model init, Minecraft tick and network");
        report.put("realtime", realtime);
        report.put("matchingClock", realtime ? "wall-clock" : "input-audio timeline; accelerated inference does not extend the five-second history");
        report.put("evaluatedWords",targets);
        report.put("evaluationVocabulary",evaluationWords);
        report.put("encodedHints",engine.equals("priority")?RecognitionHints.encodePriority(evaluationWords):RecognitionHints.encode(targets));
        report.put("recognitionContext", engine.equals("zipformer")||engine.equals("enhanced")||(engine.equals("natural")||engine.equals("priority")) ? RecognitionHints.names(targets) : List.of());
        if(engine.equals("zipformer")||engine.equals("enhanced")||(engine.equals("natural")||engine.equals("priority"))) {
        report.put("endpointSilence", replaySettings.endpointSilence());
        report.put("shortUtteranceSilence", Math.max(.6f, replaySettings.endpointSilence()));
        report.put("fastEndpointMinUtteranceSeconds", 1.2);
        }
        report.put("maxUtteranceSeconds", replaySettings.maxUtteranceSeconds());
        report.put("budgetStart", (engine.equals("natural")||engine.equals("priority"))?"Silero detected speech; watchdog survives vocabulary churn; idle reset":"first nonempty recognition hypothesis; idle does not consume speech budget");
        report.put("segmentReset", engine.equals("sensevoice") ? "Silero VAD; fresh offline stream per utterance; no vocabulary access" : "fresh native stream at each endpoint and vocabulary change");
        report.put("dangerWordPolicy", "selected final sentence; each target scored separately using evaluationVocabulary; actual combined decision also recorded; first hit only");
        report.put("phoneticMatching", true);
        report.put("phoneticThreshold",PhoneticMatcher.Settings.defaults().threshold());
        report.put("rows", rows);
        Files.writeString(output, new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(report));
    }
}
