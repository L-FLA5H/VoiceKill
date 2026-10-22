package com.example.voicekill.client;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 实际 open 路径；替换 Java Sound 设备目录，不打开物理麦克风。 */
class AudioDeviceSelectionTest {
    private record Fake(Mixer mixer, AtomicInteger opens) {}
    private Fake fake(String name, boolean input, float rate) {
        var opens = new AtomicInteger();
        var format = new AudioFormat(rate,16,1,true,false);
        var info = new Mixer.Info(name,"test","synthetic input/output","1") {};
        TargetDataLine line = (TargetDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{TargetDataLine.class},(proxy,method,args)->switch(method.getName()) {
                    case "open" -> {opens.incrementAndGet();yield null;}
                    case "getFormat" -> format;
                    case "getLineInfo" -> new DataLine.Info(TargetDataLine.class,format);
                    case "isOpen","isActive","isRunning" -> true;
                    case "getBufferSize","available","getFramePosition" -> 32000;
                    case "getLongFramePosition","getMicrosecondPosition" -> 0L;
                    case "getLevel" -> 0f;
                    default -> null;
                });
        Mixer mixer = (Mixer) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Mixer.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "getMixerInfo" -> info;
                    case "getTargetLineInfo" -> input ? new Line.Info[]{new DataLine.Info(TargetDataLine.class,format)} : new Line.Info[0];
                    case "isLineSupported" -> input && args[0] instanceof DataLine.Info requested
                            && requested.getLineClass()==TargetDataLine.class
                            && java.util.Arrays.stream(requested.getFormats()).anyMatch(f->f.matches(format));
                    case "getLine" -> {if(!input)throw new AssertionError("selected an output device");yield line;}
                    default -> null;
                });
        return new Fake(mixer,opens);
    }

    @Test void sameNamedOutputCannotShadowTheWorkingInput() throws Exception {
        var output=fake("耳机 (Test Bluetooth)",false,16000);
        var input=fake("耳机 (Test Bluetooth)",true,16000);
        try(var capture=AudioCapture.open("耳机 (Test Bluetooth)",List.of(output.mixer(),input.mixer()))) {
            assertEquals("耳机 (Test Bluetooth)",capture.device());
            assertEquals(1,input.opens().get());
            assertEquals(0,output.opens().get());
        }
    }

    @Test void bluetoothNarrowbandInputUsesItsEightKhzFormat() throws Exception {
        var input=fake("Bluetooth hands-free",true,8000);
        try(var capture=AudioCapture.open("Bluetooth hands-free",List.of(input.mixer()))) {
            assertEquals(1,input.opens().get());
            assertTrue(capture.format().contains("8000"));
        }
    }

    @Test void ambiguousInputsDoNotSilentlyChooseTheFirst() {
        var a=fake("USB microphone",true,16000);
        var b=fake("USB microphone",true,16000);
        assertThrows(IllegalArgumentException.class,()->AudioCapture.open("USB microphone",List.of(a.mixer(),b.mixer())));
    }

    @Test void advertisedConvertibleFormatIsUsedBeyondTheFallbackRates() throws Exception {
        var input=fake("native 32k microphone",true,32000);
        try(var capture=AudioCapture.open("native 32k microphone",List.of(input.mixer()))) {
            assertTrue(capture.format().contains("32000"));
            assertEquals(1,input.opens().get());
        }
    }

    @Test void explicitlySelectedOutputIsNotReplacedWithSomeOtherMicrophone() {
        var output=fake("headphones",false,16000);
        var input=fake("other microphone",true,16000);
        assertThrows(IllegalArgumentException.class,()->AudioCapture.open("headphones",List.of(output.mixer(),input.mixer())));
        assertEquals(0,input.opens().get());
    }
}
