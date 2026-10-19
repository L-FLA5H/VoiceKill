package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import java.util.List;

/** 设备信息来自与实际打开设备相同的格式探测规则。 */
public final class AudioDevices {
    private AudioDevices() {}
    public static List<String> list() {
        try { return AudioCapture.devices(); }
        catch (RuntimeException failure) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 无法枚举输入设备：{}", failure.toString());
            return List.of();
        }
    }
    public static void logAvailable() {
        VoiceKillMod.LOGGER.info("[VoiceKill] 输入设备：{}；系统={}，架构={}，Java={}", list(),
                System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"));
    }
}
