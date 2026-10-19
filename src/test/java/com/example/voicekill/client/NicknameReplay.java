package com.example.voicekill.client;

import com.example.voicekill.speech.SpeechPacket;
import com.example.voicekill.speech.SpeechWindow;
import com.example.voicekill.speech.WordMatcher;
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
    private static final List<String> TARGETS = List.of("qaq", "p老板", "豆腐汤宝宝", "干", "才");
    record Clip(String id, String target, String spokenText, String voice, String kind, String file,
                long speechStartSample, long speechEndSample, double durationSeconds, String sha256) {}
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
        String engine=args.length>4 ? args[4] : "zipformer";
        List<String> targets=args.length>5 && !args[5].isBlank() ? List.of(args[5].split(",")) : TARGETS;
        boolean realtime=args.length<7 || Boolean.parseBoolean(args[6]);
        Path model = Path.of(args[0]);
        if (engine.equals("sensevoice")) {
            try (var resource=NicknameReplay.class.getResourceAsStream("/voicekill/asr/sensevoice-int8.json")) {
                if(resource==null) throw new IllegalStateException("缺少实验模型清单");
                var manifest=new Gson().fromJson(new java.io.InputStreamReader(resource,java.nio.charset.StandardCharsets.UTF_8),SpeechModels.Manifest.class);
                for(var asset:manifest.files()) SpeechModels.validate(model.resolve(asset.name()),asset);
            }
        } else if (!engine.equals("vosk")) SpeechModels.prepare(model,false);
        if(engine.equals("enhanced")) {
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
        CaptureSession.Decoder nativeDecoder = engine.equals("vosk") ? new MicCapture.VoskDecoder(model)
                : engine.equals("sensevoice") ? new SenseVoiceDecoder(model,1,.35f)
                : new ZipformerDecoder(model, 1, () -> targets, ZipformerDecoder.Settings.defaults());
        if(engine.equals("enhanced")) {
            var post=new SentencePostprocessor(new SentencePostprocessor.Reviewer() {
                public String recognize(float[] audio) throws Exception{return sharedReviewer.recognize(audio);}
                public void close(){}
            },new SentencePostprocessor.Punctuator() {
                public String punctuate(String text) throws Exception{return sharedPunctuator.punctuate(text);}
                public void close(){}
            });
            nativeDecoder=new EnhancedDecoder(nativeDecoder,post,5);
        }
        final CaptureSession.Decoder activeDecoder=nativeDecoder;
        double initMs = (System.nanoTime() - initStart) / 1e6;
        CaptureSession.Decoder decoder = new CaptureSession.Decoder() {
            @Override public List<String> accept(byte[] bytes) throws Exception { return activeDecoder.accept(bytes); }
            @Override public void close() { try { activeDecoder.close(); } finally { released.countDown(); } }
        };
        Map<String, SpeechWindow> windows = new LinkedHashMap<>();
        Map<String, List<WordMatcher.Word>> configured = new LinkedHashMap<>();
        Map<String, Object> hits = new LinkedHashMap<>();
        for (String target : targets) {
            windows.put(target, new SpeechWindow());
            configured.put(target, List.of(new WordMatcher.Word(target, "manual", WordAliases.variants(target, null))));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        CaptureSession session = new CaptureSession(source, decoder, candidates -> {
            double wallMs = (System.nanoTime() - source.startNanos) / 1e6;
            double audioMs = source.deliveredBytes / 32.0;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("candidates", candidates); event.put("wallMs", wallMs); event.put("audioMs", audioMs);
            event.put("afterSpeechEndMs", wallMs - clip.speechEndSample() / 16.0); events.add(event);
            if(activeDecoder instanceof EnhancedDecoder enhanced)event.put("postprocess",enhanced.snapshot());
            SpeechPacket packet = SpeechPacket.parse(SpeechPacket.encode(candidates,
                    activeDecoder instanceof EnhancedDecoder enhanced?enhanced.literalCandidates():java.util.Set.of()));
            for (String target : targets) {
                if (hits.containsKey(target)) continue; // 对应玩家在第一次命中时已被处决。
                var decision = windows.get(target).accept("replay-player", packet, configured.get(target), System.currentTimeMillis());
                if (decision.hit() != null) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("wallMs", (System.nanoTime() - source.startNanos) / 1e6);
                    hit.put("afterSpeechEndMs", (System.nanoTime() - source.startNanos) / 1e6 - clip.speechEndSample() / 16.0);
                    hit.put("audioMs", audioMs); hit.put("mode", decision.hit().mode());
                    hit.put("evidence", decision.hit().evidence()); hit.put("candidate", decision.candidate() + 1);
                    hits.put(target, hit); windows.get(target).forget("replay-player");
                }
            }
        }, error::set);
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
        row.put("falseKillCount", hits.keySet().stream().filter(target -> !target.equals(clip.target())).count());
        return row;
    }

    private static void save(Path output, Corpus corpus, List<Map<String, Object>> rows, String engine, List<String> targets, boolean realtime) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", corpus.source()); report.put("endpointAnnotation", corpus.endpointAnnotation());
        report.put("model", engine.equals("vosk") ? "vosk-model-small-cn-0.22; Shriek native; thread count not configured" : engine + "; sherpa-onnx 1.13.5; CPU 1 thread");
        report.put("timing", (realtime ? "wall clock paced " : "UNPACED CAPABILITY PROBE; NO LATENCY CLAIM; ") + CaptureSession.FRAME_BYTES + "-byte capture chunks; excludes model init, Minecraft tick and network");
        report.put("realtime", realtime);
        report.put("evaluatedWords",targets);
        report.put("recognitionContext", engine.equals("zipformer")||engine.equals("enhanced") ? RecognitionHints.names(targets) : List.of());
        if(engine.equals("zipformer")||engine.equals("enhanced")) {
        report.put("endpointSilence", ZipformerDecoder.Settings.defaults().endpointSilence());
        report.put("shortUtteranceSilence", .6);
        report.put("fastEndpointMinUtteranceSeconds", 1.2);
        }
        report.put("maxUtteranceSeconds", ZipformerDecoder.Settings.defaults().maxUtteranceSeconds());
        report.put("budgetStart", "first nonempty recognition hypothesis; idle does not consume speech budget");
        report.put("segmentReset", engine.equals("sensevoice") ? "Silero VAD; fresh offline stream per utterance; no vocabulary access" : "fresh native stream at each endpoint and vocabulary change");
        report.put("dangerWordPolicy", "each target separately configured as manual; no added aliases; first hit only");
        report.put("rows", rows);
        Files.writeString(output, new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(report));
    }
}
