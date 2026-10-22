package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import com.google.gson.Gson;
import java.util.List;

/** 草稿与应用分离；本地设置不会覆盖服务器词库、轮次参数或 F8 已保存的设备。 */
public final class ClientSettings {
    private static final Gson GSON=new Gson();
    private static final List<String> FIELDS=List.of("speechEngine","speechThreads","speechContextHints",
            "speechNaturalBoundary","speechEndpointSilenceSeconds","speechMaxUtteranceSeconds","speechSentenceReview",
            "speechPunctuation","speechModelDir","speechPostModelDir","autoStartMicrophone","allowVoskFallback",
            "autoModelDownload","logSpeech","hudEnabled","hudShowBoard","hudTranscript","hudTranscriptSeconds",
            "hudTranscriptLines","hudWordWidth","hudWordLines","hudHotbarHint","hudHotbarWidth","hudHotbarSeconds",
            "hudHotbarBottom","hudBoardX","hudBoardY","hudPanelOpacity");
    private ClientSettings() {}
    public static ModConfig draft(ModConfig current){return GSON.fromJson(GSON.toJson(current),ModConfig.class);}
    public static void apply(ModConfig draft,ModConfig current) {
        try {for(String name:FIELDS){var field=ModConfig.class.getField(name);field.set(current,field.get(draft));}}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("设置字段不一致",failure);}
    }
}
