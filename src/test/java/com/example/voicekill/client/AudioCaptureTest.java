package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class AudioCaptureTest {
    @Test void nativeMonoAndStereoConvertToRecognizerPcmWithoutADevice() throws Exception {
        for (AudioFormat format : AudioCapture.formats()) {
            int frames = (int) format.getSampleRate() / 10;
            byte[] input = new byte[frames * format.getFrameSize()];
            try (var source = new AudioInputStream(new ByteArrayInputStream(input), format, frames);
                 var output = AudioCapture.convert(source)) {
                assertTrue(output.getFormat().matches(AudioCapture.OUTPUT));
                byte[] converted = output.readAllBytes();
                assertTrue(converted.length > 2800 && converted.length < 3500,
                        format + " converted bytes=" + converted.length);
                assertEquals(0, converted.length % 2);
            }
        }
    }
}
