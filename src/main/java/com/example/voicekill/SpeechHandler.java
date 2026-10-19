package com.example.voicekill;

import com.example.voicekill.config.ModConfig;
import com.pryzmm.api.ShriekApi;
import com.pryzmm.api.events.ServerPlayerTalkEvent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shriek 语音事件 → 危险词判定 → kill。
 *
 * 说明：Shriek 的语音转文字在客户端完成（Vosk），识别结果通过 C2S 数据包发到服务端，
 * 服务端再触发 ServerPlayerTalkEvent。所以单人游戏的「集成服务端」也会走到这里，
 * 不需要我们自己做网络包。
 */
public final class SpeechHandler {

    private static boolean registered = false;

    private static final com.example.voicekill.speech.SpeechWindow WINDOW = new com.example.voicekill.speech.SpeechWindow();
    private static final java.util.Map<String, Long> LAST_MOJIBAKE_WARN = new java.util.HashMap<>();

    public static void reset() { WINDOW.clear(); LAST_MOJIBAKE_WARN.clear(); }
    public static void forget(ServerPlayer player) { WINDOW.forget(player.getUUID().toString()); }

    private SpeechHandler() {
    }

    public static void register(DangerWordManager manager) {
        if (registered) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("shriek")) {
            VoiceKillMod.LOGGER.error("[VoiceKill] 没装 Shriek，语音监听未启用（这个模组必须依赖它）");
            return;
        }
        try {
            ShriekApi.registerServerPlayerSpeechListener(event -> handle(manager, event));
            registered = true;
            VoiceKillMod.LOGGER.info("[VoiceKill] 已挂上 Shriek 语音监听");
        } catch (Throwable t) {
            // 用 Throwable 兜住：Shriek 内部版本不兼容时不要连累整个服务端起不来
            VoiceKillMod.LOGGER.error("[VoiceKill] 挂载 Shriek 监听失败，语音功能不可用", t);
        }
    }

    private static void handle(DangerWordManager manager, ServerPlayerTalkEvent event) {
        if (event == null) {
            return;
        }
        handleText(manager, event.getPlayer(), event.getText());
    }

    /**
     * 一段（被认为是某玩家说出的）文本进来之后的完整处理：统计 → 判定 → 处决。
     *
     * @param player 说话的人；null 表示只统计不做判定（例如网页模拟、没人跑图时）
     */
    public static void handleText(DangerWordManager manager, ServerPlayer player, String rawPacketText) {
        if (player != null) {
            MinecraftServer server = player.level().getServer();
            if (server == null) return;
            if (!server.isSameThread()) {
                server.execute(() -> handleText(manager, player, rawPacketText));
                return;
            }
            // 切服、退出和死亡后的排队语音不再影响玩家。
            if (player.hasDisconnected() || player.isDeadOrDying()) return;
        }
        if (!manager.isRunning()) return;
        var packet = com.example.voicekill.speech.SpeechPacket.parse(rawPacketText);
        if (packet.candidates().isEmpty()) return;
        String text = packet.candidates().getFirst();
        String speaker = player == null ? "simulation" : player.getUUID().toString();
        var result = WINDOW.accept(speaker, packet, manager.matchingWords(), System.currentTimeMillis());
        if (result.duplicate()) return;
        warnIfMojibake(speaker, text);
        manager.stats().record(text);
        ModConfig config = ModConfig.get();
        if (config != null && config.logSpeech) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 语音 {}：{}；候选 {}", player == null ? "模拟" : player.getName().getString(),
                    text, packet.candidates());
        }
        if (result.hit() == null) return;
        var hit = result.hit();
        VoiceKillMod.LOGGER.info("[VoiceKill] 命中「{}」：方式={}，证据「{}」，候选={}，最优文本「{}」",
                hit.word(), hit.mode(), hit.evidence(), result.candidate() + 1, text);
        if (player != null) {
            punish(manager, player, hit.word(), text);
        }
    }

    /**
     * 检测「UTF-8 被当 GBK 解码」造成的乱码并提醒。
     *
     * 这种乱码里已经有 U+FFFD（替换符），字节是真的丢了，服务端救不回来 ——
     * 必须让那个玩家的客户端用 UTF-8 跑：JVM 参数加 -Dfile.encoding=UTF-8。
     */
    private static void warnIfMojibake(String who, String text) {
        int broken = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') {
                broken++;
            }
        }
        if (broken < 3 || broken * 5 < text.length()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_MOJIBAKE_WARN.get(who);
        if (last != null && now - last < 60_000L) {
            return;
        }
        LAST_MOJIBAKE_WARN.put(who, now);
        VoiceKillMod.LOGGER.warn("[VoiceKill] {} 的语音是乱码（UTF-8 被当成 GBK 解码），"
                + "让他给 JVM 参数加 -Dfile.encoding=UTF-8 再启动游戏", who);
    }

    private static void punish(DangerWordManager manager, ServerPlayer player, String matched, String rawText) {
        if (player.isDeadOrDying()) {
            return;
        }

        VoiceKillMod.LOGGER.info("[VoiceKill] {} 说出了危险词「{}」→ 执行 kill（识别原文：{}）",
                player.getName().getString(), matched, rawText);

        announceKill(matched, player, rawText);

        // 记一笔言多必失
        manager.recordDeath(player.getName().getString());

        // 死完清掉尾巴，避免复活瞬间被上一句拼出来的词再带走一次
        WINDOW.forget(player.getUUID().toString());

        // 这就是 /kill：直接抹掉，不走命令、不发数据包。
        // 注意 1.21.11 的签名是 kill(ServerLevel)，不是无参的 kill()
        player.kill(player.level());
    }

    /** 在聊天栏告诉所有人谁踩雷了 */
    private static void announceKill(String matched, ServerPlayer victim, String rawText) {
        MinecraftServer server = victim.level().getServer();
        if (server == null) {
            return;
        }
        Component message = Component.literal("[VoiceKill] ")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.literal(victim.getName().getString())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" 说出了危险词「")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(matched)
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal("」（识别：")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(rawText)
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("）")
                        .withStyle(ChatFormatting.GRAY));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(message, false);
        }
    }
}
