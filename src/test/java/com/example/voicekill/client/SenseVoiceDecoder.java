package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** VAD 分段后由一个双语识别器转写；不接收危险词、汉字谐音或匹配结果。 */
public final class SenseVoiceDecoder implements CaptureSession.Decoder {
    private final UtteranceDecoder decoder;

    public SenseVoiceDecoder(Path directory, int threads, float silenceSeconds) {
        if (!Float.isFinite(silenceSeconds) || silenceSeconds < .35f || silenceSeconds > 1.5f)
            throw new IllegalArgumentException("无效的语音端点等待");
        var model = OfflineModelConfig.builder().setTokens(directory.resolve("tokens.txt").toString())
                .setSenseVoice(OfflineSenseVoiceModelConfig.builder().setModel(directory.resolve("model.int8.onnx").toString())
                        .setLanguage("auto").setInverseTextNormalization(false).build())
                .setNumThreads(Math.max(1, Math.min(4, threads))).setDebug(false).build();
        var recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build());
        try {
            var vad = new Vad(VadModelConfig.builder().setSampleRate(16000).setNumThreads(1).setDebug(false)
                    .setSileroVadModelConfig(SileroVadModelConfig.builder().setModel(directory.resolve("silero_vad.onnx").toString())
                            .setWindowSize(512).setThreshold(.5f).setMinSpeechDuration(.15f)
                            .setMinSilenceDuration(silenceSeconds).setMaxSpeechDuration(15).build()).build());
            decoder = new UtteranceDecoder(new SileroSegments(vad), new UtteranceDecoder.Transcriber() {
                @Override public String recognize(float[] speech) {
                    var stream = recognizer.createStream();
                    try {
                        stream.acceptWaveform(Arrays.copyOf(speech, speech.length + 8000), 16000);
                        recognizer.decode(stream);
                        return recognizer.getResult(stream).getText();
                    } finally { stream.release(); }
                }
                @Override public void close() { recognizer.release(); }
            });
        } catch (Throwable failure) { recognizer.release(); throw failure; }
    }

    private static final class SileroSegments implements UtteranceDecoder.Segmenter {
        private final Vad vad;
        private final float[] frame = new float[512];
        private final float[] history = new float[16000 * 17];
        private long totalSamples;
        private int used;
        SileroSegments(Vad vad) { this.vad = vad; }
        @Override public List<float[]> accept(byte[] pcm) {
            float[] samples = AudioCapture.pcmSamples(pcm);
            for (float sample : samples) history[(int)(totalSamples++ % history.length)] = sample;
            List<float[]> complete = new ArrayList<>();
            for (int offset = 0; offset < samples.length;) {
                int count = Math.min(frame.length - used, samples.length - offset);
                System.arraycopy(samples, offset, frame, used, count);
                offset += count; used += count;
                if (used == frame.length) {
                    vad.acceptWaveform(Arrays.copyOf(frame, frame.length));
                    used = 0;
                    while (!vad.empty()) {
                        var segment = vad.front();
                        // 保留前后 250ms 声学上下文，避免 VAD 边界裁掉字母开头的爆破音。
                        long start = Math.max(0, (long)segment.getStart() - 4000);
                        long end = Math.min(totalSamples, (long)segment.getStart() + segment.getSamples().length + 4000);
                        if (start < totalSamples - history.length) throw new IllegalStateException("语音分段超出缓存上限");
                        float[] utterance = new float[(int)(end-start)];
                        for (int i=0;i<utterance.length;i++) utterance[i]=history[(int)((start+i)%history.length)];
                        complete.add(utterance);
                        vad.pop();
                    }
                }
            }
            return complete;
        }
        @Override public void close() { vad.release(); }
    }

    @Override public List<String> accept(byte[] pcm) { return decoder.accept(pcm); }
    @Override public void close() { decoder.close(); }
}
