package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 游戏内选择录音设备（F8），**选完立即生效**。
 *
 * 生效方式不是去改 Shriek 的麦克风，而是由本模组接管录音
 * （自己打开设备 → 独立识别会话 → 用 Shriek 的通道发包）。
 * 切换前等待旧会话退出，退出超时则拒绝并行启动。
 *
 * @see MicCapture
 */
public class MicScreen extends Screen {
    private int page;

    public MicScreen() {
        super(Component.literal("音频输入设备 · 连接成功后自动保存"));
    }

    @Override
    protected void init() {
        clearWidgets();
        ModConfig config = ModConfig.get();
        String current = config == null ? "" : config.audioDevice;
        List<String> devices = AudioDevices.list();

        int width = Math.min(420, this.width - 60);
        int x = (this.width - width) / 2;
        int y = 40;

        addRenderableWidget(Button.builder(
                        label("系统默认输入设备",
                                MicCapture.isRunning() && current != null && current.isBlank(),
                                current == null || current.isBlank()),
                        b -> apply(""))
                .bounds(x, y, width, 20)
                .build());
        y += 24;

        int pageSize = Math.max(1, (this.height - 160) / 24);
        int pages = Math.max(1, (devices.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pages - 1);
        for (String device : devices.subList(page * pageSize, Math.min(devices.size(), (page + 1) * pageSize))) {
            final String name = device;
            boolean selected = current != null && current.equals(device);
            addRenderableWidget(Button.builder(label(name, MicCapture.isRunning() && selected, selected),
                            b -> apply(name))
                    .bounds(x, y, width, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(name)))
                    .build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page = Math.max(0, page - 1); init(); })
                .bounds(x, this.height - 96, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { page = Math.min(pages - 1, page + 1); init(); })
                .bounds(x + 94, this.height - 96, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新设备"), b -> init())
                .bounds(x + 188, this.height - 96, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("停止录音"), b -> {
            Thread thread = new Thread(MicCapture::stop, "VoiceKill-MicStop");
            thread.setDaemon(true); thread.start();
        }).bounds(x, this.height - 72, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(x, this.height - 30, width, 20)
                .build());
    }

    private static Component label(String text, boolean active, boolean saved) {
        String shown = text.length() > 40 ? text.substring(0, 38) + "…" : text;
        if (active) {
            return Component.literal("▶ " + shown).withStyle(s -> s.withColor(0x3FD68A));
        }
        return Component.literal((saved ? "✔ " : "   ") + shown);
    }

    private void apply(String device) {
        String shown = (device == null || device.isBlank()) ? "系统默认" : device;
        Minecraft client = Minecraft.getInstance();

        Thread thread = new Thread(() -> {
            boolean ok = MicCapture.start(device);
            if (ok) {
                ModConfig config = ModConfig.get();
                if (config != null) {
                    config.audioDevice = device == null ? "" : device;
                    config.save();
                }
            }
            client.execute(() -> {
                if (client.player != null) client.player.displayClientMessage(
                        Component.literal(ok
                                ? ("录音设备已切换：" + shown + "（已生效，对着麦说话试试）")
                                : ("切换失败：" + MicCapture.error())), false);
                if (client.screen == this) init();
            });
            VoiceKillMod.LOGGER.info("[VoiceKill] 设备切换结果：{} → {}", shown, ok);
        }, "VoiceKill-MicSwitch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 别手动调 renderBackground —— 1.21.11 的 super.render() 已经做了，
        // 同一帧模糊两次会抛 "Can only blur once per frame" 把游戏崩掉。
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal(statusText()),
                this.width / 2, this.height - 46, 0xFF8B98A5);
    }

    private String statusText() {
        String status = switch (MicCapture.state()) {
            case "recording" -> "正在录音 · " + MicCapture.snapshot().get("engine") + " · 输入音量 " + Math.round(((Number) MicCapture.snapshot().get("level")).doubleValue() * 100) + "%";
            case "model" -> "正在准备语音模型，首次下载可能需要等待";
            case "starting", "opening", "ready" -> "正在连接麦克风…";
            case "failed" -> "连接失败：" + MicCapture.error();
            default -> "录音已停止 · 选择设备可重新连接";
        };
        String shown = status;
        while (!shown.isEmpty() && font.width(shown + "…") > this.width - 24) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown.equals(status) ? status : shown + "…";
    }
}
