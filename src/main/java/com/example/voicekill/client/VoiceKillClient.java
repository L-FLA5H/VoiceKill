package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：四角 HUD + 模型准备 + 录音设备。
 *
 * ⚠️ 关于 Vosk 模型（踩过的坑）：{@code getOrCreatePath/loadVoskModel} 是**同步下载**，
 * 首次要拉 42MB。放进客户端 tick（= 渲染线程）会让游戏冻住几分钟。
 * 所以：模型下载、native 初始化和设备打开全部在后台线程完成。
 */
public class VoiceKillClient implements ClientModInitializer {

    /** 启动后等 3 秒再动手，别和原版资源加载抢 */
    private static final int START_DELAY_TICKS = 60;

    private int ticks;
    private boolean started;
    private static KeyMapping micKey;

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("shriek")) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 客户端没检测到 Shriek，语音与 HUD 都不会工作");
            return;
        }

        // 1) 接收服务端 HUD 状态
        ClientPlayNetworking.registerGlobalReceiver(HudStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    HudOverlay.update(payload);
                    MicCapture.updateRecognitionWords(payload.words());
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MicCapture.updateRecognitionWords(java.util.List.of()));

        // 2) 注册四角常驻显示
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicekill", "hud"),
                new HudOverlay());

        // 4) 列出录音设备（方便排查「怎么不是这个麦」）
        AudioDevices.logAvailable();

        // 5) 快捷键：F8 打开录音设备选择界面
        //    1.21.11 起 KeyMapping 的第四个参数是 Category 对象，不再接受字符串
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("voicekill", "main"));
        micKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.voicekill.mic",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));

        // 6) 退出游戏时把我们自己的录音线程干净地关掉
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MicCapture.stop());

        // 7) 后台准备模型和采音，不阻塞渲染线程
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private void tick() {
        // 快捷键优先处理，不受模型准备流程影响
        if (micKey != null) {
            while (micKey.consumeClick()) {
                Minecraft.getInstance().setScreen(new MicScreen());
            }
        }

        ModConfig config = ModConfig.get();
        if (config == null) {
            return;
        }

        if (!started && ++ticks >= START_DELAY_TICKS) {
            started = true;
            if (!MicCapture.hasAttempted()) {
                Thread thread = new Thread(() -> MicCapture.start(config.audioDevice), "VoiceKill-MicStartup");
                thread.setDaemon(true);
                thread.start();
            }
        }
    }
}
