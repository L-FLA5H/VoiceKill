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
 * 架构说明（重要）：本项目不需要自建 C2S 数据包。
 * Shriek 已经在客户端完成「麦克风 → Vosk → 文本」，并通过它自己的通道把文本送到服务端，
 * 服务端触发 ServerPlayerTalkEvent。我们只做「服务端拿到文本之后的判定与统计」。
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
        config = ModConfig.load();
        speechStats = new SpeechStats();
        dangerWordManager = new DangerWordManager(config, speechStats);
        webConsole = new WebConsole(dangerWordManager);

        // 注册 S2C 通道（两边都要注册，客户端才能收）
        PayloadTypeRegistry.playS2C().register(HudStatePayload.TYPE, HudStatePayload.CODEC);

        // /voicekill start | stop | status（别名 /vk）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VoiceKillCommand.register(dispatcher));

        // 每 tick 走一次轮换计时 + 每秒刷新网页快照
        ServerTickEvents.END_SERVER_TICK.register(server -> dangerWordManager.onServerTick(server));

        // 玩家进服时把当前危险词推给他
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                dangerWordManager.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SpeechHandler.forget(handler.player));

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
