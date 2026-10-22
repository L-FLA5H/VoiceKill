package com.example.voicekill.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 JSON 配置。文件位置：.minecraft/config/voicekill.json
 * 字段名就是 JSON 里的键名，改文件即可生效（下次启动 / 下次进服）。
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String FILE_NAME = "voicekill.json";

    /** 自定义危险词（网页上「自定义」分类） */
    public List<String> dangerWords = new ArrayList<>(List.of(
            "苦力怕", "钻石", "岩浆", "末影人", "不死图腾"
    ));

    /** 人名类危险词（网页上「人名」分类，每轮固定塞 namesPerRound 个） */
    public List<String> nameWords = new ArrayList<>();

    /** 每轮固定塞几个「人名」类危险词 */
    public int namesPerRound = 1;

    /** 额外的高频词（追加到内置高频词表后面） */
    public List<String> highFreqExtra = new ArrayList<>();

    /** 从内置高频词表里禁用的词（网页上点 × 会加到这里） */
    public List<String> highFreqDisabled = new ArrayList<>();

    /**
     * 谐音别名：key 是原词，value 是它可能被识别成的变体。
     * 比如 "ad" -> ["哎地","哎弟","阿德"]，说到任一变体都算命中。
     * 英文缩写会**自动**生成一批变体，这里配的是手动补充。
     */
    public Map<String, List<String>> aliases = new LinkedHashMap<>();

    /** 多少秒换一个危险词 */
    public int intervalSeconds = 30;

    /** 换词时是否用 actionbar 提示所有玩家 */
    public boolean enableActionbarHint = true;

    /** 玩家进服时是否把当前危险词推给他 */
    public boolean hintOnJoin = true;

    /** 把每句识别结果打到日志（排查「为什么没触发」时非常有用） */
    public boolean logSpeech = true;
    public boolean phoneticMatching = true;
    public float phoneticThreshold = .30f;
    public com.example.voicekill.speech.PhoneticMatcher.Settings phoneticSettings(){return new com.example.voicekill.speech.PhoneticMatcher.Settings(phoneticMatching,phoneticThreshold);}

    /** Server-owned optional damage after no new recognized text; 2 health points = 1 heart. */
    public boolean silencePenaltyEnabled = false;
    public float silenceTimeoutSeconds = 20f;
    public float silenceDamageAmount = 2f;
    public float silenceDamageIntervalSeconds = 4f;
    public com.example.voicekill.speech.RecognitionSilence.Rules silenceRules(){return new com.example.voicekill.speech.RecognitionSilence.Rules(silencePenaltyEnabled,silenceTimeoutSeconds,silenceDamageAmount,silenceDamageIntervalSeconds);}
    public void applySilenceRules(com.example.voicekill.speech.RecognitionSilence.Rules rules){silencePenaltyEnabled=rules.enabled();silenceTimeoutSeconds=rules.timeoutSeconds();silenceDamageAmount=rules.damageAmount();silenceDamageIntervalSeconds=rules.intervalSeconds();}

    /** Server-owned death-message templates; empty/invalid lists fall back to defaults. */
    public boolean customDeathMessages = true;
    public String deathMessageStyle = "friends";
    public List<String> deathMessages = new ArrayList<>(com.example.voicekill.DeathMessages.defaults());

    /**
     * Shriek 使用的 Vosk 模型名（见 https://alphacephei.com/vosk/models）。
     * 默认英文模型只认英文，说中文必须换成中文模型。
     */
    public String voskModel = "vosk-model-small-cn-0.22";

    /** 默认中英双语流式模型；vosk 为兼容模式。 */
    public String speechEngine = "zipformer";
    public int speechThreads = 1;
    /** 本轮原词提示；优先字母/混合词和低强度长中文人名，不提示中文单字。 */
    public boolean speechContextHints = true;
    public boolean speechNaturalBoundary = true;
    public boolean autoStartMicrophone = true;
    public float speechEndpointSilenceSeconds = .6f;
    /** 无停顿时的最大语音段时长；还需加解码、网络和游戏 tick 时间。 */
    public float speechMaxUtteranceSeconds = 10f;
    public boolean allowVoskFallback = true;
    /** 留空时缓存到游戏目录 voicekill/models/zipformer-bilingual-small-int8。 */
    public String speechModelDir = "";
    /** 离线句末复核及标点；后台加载失败时继续基础识别。 */
    public boolean speechSentenceReview = true;
    public boolean speechPunctuation = true;
    /** 留空使用游戏目录 voicekill/models；子目录为模型清单 ID。 */
    public String speechPostModelDir = "";

    /**
     * 是否自动准备语音模型（基础约 60MB，自然边界另约 0.64MB，可选句末模型另约 315MB，后台准备）。
     * 关掉后只使用本地已安装的指定模型；缺少模型会提示错误，不静默切换为英文。
     */
    public boolean autoModelDownload = true;

    /** 客户端是否显示屏幕四角的常驻信息 */
    public boolean hudEnabled = true;

    /** 四角信息里是否显示「言多必失榜」 */
    public boolean hudShowBoard = true;
    public boolean hudTranscript = true;
    public float hudTranscriptSeconds = 6f;
    public int hudTranscriptLines = 3;
    public int hudWordWidth = 280;
    public int hudWordLines = 6;
    public boolean hudHotbarHint = true;
    public int hudHotbarWidth = 420;
    public float hudHotbarSeconds = 5f;
    public int hudHotbarBottom = 72;
    public float hudBoardX = 1f;
    public float hudBoardY = .5f;
    public float hudPanelOpacity = .4f;

    /**
     * 指定录音设备（优先完整名称，子串必须唯一，留空 = 系统默认设备）。
     * 启动时会把可用设备列到日志里。
     */
    public String audioDevice = "";

    // ------------------------------------------------------------------
    // 网页控制台
    // ------------------------------------------------------------------

    /** 是否启用网页控制台 */
    public boolean webEnabled = true;

    /** 网页端口（只绑 127.0.0.1，外网访问不到） */
    public int webPort = 38471;

    // ------------------------------------------------------------------
    // 智能选词
    // ------------------------------------------------------------------

    /** 是否用「上周期高频词」来挑下一轮的危险词 */
    public boolean smartPick = true;

    /** 智能选词的命中概率，0~100。70 = 七成概率用高频词，三成随机 */
    public int smartPickChance = 70;

    /** 每轮放几个危险词 */
    public int wordsPerRound = 5;

    /** 每轮固定塞几个「我 / 你 / 他」这种高频地狱词（0 = 不塞） */
    public int highFreqPerRound = 1;

    /** 词库里是否包含内置的游戏词库 */
    public boolean usePresetWords = true;

    /** 网页上展示多少个高频词 */
    public int statsTopN = 12;

    /** 当前生效的配置（全局唯一） */
    private static ModConfig instance;

    public static ModConfig get() {
        return instance;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 读配置；文件不存在 / 读取失败就用默认值，并立刻写回一份方便手改 */
    public static ModConfig load() {
        Path file = file();
        ModConfig loaded = null;

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, ModConfig.class);
            } catch (Exception e) {
                com.example.voicekill.VoiceKillMod.LOGGER.error(
                        "[VoiceKill] 配置读取失败，改用默认值：{}", e.toString());
            }
        }

        if (loaded == null) {
            loaded = new ModConfig();
        }
        loaded.sanitize();
        loaded.save();
        instance = loaded;
        return loaded;
    }

    public void save() {
        synchronized (this) {
            try {
                Path file = file();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(this, writer);
                }
            } catch (Exception e) {
                com.example.voicekill.VoiceKillMod.LOGGER.error(
                        "[VoiceKill] 配置保存失败：{}", e.toString());
            }
        }
    }

    /** 兜底：null / 空列表 / 非法间隔 全部拉回合法值 */
    private void sanitize() {
        phoneticThreshold=phoneticSettings().threshold();
        applySilenceRules(silenceRules());
        Set<String> cleaned = new LinkedHashSet<>();
        if (dangerWords != null) {
            for (String word : dangerWords) {
                if (word == null) continue;
                String trimmed = word.trim();
                if (!trimmed.isEmpty()) cleaned.add(trimmed);
            }
        }
        if (cleaned.isEmpty()) {
            cleaned.add("苦力怕");
            cleaned.add("钻石");
            cleaned.add("岩浆");
        }
        dangerWords = new ArrayList<>(cleaned);

        if (intervalSeconds < 5) intervalSeconds = 5;
        if (intervalSeconds > 3600) intervalSeconds = 3600;

        if (voskModel == null) voskModel = "";
        if (!"zipformer".equals(speechEngine) && !"vosk".equals(speechEngine)) speechEngine = "zipformer";
        speechThreads = Math.max(1, Math.min(4, speechThreads));
        if (!Float.isFinite(speechEndpointSilenceSeconds)) speechEndpointSilenceSeconds = .6f;
        speechEndpointSilenceSeconds = Math.max(.35f, Math.min(1.5f, speechEndpointSilenceSeconds));
        if (!Float.isFinite(speechMaxUtteranceSeconds)) speechMaxUtteranceSeconds = 10f;
        speechMaxUtteranceSeconds = Math.max(2f, Math.min(15f, speechMaxUtteranceSeconds));
        if (speechModelDir == null) speechModelDir = "";
        if (speechPostModelDir == null) speechPostModelDir = "";
        hudTranscriptSeconds = Float.isFinite(hudTranscriptSeconds)?Math.max(2f,Math.min(15f,hudTranscriptSeconds)):6f;
        hudTranscriptLines = Math.max(1,Math.min(6,hudTranscriptLines));
        hudWordWidth = Math.max(120,Math.min(600,hudWordWidth));
        hudWordLines = Math.max(2,Math.min(12,hudWordLines));
        hudHotbarWidth = Math.max(160,Math.min(800,hudHotbarWidth));
        hudHotbarSeconds = Float.isFinite(hudHotbarSeconds)?Math.max(2f,Math.min(15f,hudHotbarSeconds)):5f;
        hudHotbarBottom = Math.max(40,Math.min(180,hudHotbarBottom));
        hudBoardX = Float.isFinite(hudBoardX)?Math.max(0f,Math.min(1f,hudBoardX)):1f;
        hudBoardY = Float.isFinite(hudBoardY)?Math.max(0f,Math.min(1f,hudBoardY)):.5f;
        hudPanelOpacity = Float.isFinite(hudPanelOpacity)?Math.max(0f,Math.min(.85f,hudPanelOpacity)):.4f;

        if (webPort < 1024 || webPort > 65535) webPort = 38471;
        if (smartPickChance < 0) smartPickChance = 0;
        if (smartPickChance > 100) smartPickChance = 100;
        if (statsTopN < 3) statsTopN = 3;
        if (statsTopN > 50) statsTopN = 50;

        if (wordsPerRound < 1) wordsPerRound = 1;
        if (wordsPerRound > 20) wordsPerRound = 20;
        if (highFreqPerRound < 0) highFreqPerRound = 0;
        if (highFreqPerRound > wordsPerRound) highFreqPerRound = wordsPerRound;
        if (namesPerRound < 0) namesPerRound = 0;
        if (namesPerRound > wordsPerRound) namesPerRound = wordsPerRound;

        if (nameWords == null) nameWords = new ArrayList<>();
        nameWords.removeIf(w -> w == null || w.isBlank());
        if (highFreqExtra == null) highFreqExtra = new ArrayList<>();
        highFreqExtra.removeIf(w -> w == null || w.isBlank());
        if (highFreqDisabled == null) highFreqDisabled = new ArrayList<>();
        highFreqDisabled.removeIf(w -> w == null || w.isBlank());
        if (aliases == null) aliases = new LinkedHashMap<>();
        deathMessageStyle = com.example.voicekill.DeathMessages.Style.from(deathMessageStyle).id;
        deathMessages = new ArrayList<>(com.example.voicekill.DeathMessages.sanitize(deathMessages));
    }

    /** 加一个自定义危险词（去重、去空白）。返回是否真的加进去了 */
    public boolean addDangerWord(String word) {
        if (word == null) return false;
        String trimmed = word.trim();
        if (trimmed.isEmpty()) return false;
        for (String existing : dangerWords) {
            if (existing.equalsIgnoreCase(trimmed)) return false;
        }
        dangerWords.add(trimmed);
        save();
        return true;
    }

    /** 删掉一个自定义危险词。返回是否真的删掉了 */
    public boolean removeDangerWord(String word) {
        if (word == null) return false;
        String trimmed = word.trim();
        boolean removed = dangerWords.removeIf(w -> w.equalsIgnoreCase(trimmed));
        if (removed) save();
        return removed;
    }
}
