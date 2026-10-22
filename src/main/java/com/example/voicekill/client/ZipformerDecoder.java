package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** 识别侧的有限束搜索与词表提示；只发送端点确认结果，不修改服务端匹配。 */
public final class ZipformerDecoder implements CaptureSession.Decoder {
    public record Settings(float endpointSilence, boolean contextHints, float hintScore, float maxUtteranceSeconds) {
        public Settings(float endpointSilence, boolean contextHints, float hintScore) {
            this(endpointSilence, contextHints, hintScore, 10f);
        }
        public Settings {
            if (!Float.isFinite(endpointSilence) || endpointSilence < .25f || endpointSilence > 1.5f)
                throw new IllegalArgumentException("无效的语音端点等待");
            if (!Float.isFinite(hintScore) || hintScore < 0 || hintScore > 1.5f)
                throw new IllegalArgumentException("识别提示强度超出验收范围");
            if (!Float.isFinite(maxUtteranceSeconds) || maxUtteranceSeconds < 2 || maxUtteranceSeconds > 15)
                throw new IllegalArgumentException("连续讲话结算上限必须在 2～15 秒之间");
        }
        public static Settings defaults() { return new Settings(.6f, true, 1.5f); }
    }
    private final OnlineRecognizer recognizer;
    private OnlineStream stream;
    private final Supplier<List<String>> words;
    private final Settings settings;
    private final Supplier<String> hintOverride;
    private final UtteranceBudget budget;
    private boolean hasHypothesis;
    private String activeHints;
    private List<String> cachedWords;
    private String cachedHints = "";
    private boolean closed;
    private CaptureSession.Boundary boundary=CaptureSession.Boundary.NONE;
    private long epoch;
    private String partial="";
    @Override public String partial(){return partial;}
    @Override public CaptureSession.Boundary boundary(){return boundary;}
    @Override public long epoch(){return epoch;}

    public ZipformerDecoder(Path directory, int threads) {
        this(directory, threads, List::of, Settings.defaults());
    }

    public ZipformerDecoder(Path directory, int threads, Supplier<List<String>> words, Settings settings) {
        this(directory,threads,words,settings,null);
    }
    public ZipformerDecoder(Path directory, int threads, Supplier<List<String>> words, Settings settings,Supplier<String> hintOverride) {
        this.words = words;
        this.settings = settings;
        this.hintOverride=hintOverride;
        this.budget = new UtteranceBudget(settings.maxUtteranceSeconds());
        var transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(directory.resolve("encoder.int8.onnx").toString())
                .setDecoder(directory.resolve("decoder.onnx").toString())
                .setJoiner(directory.resolve("joiner.int8.onnx").toString()).build();
        var model = OnlineModelConfig.builder().setTransducer(transducer)
                .setTokens(directory.resolve("tokens.txt").toString())
                .setModelingUnit("cjkchar+bpe").setBpeVocab(directory.resolve("bpe.vocab").toString())
                .setNumThreads(Math.max(1, Math.min(4, threads))).setDebug(false).build();
        recognizer = new OnlineRecognizer(OnlineRecognizerConfig.builder().setOnlineModelConfig(model)
                .setEndpointConfig(endpointConfig(settings)).setEnableEndpoint(true).setDecodingMethod("modified_beam_search")
                .setMaxActivePaths(4).setHotwordsScore(settings.hintScore()).build());
        try {
            activeHints = hints();
            stream = createStream(activeHints);
        } catch (Throwable failure) { recognizer.release(); throw failure; }
    }

    public static EndpointConfig endpointConfig(Settings settings) {
        return EndpointConfig.builder()
                .setRule1(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(2.4f).setMinUtteranceLength(0).build())
                .setRule2(EndpointRule.builder().setMustContainNonSilence(true)
                        .setMinTrailingSilence(settings.endpointSilence()).setMinUtteranceLength(1.2f).build())
                .setRule3(EndpointRule.builder().setMustContainNonSilence(true)
                        .setMinTrailingSilence(Math.max(.6f, settings.endpointSilence())).setMinUtteranceLength(0).build()).build();
    }

