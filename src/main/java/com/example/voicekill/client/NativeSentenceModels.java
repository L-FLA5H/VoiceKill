package com.example.voicekill.client;

import com.k2fsa.sherpa.onnx.*;
import java.nio.file.Path;

/** 两个独立可选模型，只接收音频/文字，不接收危险词或命中标签。 */
public final class NativeSentenceModels {
    private NativeSentenceModels() {}
    public static SentencePostprocessor.Reviewer reviewer(Path directory,int threads) {
        var model=OfflineModelConfig.builder().setTokens(directory.resolve("tokens.txt").toString())
                .setSenseVoice(OfflineSenseVoiceModelConfig.builder().setModel(directory.resolve("model.int8.onnx").toString())
                        .setLanguage("auto").setInverseTextNormalization(false).build())
                .setNumThreads(threads).setDebug(false).build();
        var recognizer=new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build());
        return new SentencePostprocessor.Reviewer() {
            public String recognize(float[] audio) {
                var stream=recognizer.createStream();
                try {stream.acceptWaveform(audio,16000);recognizer.decode(stream);return recognizer.getResult(stream).getText();}
                finally {stream.release();}
            }
            public void close(){recognizer.release();}
        };
    }
    public static SentencePostprocessor.Punctuator punctuator(Path directory,int threads) {
        var model=OfflinePunctuationModelConfig.builder().setCtTransformer(directory.resolve("model.int8.onnx").toString())
                .setNumThreads(threads).setDebug(false).build();
        var punctuator=new OfflinePunctuation(OfflinePunctuationConfig.builder().setModel(model).build());
        return new SentencePostprocessor.Punctuator() {
            public String punctuate(String text){return punctuator.addPunctuation(text);}
            public void close(){punctuator.release();}
        };
    }
}
