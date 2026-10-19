package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import com.example.voicekill.speech.SpeechPacket;
import com.pryzmm.client.event.EventHandler;
import com.pryzmm.networking.ShriekPackets;
import com.pryzmm.networking.packets.PlayerSpeakPacket;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 录音/识别的唯一所有者；Shriek 仅提供模型准备和文本网络通道。 */
public final class MicCapture {
    public static final String SEPARATOR = SpeechPacket.SEPARATOR;
    private static volatile CaptureSession session;
    private static volatile EnhancedDecoder enhanced;
    private static volatile String state = "idle";
    private static volatile String lastError = "";
    private static volatile String activeEngine = "";
    private static volatile String note = "";
    private static volatile String currentDevice = "";
    private static volatile String inputFormat = "";
    private static volatile long generation;
    private static volatile boolean attempted;
    private static volatile List<String> recognitionWords = List.of();

    public static void updateRecognitionWords(List<String> words) {
        recognitionWords = RecognitionHints.names(words);
    }

    private MicCapture() {}
    public static boolean isRunning() { var active = session; return active != null && active.isRunning(); }
    public static String currentDevice() { return currentDevice; }
    public static boolean hasAttempted() { return attempted; }

    public static String state() { var active = session; return active == null ? state : active.state(); }
    public static String error() { var active = session; return active == null ? lastError : active.error(); }

    public static Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        var active = session;
        map.put("state", state()); map.put("error", error());
        map.put("engine", activeEngine); map.put("note", note);
        map.put("device", currentDevice); map.put("format", inputFormat);
        map.put("level", active == null ? 0 : active.level());
        map.put("chunks", active == null ? 0 : active.chunks());
        map.put("os", System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        map.put("java", System.getProperty("java.version"));
        var post = enhanced;
        if (post != null) map.put("postprocess", post.snapshot());
        return map;
    }