    private String hints() {
        if (!settings.contextHints()) return "";
        if(hintOverride!=null)return hintOverride.get();
        List<String> requested = words.get();
        if (!requested.equals(cachedWords)) {
            cachedWords = List.copyOf(requested);
            cachedHints = RecognitionHints.encode(cachedWords);
        }
        return cachedHints;
    }

    private OnlineStream createStream(String context) {
        if (context.isEmpty()) return recognizer.createStream();
        try {
            // 1.13.5 的 Java 封装未公开此重载；已核对其固定版本 JNI 的 createStream(long,String)。
            var pointer = OnlineRecognizer.class.getDeclaredField("ptr");
            pointer.setAccessible(true);
            var create = OnlineRecognizer.class.getDeclaredMethod("createStream", long.class, String.class);
            create.setAccessible(true);
            long handle = (Long) create.invoke(recognizer, pointer.getLong(recognizer), context);
            if (handle == 0) throw new IllegalStateException("无法创建带词表提示的语音流");
            return new OnlineStream(handle);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("sherpa-onnx 1.13.5 词表接口不兼容", failure);
        }
    }

    public static float[] pcmSamples(byte[] bytes) {
        return AudioCapture.pcmSamples(bytes);
    }

    /** 显示用的流式假设，不作为处罚候选。自然边界确认后才完成同一识别流。 */
    public String preview(byte[] pcm) {
        stream.acceptWaveform(pcmSamples(pcm),16000);
        while(recognizer.isReady(stream))recognizer.decode(stream);
        return recognizer.getResult(stream).getText().trim();
    }

    public void resetPreview() {
        String nextHints=hints();
        OnlineStream next=createStream(nextHints);
        stream.release();stream=next;activeHints=nextHints;
    }

    public void seedPreview(float[] samples) {
        if(samples.length==0)return;
        stream.acceptWaveform(samples,16000);
        while(recognizer.isReady(stream))recognizer.decode(stream);
    }

    public List<String> finishPreview() {
        stream.acceptWaveform(new float[10560],16000);stream.inputFinished();
        while(recognizer.isReady(stream))recognizer.decode(stream);
        String text=recognizer.getResult(stream).getText().trim();
        return text.isEmpty()?List.of():List.of(text);
    }

    @Override public List<String> accept(byte[] bytes) {
        boundary=CaptureSession.Boundary.NONE;
        // Refresh hints only after this sentence ends, never by discarding its audio.
        stream.acceptWaveform(pcmSamples(bytes), 16000);
        boolean decoded = false;
        while (recognizer.isReady(stream)) { recognizer.decode(stream); decoded = true; }
        if (decoded) {partial=recognizer.getResult(stream).getText().trim();hasHypothesis = !partial.isBlank();}
        boolean due = budget.advance(bytes.length / 2, hasHypothesis);
        boolean endpoint = recognizer.isEndpoint(stream);
        if (!endpoint && !due) return List.of();
        if (endpoint && !hasHypothesis) {
            // 空闲端点可能正好落在下一句话开头；保留尚未解码的前瞻音频。
            // 此时没有旧文本，不会将上一段的已识别音节带入新段。
            recognizer.reset(stream);
            budget.reset();
            partial="";
            boundary=CaptureSession.Boundary.IDLE;
            return List.of();
        }
        if (!endpoint) {
            // 强制结算也先冲刷模型的右侧上下文，不能丢弃已采集但尚未解码的尾部音频。
            stream.acceptWaveform(new float[10560], 16000);
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
        }
        String text = recognizer.getResult(stream).getText().trim();
        String nextHints = hints();
        // 新段不沿用旧段 encoder/beam 状态，避免短片段在后续静音中重复输出。
        OnlineStream next = createStream(nextHints);
        stream.release(); stream = next; activeHints = nextHints;
        budget.reset(); hasHypothesis = false;
        partial="";
        boundary=text.isEmpty()?CaptureSession.Boundary.DISCARD:CaptureSession.Boundary.FINAL;
        return text.isEmpty() ? List.of() : List.of(text);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { stream.release(); } finally { recognizer.release(); }
    }
    @Override public void discard(){resetPreview();budget.reset();hasHypothesis=false;partial="";boundary=CaptureSession.Boundary.DISCARD;epoch++;}
}
