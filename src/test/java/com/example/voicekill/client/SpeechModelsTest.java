package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpeechModelsTest {
    @TempDir Path directory;

    @Test void manifestIsPinnedAndDoesNotBundleWeights() throws Exception {
        var manifest = SpeechModels.manifest();
        assertEquals(5, manifest.files().size(), "识别词表提示需要固定哈希的 bpe.vocab");
        assertEquals(40, manifest.revision().length());
        assertEquals("apache-2.0", manifest.license());
        for (var asset : manifest.files()) {
            assertTrue(asset.url().contains("/resolve/" + manifest.revision() + "/"));
            assertEquals(64, asset.sha256().length());
        }
    }

    @Test void missingOfflineModelFailsWithoutDownloading() {
        assertThrows(Exception.class, () -> SpeechModels.prepare(directory, false));
    }

    @Test void utteranceModelPinsRecognitionAndSegmentationAssets() throws Exception {
        SpeechModels.Manifest manifest;
        try(var resource=getClass().getResourceAsStream("/voicekill/asr/sensevoice-int8.json")) {
            assertNotNull(resource);
            manifest=new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(resource,java.nio.charset.StandardCharsets.UTF_8),SpeechModels.Manifest.class);
        }
        assertEquals(3,manifest.files().size());
        assertEquals(40,manifest.revision().length());
        assertTrue(manifest.files().stream().anyMatch(a -> a.name().equals("silero_vad.onnx")));
        assertTrue(manifest.files().stream().allMatch(a -> a.sha256().matches("[a-f0-9]{64}") && a.size()>0));
    }

    @Test void incompletePcmSampleIsRejectedRatherThanDropped() {
        assertThrows(IllegalArgumentException.class,()->AudioCapture.pcmSamples(new byte[1]));
    }

    @Test void corruptExistingFileIsNeverOverwritten() throws Exception {
        Path file = directory.resolve("encoder.int8.onnx");
        Files.writeString(file, "preserve this file");
        assertThrows(Exception.class, () -> SpeechModels.prepare(directory, false));
        assertEquals("preserve this file", Files.readString(file));
    }

    @Test void pcmConversionUsesSignedLittleEndian() {
        float[] samples = ZipformerDecoder.pcmSamples(new byte[]{0,0, -1,127, 0,-128, -1,-1});
        assertArrayEquals(new float[]{0,32767/32768f,-1,-1/32768f}, samples);
    }
}