    public static synchronized boolean start(String device) {
        attempted = true;
        if (!stop()) return false;
        state = "starting"; lastError = ""; note = "";
        AudioCapture source = null;
        CaptureSession.Decoder decoder = null;
        try {
            // 先停止并等 Shriek 退出，再开设备。我们不复用/修改它的 native Recognizer。
            stopShriek();
            ModConfig config = ModConfig.get();
            if (config == null) throw new IllegalStateException("配置尚未加载");
            state = "model";
            decoder = createDecoder(config);
            decoder = enhance(decoder, config);
            state = "opening";
            source = AudioCapture.open(device);
            currentDevice = source.device(); inputFormat = source.format();
            final long token = ++generation;
            final CaptureSession.Decoder activeDecoder = decoder;
            session = new CaptureSession(source, decoder, candidates -> {
                String packet = SpeechPacket.encode(candidates, activeDecoder.literalCandidates());
                if (config.logSpeech && activeDecoder instanceof EnhancedDecoder post)
                    VoiceKillMod.LOGGER.info("[VoiceKill] 句末处理：{}", post.snapshot());
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (generation == token && client.getConnection() != null) {
                        ShriekPackets.sendToServer(new PlayerSpeakPacket(packet));
                    }
                });
            }, failure -> VoiceKillMod.LOGGER.error("[VoiceKill] 录音会话中断：{}", failure));
            session.start();
            VoiceKillMod.LOGGER.info("[VoiceKill] 录音已启动：{}，{} → 16kHz mono；识别引擎={}", currentDevice, inputFormat, activeEngine);
            return true;
        } catch (Throwable failure) {
            if (source != null) {
                try { source.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            if (decoder != null) decoder.close();
            session = null; state = "failed";
            lastError = CaptureSession.rootCause(failure);
            VoiceKillMod.LOGGER.error("[VoiceKill] 录音启动失败（{} / {} / {}）：{}",
                    System.getProperty("os.name"), System.getProperty("os.arch"), device, lastError, failure);
            return false;
        }
    }

    private static CaptureSession.Decoder createDecoder(ModConfig config) throws Exception {
        if ("zipformer".equals(config.speechEngine)) {
            try {
                Path directory = config.speechModelDir.isBlank()
                        ? net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                            .resolve("voicekill/models/zipformer-bilingual-small-int8")
                        : Path.of(config.speechModelDir);
                VoiceKillMod.LOGGER.info("[VoiceKill] 准备本地中英双语模型（首次约 60MB）：{}", directory);
                var decoder = new ZipformerDecoder(SpeechModels.prepare(directory, config.autoModelDownload), config.speechThreads,
                        () -> recognitionWords, new ZipformerDecoder.Settings(config.speechEndpointSilenceSeconds, config.speechContextHints, 1.5f, config.speechMaxUtteranceSeconds));
                activeEngine = "Zipformer 中英双语 INT8";
                return decoder;
            } catch (Exception | LinkageError failure) {
                if (!config.allowVoskFallback) throw failure;
                note = "双语引擎不可用，已回退 Vosk：" + CaptureSession.rootCause(failure);
                VoiceKillMod.LOGGER.warn("[VoiceKill] {}", note, failure);
            }
        }
        String model = config.voskModel == null ? "" : config.voskModel.trim();
        if (model.isEmpty()) throw new IllegalArgumentException("voskModel 为空，请配置中文模型");
        Path modelPath = com.pryzmm.ShriekConstants.acousticModelPath.resolve(model);
        if (!Files.isDirectory(modelPath) && !config.autoModelDownload) {
            throw new IllegalStateException("缺少模型 " + modelPath + "，请手动安装或开启 autoModelDownload");
        }
        if (config.autoModelDownload) modelPath = Path.of(EventHandler.getOrCreatePath(model));
        if (!Files.isDirectory(modelPath)) throw new IllegalStateException("模型目录不存在：" + modelPath);
        var decoder = new VoskDecoder(modelPath, config.speechMaxUtteranceSeconds);
        activeEngine = "Vosk 兼容模式";
        return decoder;
    }

    private static CaptureSession.Decoder enhance(CaptureSession.Decoder base, ModConfig config) {
        if (!config.speechSentenceReview && !config.speechPunctuation) return base;
        Path root = config.speechPostModelDir.isBlank()
                ? net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("voicekill/models")
                : Path.of(config.speechPostModelDir);
        enhanced = new EnhancedDecoder(base, () -> {
            SentencePostprocessor.Reviewer reviewer = null;
            SentencePostprocessor.Punctuator punctuator = null;
            try {
                if (config.speechSentenceReview) reviewer = NativeSentenceModels.reviewer(
                        SpeechModels.prepare(root.resolve("sensevoice-review-int8"), config.autoModelDownload, "sensevoice-review-int8"), config.speechThreads);
                if (config.speechPunctuation) punctuator = NativeSentenceModels.punctuator(
                        SpeechModels.prepare(root.resolve("punctuation-zh-en-int8"), config.autoModelDownload, "punctuation-zh-en-int8"), config.speechThreads);
                return new SentencePostprocessor(reviewer, punctuator);
            } catch (Exception | LinkageError failure) {
                if (reviewer != null) reviewer.close();
                if (punctuator != null) punctuator.close();
                VoiceKillMod.LOGGER.warn("[VoiceKill] 句末模型不可用，继续基础识别", failure);
                throw failure;
            }
        }, config.speechMaxUtteranceSeconds);
        return enhanced;
    }

    /** 返回 false 时保留旧会话，拒绝启动另一个识别线程。 */
    public static synchronized boolean stop() {
        generation++;
        CaptureSession active = session;
        if (active != null && !active.stop()) {
            lastError = "旧录音线程未退出，请重启游戏后重试";
            return false;
        }
        session = null; enhanced = null; state = "idle";
        return true;
    }

    private static Object field(String name) throws ReflectiveOperationException {
        Field field = EventHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void stopShriek() throws Exception {
        Object retry = field("retryTimer");
        if (retry instanceof java.util.Timer timer) timer.cancel();
        Object listener = field("listenThread");
        Object microphone = field("microphoneHandler");
        if (listener instanceof Thread thread) {
            thread.interrupt();
            if (microphone != null) microphone.getClass().getMethod("stopListening").invoke(microphone);
            thread.join(2000);
            if (thread.isAlive()) throw new IllegalStateException("Shriek 录音线程未退出；拒绝并行采音，请重启游戏");
        } else if (microphone != null) {
            microphone.getClass().getMethod("stopListening").invoke(microphone);
        }
        // 保留 Shriek 的非空字段供其退出回调使用，但清除尚未发出的旧文本。
        Field result = EventHandler.class.getDeclaredField("lastResult");
        result.setAccessible(true);
        result.set(null, "");
    }

    /** 独立 native Model/Recognizer 的生命周期，不再反射借用 Shriek 的全局识别器。 */
    public static final class VoskDecoder implements CaptureSession.Decoder {
        private final Object model;
        private final Object recognizer;
        private final Method accept;
        private final Method pointer;
        private final Object resultFunction;
        private final Object finalResultFunction;
        private final Object partialResultFunction;
        private final Method reset;
        private final UtteranceBudget budget;
        private final Method invokePointer;
        private final Method utf8String;
        private boolean closed;
        private CaptureSession.Boundary boundary=CaptureSession.Boundary.NONE;
        @Override public CaptureSession.Boundary boundary(){return boundary;}

        public VoskDecoder(Path path) throws Exception {
            this(path, 5f);
        }

        public VoskDecoder(Path path, float maxUtteranceSeconds) throws Exception {
            budget = new UtteranceBudget(maxUtteranceSeconds);
            Class<?> modelType = Class.forName("org.vosk.Model");
            Object model = modelType.getConstructor(String.class).newInstance(path.toString());
            Object recognizer = null;
            try {
                Class<?> type = Class.forName("org.vosk.Recognizer");
                recognizer = type.getConstructor(modelType, float.class).newInstance(model, 16000f);
                type.getMethod("setMaxAlternatives", int.class).invoke(recognizer, 3);
                accept = type.getMethod("acceptWaveForm", byte[].class, int.class);
                // 不经 JNA 默认系统编码转换 char*；Windows 的 GBK 会损坏中文 JSON。
                pointer = type.getMethod("getPointer");
                Class<?> libraryType = Class.forName("com.sun.jna.NativeLibrary");
                String libraryName = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "libvosk" : "vosk";
                Object library = libraryType.getMethod("getInstance", String.class).invoke(null, libraryName);
                resultFunction = libraryType.getMethod("getFunction", String.class).invoke(library, "vosk_recognizer_result");
                finalResultFunction = libraryType.getMethod("getFunction", String.class).invoke(library, "vosk_recognizer_final_result");
                partialResultFunction = libraryType.getMethod("getFunction", String.class).invoke(library, "vosk_recognizer_partial_result");
                reset = type.getMethod("reset");
                invokePointer = Class.forName("com.sun.jna.Function").getMethod("invokePointer", Object[].class);
                utf8String = Class.forName("com.sun.jna.Pointer").getMethod("getString", long.class, String.class);
                this.model = model; this.recognizer = recognizer;
            } catch (Throwable failure) {
                closeNative(recognizer); closeNative(model);
                throw failure;
            }
        }

        @Override public List<String> accept(byte[] bytes) throws Exception {
            boundary=CaptureSession.Boundary.NONE;
            boolean endpoint = (Boolean) accept.invoke(recognizer, bytes, bytes.length);
            boolean hasHypothesis = false;
            if (!endpoint) {
                Object partialJson = invokePointer.invoke(partialResultFunction, (Object) new Object[]{pointer.invoke(recognizer)});
                var partial = JsonParser.parseString((String)utf8String.invoke(partialJson,0L,"UTF-8")).getAsJsonObject();
                hasHypothesis = partial.has("partial") && !partial.get("partial").getAsString().isBlank();
            }
            boolean due = budget.advance(bytes.length / 2, hasHypothesis);
            if (!endpoint && !due) return List.of();
            Object json = invokePointer.invoke(endpoint ? resultFunction : finalResultFunction,
                    (Object) new Object[]{pointer.invoke(recognizer)});
            List<String> text = parseAlternatives((String) utf8String.invoke(json, 0L, "UTF-8"));
            if (!endpoint) reset.invoke(recognizer);
            budget.reset();
            boundary=text.isEmpty()?(endpoint?CaptureSession.Boundary.IDLE:CaptureSession.Boundary.DISCARD):CaptureSession.Boundary.FINAL;
            return text;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            closeNative(recognizer); closeNative(model);
        }
        private static void closeNative(Object resource) {
            if (resource == null) return;
            try { resource.getClass().getMethod("close").invoke(resource); }
            catch (ReflectiveOperationException failure) { VoiceKillMod.LOGGER.warn("[VoiceKill] 识别资源释放失败", failure); }
        }
    }

    static List<String> parseAlternatives(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        List<String> texts = new ArrayList<>();
        if (root.has("alternatives")) {
            for (var element : root.getAsJsonArray("alternatives")) {
                var candidate = element.getAsJsonObject();
                if (!candidate.has("text")) continue;
                String text = candidate.get("text").getAsString().trim();
                if (!text.isEmpty() && !texts.contains(text)) texts.add(text);
                if (texts.size() == 3) break;
            }
        } else if (root.has("text")) {
            String text = root.get("text").getAsString().trim();
            if (!text.isEmpty()) texts.add(text);
        }
        return List.copyOf(texts);
    }
}
