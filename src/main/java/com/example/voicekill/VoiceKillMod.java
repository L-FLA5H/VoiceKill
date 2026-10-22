package com.example.voicekill;

import com.example.voicekill.command.VoiceKillCommand;
import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import com.example.voicekill.speech.SpeechStats;
import com.example.voicekill.web.WebConsole;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口：装配配置、危险词管理器、词频统计、网页控制台、语音监听。
 *
 * 完整识别句仍复用 Shriek 文本通道，进入服务端判定与统计。
 * 轻量 C2S RecognitionActivityPayload 仅通知当前会话出现了新识别文字，
 * 不携带转写内容，也不把临时假设用于危险词处罚或词频统计。
 */
public class VoiceKillMod implements ModInitializer {

    public static final String MOD_ID = "voicekill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ModConfig config;
    private static SpeechStats speechStats;
    private static DangerWordManager dangerWordManager;
    private static WebConsole webConsole;

    @Override
    public void onInitialize() {
        com.example.voicekill.speech.GameTerms.warmUp();
        config = ModConfig.load();
        speechStats = new SpeechStats();
        dangerWordManager = new DangerWordManager(config, speechStats);
        webConsole = new WebConsole(dangerWordManager);

        // 注册 S2C 通道（两边都要注册，客户端才能收）
        PayloadTypeRegistry.playS2C().register(HudStatePayload.TYPE, HudStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(com.example.voicekill.network.RecognitionLexiconPayload.TYPE,com.example.voicekill.network.RecognitionLexiconPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.example.voicekill.network.RecognitionActivityPayload.TYPE,com.example.voicekill.network.RecognitionActivityPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(com.example.voicekill.network.RecognitionActivityPayload.TYPE,
                (payload,context)->dangerWordManager.recordRecognition(context.player(),payload.session()));
        PayloadTypeRegistry.playC2S().register(com.example.voicekill.network.RecognitionHealthPayload.TYPE,com.example.voicekill.network.RecognitionHealthPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(com.example.voicekill.network.RecognitionHealthPayload.TYPE,
                (payload,context)->dangerWordManager.recordRecognitionHealth(context.player(),payload.session(),payload.issue()));

        // /voicekill start | stop | status（别名 /vk）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VoiceKillCommand.register(dispatcher));

        // 每 tick 走一次轮换计时 + 每秒刷新网页快照
        ServerTickEvents.END_SERVER_TICK.register(server -> dangerWordManager.onServerTick(server));

        // 玩家进服时把当前危险词推给他
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                dangerWordManager.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {SpeechHandler.forget(handler.player);dangerWordManager.forgetRecognition(handler.player);});

        // 语音事件 → 记录词频 → 判定 → kill
        SpeechHandler.register(dangerWordManager);

        // 世界加载完成才起网页（这时才读得到玩家列表）
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            dangerWordManager.attach(server);
            webConsole.start(config);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            dangerWordManager.stop(server);
            webConsole.stop();
            SpeechHandler.reset();
        });

        LOGGER.info("[VoiceKill] 初始化完成：自定义词 {} 个，每 {} 秒轮换，智能选词 {}",
                config.dangerWords.size(),
                config.intervalSeconds,
                config.smartPick ? ("开（" + config.smartPickChance + "%）") : "关");
        LOGGER.info("[VoiceKill] 隐私提醒：Shriek 会持续录音，并把识别出的文本发送到服务端，"
                + "请只在自己能控制的房间/服务器里使用");
    }

    public static ModConfig config() {
        return config;
    }

    public static DangerWordManager manager() {
        return dangerWordManager;
    }

    public static SpeechStats stats() {
        return speechStats;
    }
}
