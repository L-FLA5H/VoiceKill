package com.example.voicekill.client;

import javax.sound.sampled.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 打开设备的原生 PCM 格式，再转换为识别器使用的 16kHz 单声道。 */
public final class AudioCapture implements CaptureSession.Source {
    public static final AudioFormat OUTPUT = new AudioFormat(16000, 16, 1, true, false);
    private final TargetDataLine line;
    private final AudioInputStream pcm;
    private final String device;

    private AudioCapture(TargetDataLine line, AudioInputStream pcm, String device) {
        this.line = line;
        this.pcm = pcm;
        this.device = device;
    }

    public static List<AudioFormat> formats() {
        List<AudioFormat> formats = new ArrayList<>();
        for (float rate : new float[]{16000, 48000, 44100, 8000}) {
            for (int channels : new int[]{1, 2}) formats.add(new AudioFormat(rate, 16, channels, true, false));
        }
        return List.copyOf(formats);
    }

    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        for (Mixer mixer : mixers()) {
            try {
                if (supportsInput(mixer)) names.add(mixer.getMixerInfo().getName());
            } catch (RuntimeException ignored) { /* 单个驱动失败不隐藏其他设备 */ }
        }
        return names.stream().distinct().toList();
    }

    private static List<Mixer> mixers() {
        List<Mixer> available = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try { available.add(AudioSystem.getMixer(info)); }
            catch (RuntimeException ignored) { /* 单个驱动不可用时继续其他设备 */ }
        }
        return available;
    }

    private static boolean supportsInput(Mixer mixer) {
        for (Line.Info info : mixer.getTargetLineInfo())
            if (TargetDataLine.class.isAssignableFrom(info.getLineClass())) return true;
        return formats().stream().anyMatch(format -> mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format)));
    }

    /** 优先常见格式，再尝试驱动明确报告、且 Java Sound 能转换的原生 PCM 格式。 */
    private static List<AudioFormat> captureFormats(Mixer mixer) {
        List<AudioFormat> candidates = new ArrayList<>(formats());
        if (mixer != null) {
            try {
                for (Line.Info line : mixer.getTargetLineInfo()) {
                    if (!(line instanceof DataLine.Info data) || !TargetDataLine.class.isAssignableFrom(data.getLineClass())) continue;
                    for (AudioFormat nativeFormat : data.getFormats()) {
                        if (nativeFormat.getSampleRate() <= 0 || nativeFormat.getSampleSizeInBits() <= 0
                                || nativeFormat.getFrameSize() <= 0 || nativeFormat.getChannels() < 1
                                || nativeFormat.getChannels() > 2 || !AudioSystem.isConversionSupported(OUTPUT,nativeFormat)) continue;
                        if (candidates.stream().noneMatch(f -> f.matches(nativeFormat))) candidates.add(nativeFormat);
                    }
                }
            } catch (RuntimeException ignored) { /* 驱动不提供原生清单时继续探测常见格式 */ }
        }
        return candidates;
    }

    public static AudioInputStream convert(AudioInputStream input) {
        return input.getFormat().matches(OUTPUT) ? input : AudioSystem.getAudioInputStream(OUTPUT, input);
    }

    public static float[] pcmSamples(byte[] bytes) {
        if (bytes.length % 2 != 0) throw new IllegalArgumentException("PCM16 数据必须包含完整采样");
        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short sample = (short) ((bytes[2*i] & 255) | (bytes[2*i+1] << 8));
            samples[i] = sample / 32768f;
        }
        return samples;
    }

    public static AudioCapture open(String requested) throws Exception {
        return open(requested, mixers());
    }

    static AudioCapture open(String requested, List<Mixer> available) throws Exception {
        String name = requested == null ? "" : requested.trim();
        Mixer selected = null;
        if (!name.isEmpty()) {
            List<Mixer> matches = new ArrayList<>();
            List<Mixer> exact = new ArrayList<>();
            for (Mixer mixer : available) {
                try { if (!supportsInput(mixer)) continue; }
                catch (RuntimeException ignored) { continue; }
                Mixer.Info info = mixer.getMixerInfo();
                if (info.getName().equals(name)) exact.add(mixer);
                if (info.getName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) matches.add(mixer);
            }
            if (!exact.isEmpty()) matches = exact;
            if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty()
                    ? "找不到可录音的输入设备「" + name + "」，请按 F8 重新选择或使用系统默认输入"
                    : "设备名匹配到多个输入，请在系统中区分设备名称，或按 F8 使用系统默认输入");
            selected = matches.getFirst();
        }
        List<String> failures = new ArrayList<>();
        for (AudioFormat format : captureFormats(selected)) {
            TargetDataLine line = null;
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (selected != null ? !selected.isLineSupported(info) : !AudioSystem.isLineSupported(info)) continue;
                line = selected == null ? (TargetDataLine) AudioSystem.getLine(info) : (TargetDataLine) selected.getLine(info);
                line.open(format);
                AudioInputStream converted = convert(new AudioInputStream(line));
                line.start();
                return new AudioCapture(line, converted, name.isEmpty() ? "系统默认" : selected.getMixerInfo().getName());
            } catch (Exception failure) {
                if (line != null) line.close();
                failures.add(format.getSampleRate() + "Hz/" + format.getChannels() + "ch: " + failure.getMessage());
            }
        }
        throw new LineUnavailableException("无法打开录音设备。请检查系统/启动器麦克风权限、静音及设备占用。尝试："
                + (failures.isEmpty() ? "输入设备没有支持的可转换 PCM 格式；可将常用麦克风设为系统默认后按 F8 选择系统默认输入"
                                     : String.join("; ", failures)));
    }

    public int read(byte[] bytes) throws IOException { return pcm.read(bytes, 0, bytes.length); }
    public String device() { return device; }
    public String format() { return line.getFormat().toString(); }

    @Override public void close() {
        // 先关设备，唤醒阻塞的 read；不要先关转换流（部分 provider 会等待读锁）。
        try { line.stop(); }
        finally {
            line.close();
            try { pcm.close(); } catch (IOException ignored) {}
        }
    }
}
