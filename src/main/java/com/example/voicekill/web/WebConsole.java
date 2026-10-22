package com.example.voicekill.web;

import com.example.voicekill.DangerWordManager;
import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地网页控制台。
 *
 * 只绑 127.0.0.1，外网/局域网都访问不到 —— 因为它能改游戏状态，不该对外开放。
 * 用 JDK 自带的 HttpServer，不引入任何额外依赖。
 */
public final class WebConsole {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PAGE_PATH = "/voicekill/console.html";

    private final DangerWordManager manager;

    private HttpServer server;
    private ExecutorService executor;
    private byte[] pageCache;

    public WebConsole(DangerWordManager manager) {
        this.manager = manager;
    }

    public synchronized void start(ModConfig config) {
        if (!config.webEnabled || server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.webPort), 0);
            executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "VoiceKill-Web");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);

            server.createContext("/", this::handleIndex);
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/words/set", ex -> handleAction(ex, body -> {
                manager.requestSetWords(strList(body, "words", "word"));
                return true;
            }));
            server.createContext("/api/words/add", ex -> handleAction(ex, body -> {
                manager.requestAddCurrentWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/words/remove", ex -> handleAction(ex, body -> {
                manager.requestRemoveCurrentWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/set-next", ex -> handleAction(ex, body -> {
                manager.requestSetNext(strList(body, "words", "word"));
                return true;
            }));
            server.createContext("/api/reroll", ex -> handleAction(ex, body -> {
                manager.requestReroll();
                return true;
            }));
            server.createContext("/api/debug/match", ex -> handleAction(ex, body -> {
                manager.requestPreviewSpeech(str(body, "text"));
                return true;
            }));
            server.createContext("/api/debug/speech", ex -> handleAction(ex, body -> {
                manager.requestSimulateSpeech(str(body, "text"));
                return true;
            }));
            server.createContext("/api/pool/add", ex -> handleAction(ex, body -> {
                manager.requestAddWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/pool/remove", ex -> handleAction(ex, body -> {
                manager.requestRemoveWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/nameword/add", ex -> handleAction(ex, body -> {
                manager.requestAddNameWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/nameword/remove", ex -> handleAction(ex, body -> {
                manager.requestRemoveNameWord(str(body, "word"));
                return true;
            }));
            server.createContext("/api/alias/add", ex -> handleAction(ex, body -> {
                manager.requestAddAliases(str(body, "word"), strList(body, "aliases", "alias"));
                return true;
            }));
            server.createContext("/api/alias/remove", ex -> handleAction(ex, body -> {
                manager.requestRemoveAlias(str(body, "word"), str(body, "alias"));
                return true;
            }));
            server.createContext("/api/board/reset", ex -> handleAction(ex, body -> {
                manager.requestResetBoard();
                return true;
            }));
            server.createContext("/api/start", ex -> handleAction(ex, body -> {
                manager.requestStart();
                return true;
            }));
            server.createContext("/api/stop", ex -> handleAction(ex, body -> {
                manager.requestStop();
                return true;
            }));
            server.createContext("/api/highfreq/add", ex -> handleAction(ex, body -> {
                manager.requestAddHighFreq(str(body, "word"));
                return true;
            }));
            server.createContext("/api/highfreq/remove", ex -> handleAction(ex, body -> {
                manager.requestRemoveHighFreq(str(body, "word"));
                return true;
            }));
            server.createContext("/api/mixin/toggle", ex -> handleAction(ex, body -> {
                boolean off = body.has("off") && body.get("off").getAsBoolean();
                toggleMicMixin(off);
                return true;
            }));
            server.createContext("/api/audio", this::handleAudio);
            server.createContext("/api/config", ex -> handleAction(ex, body -> {
                Integer interval = body.has("intervalSeconds")
                        ? body.get("intervalSeconds").getAsInt() : null;
                Boolean smart = body.has("smartPick")
                        ? body.get("smartPick").getAsBoolean() : null;
                Integer chance = body.has("smartPickChance")
                        ? body.get("smartPickChance").getAsInt() : null;
                Integer perRound = body.has("wordsPerRound")
                        ? body.get("wordsPerRound").getAsInt() : null;
                Integer highFreq = body.has("highFreqPerRound")
                        ? body.get("highFreqPerRound").getAsInt() : null;
                Integer namesPerRound = body.has("namesPerRound")
                        ? body.get("namesPerRound").getAsInt() : null;
                Boolean silence=body.has("silencePenaltyEnabled")?body.get("silencePenaltyEnabled").getAsBoolean():null;
                Float timeout=body.has("silenceTimeoutSeconds")?body.get("silenceTimeoutSeconds").getAsFloat():null;
                Float damage=body.has("silenceDamageAmount")?body.get("silenceDamageAmount").getAsFloat():null;
                Float damageInterval=body.has("silenceDamageIntervalSeconds")?body.get("silenceDamageIntervalSeconds").getAsFloat():null;
                Boolean phonetic=body.has("phoneticMatching")?body.get("phoneticMatching").getAsBoolean():null;
                Float phoneticThreshold=body.has("phoneticThreshold")?body.get("phoneticThreshold").getAsFloat():null;
                manager.requestUpdateConfig(interval, smart, chance, perRound, highFreq, namesPerRound,silence,timeout,damage,damageInterval,phonetic,phoneticThreshold);
                return true;
            }));

            server.start();
            VoiceKillMod.LOGGER.info("[VoiceKill] 网页控制台已启动：http://127.0.0.1:{}/", config.webPort);
        } catch (Exception e) {
            VoiceKillMod.LOGGER.error("[VoiceKill] 网页控制台启动失败（端口 {} 可能被占用）",
                    config.webPort, e);
            stop();
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (pageCache == null) {
            try (InputStream in = WebConsole.class.getResourceAsStream(PAGE_PATH)) {
                if (in == null) {
                    send(exchange, 500, "text/plain",
                            ("找不到 " + PAGE_PATH).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                pageCache = in.readAllBytes();
            }
        }
        send(exchange, 200, "text/html", pageCache);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        byte[] body = GSON.toJson(manager.snapshot()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, 200, "application/json", body);
    }

    /** 应急开关：关掉麦克风相关的 Mixin（下次启动生效） */
    private void toggleMicMixin(boolean off) {
        java.nio.file.Path flag = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("voicekill-mic-mixin.off");
        try {
            if (off) {
                java.nio.file.Files.writeString(flag, "off");
                VoiceKillMod.LOGGER.warn("[VoiceKill] 已标记禁用麦克风 Mixin，重启游戏后生效");
            } else {
                java.nio.file.Files.deleteIfExists(flag);
                VoiceKillMod.LOGGER.info("[VoiceKill] 已恢复麦克风 Mixin，重启游戏后生效");
            }
        } catch (IOException e) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 写标记文件失败：{}", e.toString());
        }
    }

    /** 录音设备：GET 列表 / POST 切换 */
    private void handleAudio(HttpExchange exchange) throws IOException {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) {
            send(exchange, 200, "application/json", GSON.toJson(Map.of("available", false,
                    "message", "独立服务器无法访问玩家的麦克风，请在各自客户端按 F8"))
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleAction(exchange, body -> {
                String device = str(body, "device");
                if (device == null) return false;
                Thread thread = new Thread(() -> {
                    if (com.example.voicekill.client.MicCapture.start(device)) {
                        ModConfig config = ModConfig.get();
                        config.audioDevice = device;
                        config.save();
                    }
                }, "VoiceKill-WebMicSwitch");
                thread.setDaemon(true); thread.start();
                return true;
            });
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>(com.example.voicekill.client.MicCapture.snapshot());
        payload.put("available", true);
        payload.put("devices", com.example.voicekill.client.AudioDevices.list());
        ModConfig config = ModConfig.get();
        payload.put("current", config == null ? "" : config.audioDevice);
        send(exchange, 200, "application/json", GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
    }

    private interface Action {
        boolean run(JsonObject body);
    }

    private void handleAction(HttpExchange exchange, Action action) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        JsonObject body = new JsonObject();
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            if (raw.length > 0) {
                body = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            boolean ok = action.run(body);
            send(exchange, 200, "application/json",
                    ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 网页请求处理失败：{}", e.toString());
            send(exchange, 400, "application/json",
                    "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String str(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
    }

    /** 读数组字段；同时兼容单个字符串字段（方便 curl 手测） */
    private static java.util.List<String> strList(JsonObject body, String arrayKey, String singleKey) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (body.has(arrayKey) && body.get(arrayKey).isJsonArray()) {
            for (com.google.gson.JsonElement element : body.getAsJsonArray(arrayKey)) {
                if (!element.isJsonNull()) {
                    out.add(element.getAsString());
                }
            }
        } else if (body.has(singleKey) && !body.get(singleKey).isJsonNull()) {
            out.add(body.get(singleKey).getAsString());
        }
        return out;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
