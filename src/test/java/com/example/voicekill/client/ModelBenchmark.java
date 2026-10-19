package com.example.voicekill.client;

import com.google.gson.GsonBuilder;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 手动回放官方测试音频；不是设备/游戏测试，也不是昵称准确率评测。 */
public final class ModelBenchmark {
    public static void main(String[] args) throws Exception {
        Path zipformer = SpeechModels.prepare(Path.of(args[0]), false);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Path> waves;
        try (var files = Files.list(Path.of(args[1]))) {
            waves = files.filter(file -> file.toString().endsWith(".wav")).sorted().toList();
        }
        for (int run = 0; run < 3; run++) {
            for (String engine : run % 2 == 0 ? List.of("zipformer", "vosk") : List.of("vosk", "zipformer")) {
                if (engine.equals("vosk") && args[2].isBlank()) continue;
                long initStart = System.nanoTime();
                try (CaptureSession.Decoder decoder = engine.equals("zipformer")
                        ? new ZipformerDecoder(zipformer, 1) : new MicCapture.VoskDecoder(Path.of(args[2]))) {
                    double initSeconds = (System.nanoTime() - initStart) / 1e9;
                    for (Path wave : waves) {
                        byte[] pcm;
                        try (var input = AudioSystem.getAudioInputStream(wave.toFile()); var converted = AudioCapture.convert(input)) {
                            pcm = converted.readAllBytes();
                        }
                        List<List<String>> utterances = new ArrayList<>();
                        long start = System.nanoTime();
                        for (int offset = 0; offset < pcm.length; offset += 4096) {
                            var text = decoder.accept(Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 4096)));
                            if (!text.isEmpty()) utterances.add(text);
                        }
                        // 回放尾部静音以触发端点，不打开设备、不实时等待。
                        for (int i = 0; i < 16; i++) {
                            var text = decoder.accept(new byte[4096]);
                            if (!text.isEmpty()) utterances.add(text);
                        }
                        double seconds = (System.nanoTime() - start) / 1e9;
                        if (utterances.stream().flatMap(List::stream).anyMatch(text -> text.indexOf('\ufffd') >= 0)) {
                            throw new IllegalStateException("识别文本包含丢失字符，请检查 native UTF-8 解码：" + engine);
                        }
                        double duration = pcm.length / 32000.0;
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("run", run); result.put("engine", engine); result.put("audio", wave.getFileName().toString());
                        result.put("initSeconds", initSeconds); result.put("audioSeconds", duration);
                        result.put("decodeSeconds", seconds); result.put("rtf", seconds / duration);
                        result.put("utterances", utterances); results.add(result);
                    }
                }
            }
        }
        Path output = Path.of(args[3]); Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(results));
        System.out.println("ASR_BENCHMARK=" + output.toAbsolutePath());
    }
}
