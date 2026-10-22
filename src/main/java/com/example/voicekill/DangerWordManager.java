package com.example.voicekill;

import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import com.example.voicekill.speech.SpeechStats;
import com.example.voicekill.speech.SpeechStats.WordCount;
import com.example.voicekill.util.TextUtil;
import com.example.voicekill.util.WordAliases;
import com.example.voicekill.wordbank.WordBank;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 危险词管理器（每轮一组词）。
 *
 * 线程模型（重要）：
 * - 内部状态只由「服务端逻辑线程」读写（tick 回调、语音事件、网页排队的任务）
 * - 每秒生成一份不可变快照（Map），网页线程只读快照
 * - 网页的写操作全部通过 {@link #onServer} 排队到服务端线程，绝不直接改状态
 */
public final class DangerWordManager {

    public static final int TICKS_PER_SECOND = 20;
    private static final int HISTORY_LIMIT = 20;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Random RANDOM = new Random();

    /** 一个危险词 + 它是怎么被选出来的（smart / highfreq / random / manual / name）+ 谐音变体 */
    public record WordSlot(String word, String source, List<String> aliases) {
    }

    private final ModConfig config;
    private final SpeechStats stats;
    private final DeathBoard deathBoard = new DeathBoard();

    // ---------------- 仅服务端线程访问 ----------------
    private List<WordSlot> currentWords = List.of();
    private List<String> nextWords = List.of();
    private int ticksUntilRotate;
    private int ticksElapsed;
    private final List<Map<String, Object>> history = new ArrayList<>();
    private MinecraftServer lastServer;
    private String recognitionSession=java.util.UUID.randomUUID().toString();
    private String recognitionRound;
    private final com.example.voicekill.speech.RecognitionSilence recognitionSilence=new com.example.voicekill.speech.RecognitionSilence();
    private final com.example.voicekill.speech.RecognitionHealth recognitionHealth=new com.example.voicekill.speech.RecognitionHealth();
    private long silenceTick;

    /** 是否已经 /voicekill start 开始游戏 */
    private boolean running = false;

    // ---------------- 跨线程可见 ----------------
    private volatile Map<String, Object> snapshot = Map.of();
    private final com.example.voicekill.speech.SpeechWindow previewWindow = new com.example.voicekill.speech.SpeechWindow();
    private Map<String, Object> speechPreview = Map.of();

    private void updateWords(List<WordSlot> words) {
        currentWords = List.copyOf(words);
        recognitionRound=SpeechHandler.changeWords(matchingWords());
        previewWindow.clear();
        speechPreview = Map.of();
    }

    public DangerWordManager(ModConfig config, SpeechStats stats) {
        this.config = config;
        this.stats = stats;
        updateWords(buildRound(null, null));
        this.ticksUntilRotate = intervalTicks();
        VoiceKillMod.LOGGER.info("[VoiceKill] 初始危险词：{}（每 {} 秒轮换一次）",
                renderWords(currentWords), config.intervalSeconds);
    }

    public SpeechStats stats() {
        return stats;
    }

    public List<WordSlot> getCurrentWords() {
        return currentWords;
    }

    /** 服务端启动时调用：先把 server 引用接上，并立刻发布一份初始快照给网页 */
    public void attach(MinecraftServer server) {
        this.lastServer = server;
        recognitionSession=java.util.UUID.randomUUID().toString();
        recognitionHealth.reset(recognitionSession);silenceTick=0;
        publishSnapshot(server);
    }

    private int intervalTicks() {
        return Math.max(1, config.intervalSeconds) * TICKS_PER_SECOND;
    }

    // ==================================================================
    // tick / 轮换
    // ==================================================================

    public void onServerTick(MinecraftServer server) {
        this.lastServer = server;

        // 没开始游戏：只维持网页/四角的状态显示，不计时、不轮换
        if (!running) {
            tickRecognitionSilence(server);
            if (server.getTickCount() % TICKS_PER_SECOND == 0) {
                publishSnapshot(server);
            }
            return;
        }

        boolean hasPlayers = !server.getPlayerList().getPlayers().isEmpty();
        if (!hasPlayers) {
            this.ticksUntilRotate = intervalTicks();
            this.ticksElapsed = 0;
        } else {
            this.ticksElapsed++;
            if (--this.ticksUntilRotate <= 0) {
                rotate(server, null);
            }
        }

        tickRecognitionSilence(server);

        if (server.getTickCount() % TICKS_PER_SECOND == 0) {
            publishSnapshot(server);
        }
    }

    public boolean isRunning() {
        return running;
    }
    public com.example.voicekill.speech.PhoneticMatcher.Settings phoneticSettings(){return config.phoneticSettings();}
    public boolean logSpeech(){return config.logSpeech;}

    /** /voicekill start：清空本周期统计，重新组一组词，开始计时 */
    public void start(MinecraftServer server) {
        SpeechHandler.reset();recognitionSession=java.util.UUID.randomUUID().toString();
        recognitionSilence.clear();recognitionHealth.bindSession(recognitionSession);
        this.running = true;
        this.nextWords = List.of();
        this.history.clear();
        stats.finishCycle();
        updateWords(buildRound(null, null));
        this.ticksUntilRotate = intervalTicks();
        this.ticksElapsed = 0;
        pushHistory(currentWords);
        VoiceKillMod.LOGGER.info("[VoiceKill] 游戏开始 → {}", renderWords(currentWords));
        if (config.enableActionbarHint) {
            broadcastActionBar(server, hintText());
        }
        publishSnapshot(server);
    }

    /** /voicekill stop：停表、停止判定（榜单保留） */
    public void stop(MinecraftServer server) {
        this.running = false;
        recognitionSilence.clear();
        SpeechHandler.reset();
        this.ticksElapsed = 0;
        VoiceKillMod.LOGGER.info("[VoiceKill] 游戏已停止（/voicekill start 重新开始）");
        publishSnapshot(server);
    }

    private static boolean silenceEligible(ServerPlayer player){return player!=null&&!player.hasDisconnected()&&!player.isDeadOrDying()&&!player.isCreative()&&!player.isSpectator();}
    private void resetRecognition(ServerPlayer player) {
        if(!running||!silenceEligible(player)||!canRecognize(player))return;
        recognitionSilence.configure(config.silenceRules());
        recognitionSilence.recognized(player.getUUID().toString(),silenceTick);
    }
    /** A partial-text activity notice is deliberately separate from danger-word judging. */
    public void recordRecognition(ServerPlayer player,String session){var server=player.level().getServer();if(server==null)return;if(!server.isSameThread()){server.execute(()->recordRecognition(player,session));return;}if(recognitionSession.equals(session))resetRecognition(player);}
    public void recordRecognizedText(ServerPlayer player,String text){if(com.example.voicekill.speech.RecognitionSilence.hasText(text))resetRecognition(player);}
    public boolean canRecognize(ServerPlayer player){return player==null||recognitionHealth.ready(player.getUUID().toString(),silenceTick);}
    public void recordRecognitionHealth(ServerPlayer player,String session,com.example.voicekill.speech.RecognitionHealth.Issue issue) {
        var server=player.level().getServer();if(server==null)return;
        if(!server.isSameThread()){server.execute(()->recordRecognitionHealth(player,session,issue));return;}
        if(player.hasDisconnected())return;
        String id=player.getUUID().toString();boolean wasReady=recognitionHealth.ready(id,silenceTick);
        if(!recognitionHealth.report(id,session,issue,silenceTick))return;
        if(!wasReady||issue!=com.example.voicekill.speech.RecognitionHealth.Issue.READY){recognitionSilence.forget(id);SpeechHandler.forget(player);}
    }
    public void forgetRecognition(ServerPlayer player){String id=player.getUUID().toString();recognitionSilence.forget(id);recognitionHealth.forget(id);}
    private void tickRecognitionSilence(MinecraftServer server) {
        recognitionSilence.configure(config.silenceRules());silenceTick++;
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            String id=player.getUUID().toString();boolean ready=canRecognize(player);
            if(!ready)SpeechHandler.forget(player);
            if(!player.isDeadOrDying()&&recognitionHealth.noticeDue(id,silenceTick))
                player.displayClientMessage(Component.literal("[VoiceKill] "+recognitionHealth.issue(id,silenceTick).warning()).withStyle(ChatFormatting.YELLOW),true);
            if(!recognitionSilence.step(id,silenceTick,running&&silenceEligible(player)&&ready))continue;
            float amount=recognitionSilence.rules().damageAmount();
            if(player.hurtServer(player.level(),SilenceDamage.source(player),amount)) {
                player.displayClientMessage(Component.literal("[VoiceKill] 未识别到新文字，扣除 "+amount+" 点生命").withStyle(ChatFormatting.RED),true);
                if(player.isDeadOrDying()) {recordDeath(player.getName().getString());SpeechHandler.forget(player);forgetRecognition(player);}
            }
        }
    }

    /** 把四角要显示的东西推给所有客户端 */
    private void broadcastHud(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        List<String> words = new ArrayList<>(currentWords.size());
        for (WordSlot slot : currentWords) {
            words.add(slot.word());
        }
        HudStatePayload payload = HudStatePayload.of(
                running,
                words,
                Math.max(0, ticksUntilRotate / TICKS_PER_SECOND),
                config.intervalSeconds,
                deathBoard.top(5),
                deathBoard.total());
        var lexicon=com.example.voicekill.network.RecognitionLexiconPayload.of(matchingWords(),recognitionSession,recognitionRound,running);
        for (ServerPlayer player : players) {
            try {
                ServerPlayNetworking.send(player, payload);
                if(ServerPlayNetworking.canSend(player,com.example.voicekill.network.RecognitionLexiconPayload.TYPE))ServerPlayNetworking.send(player,lexicon);
            } catch (Throwable ignored) {
                // 客户端没装本模组 / 通道没注册时不要炸 tick
            }
        }
    }

    /** 换一组词。forcedWords 非空则只用这些词（网页强制换词时用） */
    public void rotate(MinecraftServer server, List<String> forcedWords) {
        // 1) 上一个周期结束，冻结词频
        stats.finishCycle();
        Set<String> known = WordBank.knownWords(server, config);
        List<WordCount> lastCycle = stats.lastCycleTop(config.statsTopN, known);
        List<WordCount> cumulative = stats.cumulativeTop(Math.max(config.statsTopN, 20), known);
        // 上一轮 ×3 + 整局累计 ×1：最近聊的最重，但老词不会因为换了一轮就归零
        List<WordCount> candidates = mergeScores(lastCycle, cumulative);

        // 2) 组新的一组词
        List<String> manual = forcedWords != null ? forcedWords : nextWords;
        updateWords(buildRound(manual, candidates));
        this.nextWords = List.of();
        this.ticksUntilRotate = intervalTicks();
        this.ticksElapsed = 0;

        pushHistory(currentWords);
        VoiceKillMod.LOGGER.info("[VoiceKill] 换词 → {}", renderWords(currentWords));

        if (config.enableActionbarHint) {
            broadcastActionBar(server, hintText());
        }
        publishSnapshot(server);
    }

    /**
     * 组一组新词。优先级：手动指定 > 智能选词 > 高频地狱词配额 > 随机补满。
     *
     * @param manualWords 手动指定的词（可以为 null）
     * @param lastCycle   上一周期的高频词（可以为 null，那就不做智能选词）
     */
    private List<WordSlot> buildRound(List<String> manualWords, List<WordCount> lastCycle) {
        int target = Math.max(1, config.wordsPerRound);
        List<WordSlot> result = new ArrayList<>(target);
        Set<String> used = new HashSet<>();
        Set<String> knownWords = WordBank.knownWords(lastServer, config);

        // ---- 1. 手动指定 ----
        if (manualWords != null) {
            for (String word : manualWords) {
                if (result.size() >= target) break;
                addSlot(result, used, word, "manual");
            }
        }

        // ---- 2. 给「人名」和「高频词」留名额（先算好，避免被智能词占满）----
        int nameQuota = Math.min(config.namesPerRound, target - result.size());
        int highQuota = Math.min(config.highFreqPerRound, target - result.size() - nameQuota);
        int smartSlots = Math.max(0, target - result.size() - nameQuota - highQuota);

        // ---- 3. 人名（每轮至少 N 个，方便「谁的名字被念到谁死」）----
        List<String> namePool = WordBank.namePool(lastServer, config);
        for (int i = 0; i < nameQuota; i++) {
            String picked = pickRandom(namePool, used);
            if (picked == null) break;
            addSlot(result, used, picked, "name");
        }

        // ---- 4. 智能选词：从上一周期高频词里按权重挑，每个槽位独立判概率 ----
        if (config.smartPick && lastCycle != null && !lastCycle.isEmpty() && smartSlots > 0) {
            List<WordCount> pool = new ArrayList<>(lastCycle);
            for (int i = 0; i < smartSlots; i++) {
                if (RANDOM.nextInt(100) >= config.smartPickChance) {
                    break; // 概率没中，剩下的位置留给随机
                }
                String picked = pickWeighted(pool, used, knownWords);
                if (picked == null) {
                    break;
                }
                pool.removeIf(wc -> normalize(wc.word()).equals(normalize(picked)));
                addSlot(result, used, picked, "smart");
            }
        }

        // ---- 5. 高频地狱词（内置 − 禁用的 + 用户自己加的）----
        List<String> highFreqPool = WordBank.highFreqFor(config);
        for (int i = 0; i < highQuota; i++) {
            String picked = pickRandom(highFreqPool, used);
            if (picked == null) break;
            addSlot(result, used, picked, "highfreq");
        }

        // ---- 6. 剩下的用普通词库补满 ----
        List<String> bank = WordBank.all(lastServer, config);
        for (int i = 0; result.size() < target && i < 200; i++) {
            String picked = pickRandom(bank, used);
            if (picked == null) break;
            addSlot(result, used, picked, "random");
        }

        // 极端情况兜底（词库为空之类）
        if (result.isEmpty()) {
            result.add(new WordSlot("苦力怕", "random", List.of()));
        }
        return result;
    }

    /**
     * 合并两路词频：最近一轮 count×3，整局累计 count×1。
     * 这样「刚聊过的词」优先，但早期反复出现的词也会一直有存在感，不会只活一轮。
     */
    private List<WordCount> mergeScores(List<WordCount> recent, List<WordCount> cumulative) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        if (cumulative != null) {
            for (WordCount wc : cumulative) {
                String key = normalize(wc.word());
                merged.merge(key, wc.count(), Integer::sum);
                display.putIfAbsent(key, wc.word());
            }
        }
        if (recent != null) {
            for (WordCount wc : recent) {
                String key = normalize(wc.word());
                merged.merge(key, wc.count() * 3, Integer::sum);
                display.putIfAbsent(key, wc.word());
            }
        }
        List<WordCount> list = new ArrayList<>(merged.size());
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            list.add(new WordCount(display.get(e.getKey()), e.getValue()));
        }
        list.sort(Comparator.comparingInt(WordCount::count).reversed());
        return list;
    }

    /** 加一个词进组（自动去重、去空白），成功返回 true */
    private boolean addSlot(List<WordSlot> target, Set<String> used, String word, String source) {
        if (word == null) return false;
        String trimmed = word.trim();
        if (trimmed.isEmpty()) return false;
        if (!used.add(normalize(trimmed))) return false;
        // 谐音变体在这里算一次并缓存，匹配时不用反复生成
        target.add(new WordSlot(trimmed, source, WordAliases.variants(trimmed, config, !"highfreq".equals(source) && !"smart".equals(source))));
        return true;
    }

    /**
     * 按频次加权随机挑一个还没用过的词。
     *
     * 两条过滤规则：
     * - 词库认识的词：直接用
     * - 词库不认识的词（玩家临时聊出来的）：必须重复出现 2 次以上，否则大概率是
     *   n-gram 切出来的碎片（「天又被苦」「苦力怕炸」），拿它当危险词基本没人说得出来
     */
    private String pickWeighted(List<WordCount> pool, Set<String> used, Set<String> knownWords) {
        List<WordCount> available = new ArrayList<>();
        int total = 0;
        for (WordCount wc : pool) {
            String normalized = normalize(wc.word());
            if (used.contains(normalized)) continue;
            boolean known = knownWords != null && knownWords.contains(normalized);
            if (!known && wc.count() < 2) continue;
            available.add(wc);
            total += Math.max(1, wc.count()) * (known ? 2 : 1);
        }
        if (available.isEmpty() || total <= 0) {
            return null;
        }
        int roll = RANDOM.nextInt(total);
        for (WordCount wc : available) {
            boolean known = knownWords != null && knownWords.contains(normalize(wc.word()));
            roll -= Math.max(1, wc.count()) * (known ? 2 : 1);
            if (roll < 0) {
                return wc.word();
            }
        }
        return available.get(available.size() - 1).word();
    }

    /** 从候选列表里随机挑一个还没用过的 */
    private String pickRandom(List<String> candidates, Set<String> used) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            String candidate = candidates.get(RANDOM.nextInt(candidates.size()));
            if (!used.contains(normalize(candidate))) {
                return candidate;
            }
        }
        for (String candidate : candidates) {
            if (!used.contains(normalize(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (config.enableActionbarHint && config.hintOnJoin) {
            player.displayClientMessage(hintText(), true);
        }
    }

    // ==================================================================
    // 匹配
    // ==================================================================

    /**
     * 识别文本是否命中本轮任意一个危险词。
     *
     * @return 命中的那个词；没命中返回 null
     */
    public List<com.example.voicekill.speech.WordMatcher.Word> matchingWords() {
        Set<String> preferred = new HashSet<>();
        config.dangerWords.forEach(word -> preferred.add(normalize(word)));
        config.nameWords.forEach(word -> preferred.add(normalize(word)));
        return currentWords.stream().map(slot -> new com.example.voicekill.speech.WordMatcher.Word(
                slot.word(), slot.source(), slot.aliases(), preferred.contains(normalize(slot.word()))
                    || "manual".equals(slot.source()) || "name".equals(slot.source()))).toList();
    }

    public String match(String speechText) {
        if (!running) return null;
        var hit = com.example.voicekill.speech.PhoneticMatcher.find(matchingWords(),speechText,config.phoneticSettings());
        return hit == null ? null : hit.word();
    }

    /** 记一笔「言多必失」 */
    public void recordDeath(String playerName) {
        deathBoard.record(playerName);
    }

    public DeathBoard deathBoard() {
        return deathBoard;
    }

    /** 归一化：转小写 + 去掉一切空白字符（Vosk 中文输出常带空格） */
    public static String normalize(String input) {
        return TextUtil.normalize(input);
    }

    // ==================================================================
    // 网页控制台 API（可在任意线程调用）
    // ==================================================================

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    /** 整组替换当前危险词 */
    public void requestSetWords(List<String> words) {
        onServer(server -> {
            if (words == null || words.isEmpty()) {
                return;
            }
            List<String> cleaned = new ArrayList<>();
            for (String w : words) {
                if (w != null && !w.isBlank()) cleaned.add(w.trim());
            }
            if (cleaned.isEmpty()) return;

            List<WordSlot> slots = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (String w : cleaned) {
                if (slots.size() >= Math.max(1, config.wordsPerRound)) break;
                addSlot(slots, used, w, "manual");
            }
            if (slots.isEmpty()) return;

            updateWords(slots);
            this.ticksElapsed = 0;
            this.ticksUntilRotate = intervalTicks();
            pushHistory(currentWords);
            VoiceKillMod.LOGGER.info("[VoiceKill] 网页整组设定危险词 → {}", renderWords(currentWords));
            if (config.enableActionbarHint) {
                broadcastActionBar(server, hintText());
            }
            publishSnapshot(server);
        });
    }

    /** 往本轮里加一个词（组满了就不加） */
    public void requestAddCurrentWord(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            int limit = Math.max(1, config.wordsPerRound);
            if (currentWords.size() >= limit) {
                VoiceKillMod.LOGGER.info("[VoiceKill] 本轮已满 {} 个，不再添加", limit);
                return;
            }
            List<WordSlot> slots = new ArrayList<>(currentWords);
            Set<String> used = new HashSet<>();
            for (WordSlot s : slots) used.add(normalize(s.word()));
            if (addSlot(slots, used, word, "manual")) {
                updateWords(slots);
                VoiceKillMod.LOGGER.info("[VoiceKill] 本轮新增危险词：{}", word);
                if (config.enableActionbarHint) {
                    broadcastActionBar(server, hintText());
                }
            }
            publishSnapshot(server);
        });
    }

    /** 从本轮里删掉一个词 */
    public void requestRemoveCurrentWord(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            String target = normalize(word);
            List<WordSlot> slots = new ArrayList<>();
            boolean removed = false;
            for (WordSlot s : currentWords) {
                if (normalize(s.word()).equals(target)) {
                    removed = true;
                } else {
                    slots.add(s);
                }
            }
            if (removed && !slots.isEmpty()) {
                updateWords(slots);
                VoiceKillMod.LOGGER.info("[VoiceKill] 本轮移除危险词：{}", word);
                if (config.enableActionbarHint) {
                    broadcastActionBar(server, hintText());
                }
            }
            publishSnapshot(server);
        });
    }

    /** 指定下一轮的词（可以多个）；传空列表表示取消指定 */
    public void requestSetNext(List<String> words) {
        onServer(server -> {
            List<String> cleaned = new ArrayList<>();
            if (words != null) {
                for (String w : words) {
                    if (w != null && !w.isBlank()) cleaned.add(w.trim());
                }
            }
            this.nextWords = cleaned;
            publishSnapshot(server);
        });
    }

    public void requestReroll() {
        onServer(server -> rotate(server, null));
    }

    /** 网页上点「开始」 */
    public void requestStart() {
        onServer(this::start);
    }

    /** 网页上点「停止」 */
    public void requestStop() {
        onServer(this::stop);
    }

    /** 高频词：加一个（写进 highFreqExtra） */
    public void requestAddHighFreq(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            String trimmed = word.trim();
            // 如果之前被禁用过，先解除禁用
            boolean wasDisabled = config.highFreqDisabled.removeIf(w -> w.equalsIgnoreCase(trimmed));
            boolean exists = WordBank.highFreqFor(config).stream()
                    .anyMatch(w -> w.equalsIgnoreCase(trimmed));
            if (!exists) {
                config.highFreqExtra.add(trimmed);
            }
            if (wasDisabled || !exists) {
                config.save();
                VoiceKillMod.LOGGER.info("[VoiceKill] 高频词新增/启用：{}", trimmed);
            }
            publishSnapshot(server);
        });
    }

    /** 高频词：删一个（内置的写进 highFreqDisabled，自己加的从 extra 移除） */
    public void requestRemoveHighFreq(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            String trimmed = word.trim();
            boolean builtin = WordBank.highFreq().stream()
                    .anyMatch(w -> w.equalsIgnoreCase(trimmed));
            if (builtin) {
                if (!config.highFreqDisabled.contains(trimmed)) {
                    config.highFreqDisabled.add(trimmed);
                }
            } else {
                config.highFreqExtra.removeIf(w -> w.equalsIgnoreCase(trimmed));
            }
            config.save();
            VoiceKillMod.LOGGER.info("[VoiceKill] 高频词移除：{}", trimmed);
            publishSnapshot(server);
        });
    }

    /**
     * 测试用：把一段文本当成「某个在线玩家说出来的」灌进完整管线（统计 + 判定 + 处决）。
     * 没有玩家在线时只做统计，用来验证智能选词是否工作。
     */
    /** 安全的匹配预览：不统计词频、不选择在线玩家、不处决。 */
    public void requestPreviewSpeech(String text) {
        onServer(server -> {
            var packet = com.example.voicekill.speech.SpeechPacket.parse(text);
            var result = previewWindow.acceptFinal("preview", packet, matchingWords(), System.currentTimeMillis(),config.phoneticSettings());
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("text", text == null ? "" : text);
            preview.put("matched", result.hit() != null);
            if (result.hit() != null) {
                preview.put("word", result.hit().word());
                preview.put("mode", result.hit().mode());
                preview.put("evidence", result.hit().evidence());
                preview.put("candidate", result.candidate() + 1);
            }
            speechPreview = preview;
            publishSnapshot(server);
        });
    }

    public void requestSimulateSpeech(String text) {
        onServer(server -> {
            ServerPlayer target = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            SpeechHandler.handleText(this, target, text);
            publishSnapshot(server);
        });
    }

    public void requestAddWord(String word) {
        onServer(server -> {
            if (config.addDangerWord(word)) {
                VoiceKillMod.LOGGER.info("[VoiceKill] 网页添加自定义危险词：{}", word);
            }
            publishSnapshot(server);
        });
    }

    public void requestRemoveWord(String word) {
        onServer(server -> {
            if (config.removeDangerWord(word)) {
                VoiceKillMod.LOGGER.info("[VoiceKill] 网页移除自定义危险词：{}", word);
            }
            publishSnapshot(server);
        });
    }

    /** 加一个人名到「人名」分类 */
    public void requestAddNameWord(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            String trimmed = word.trim();
            boolean exists = config.nameWords.stream().anyMatch(w -> w.equalsIgnoreCase(trimmed));
            if (!exists) {
                config.nameWords.add(trimmed);
                config.save();
                VoiceKillMod.LOGGER.info("[VoiceKill] 新增人名词：{}", trimmed);
            }
            publishSnapshot(server);
        });
    }

    public void requestRemoveNameWord(String word) {
        onServer(server -> {
            if (word == null || word.isBlank()) return;
            if (config.nameWords.removeIf(w -> w.equalsIgnoreCase(word.trim()))) {
                config.save();
                VoiceKillMod.LOGGER.info("[VoiceKill] 移除人名词：{}", word);
            }
            publishSnapshot(server);
        });
    }

    /** 给某个词加一个谐音别名（逗号/空格分隔可以一次加多个） */
    public void requestAddAliases(String word, List<String> aliases) {
        onServer(server -> {
            if (word == null || word.isBlank() || aliases == null || aliases.isEmpty()) return;
            String key = word.trim();
            List<String> list = config.aliases.computeIfAbsent(key, k -> new ArrayList<>());
            for (String alias : aliases) {
                if (alias == null || alias.isBlank()) continue;
                String trimmed = alias.trim();
                if (!list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
            config.save();
            VoiceKillMod.LOGGER.info("[VoiceKill] {} 的谐音别名：{}", key, list);
            // 别名改了要重建当前这组词，才能立刻生效
            rebuildCurrentAliases();
            publishSnapshot(server);
        });
    }

    public void requestRemoveAlias(String word, String alias) {
        onServer(server -> {
            if (word == null || alias == null) return;
            List<String> list = config.aliases.get(word.trim());
            if (list != null) {
                list.removeIf(a -> a.equalsIgnoreCase(alias.trim()));
                if (list.isEmpty()) {
                    config.aliases.remove(word.trim());
                }
                config.save();
                rebuildCurrentAliases();
            }
            publishSnapshot(server);
        });
    }

    /** 清零言多必失榜 */
    public void requestResetBoard() {
        onServer(server -> {
            deathBoard.reset();
            VoiceKillMod.LOGGER.info("[VoiceKill] 言多必失榜已清零");
            publishSnapshot(server);
        });
    }

    private void rebuildCurrentAliases() {
        List<WordSlot> rebuilt = new ArrayList<>(currentWords.size());
        Set<String> used = new HashSet<>();
        for (WordSlot slot : currentWords) {
            addSlot(rebuilt, used, slot.word(), slot.source());
        }
        updateWords(rebuilt);
    }

    /** 网页改配置；参数都可以为 null（表示这一项不动） */
    public void requestUpdateConfig(Integer intervalSeconds, Boolean smartPick, Integer smartPickChance,
                                    Integer wordsPerRound, Integer highFreqPerRound, Integer namesPerRound) {
        requestUpdateConfig(intervalSeconds,smartPick,smartPickChance,wordsPerRound,highFreqPerRound,namesPerRound,null,null,null,null);
    }
    public void requestUpdateConfig(Integer intervalSeconds,Boolean smartPick,Integer smartPickChance,Integer wordsPerRound,Integer highFreqPerRound,Integer namesPerRound,
                                    Boolean silenceEnabled,Float silenceTimeout,Float silenceDamage,Float silenceInterval) {
        requestUpdateConfig(intervalSeconds,smartPick,smartPickChance,wordsPerRound,highFreqPerRound,namesPerRound,silenceEnabled,silenceTimeout,silenceDamage,silenceInterval,null,null);
    }
    public void requestUpdateConfig(Integer intervalSeconds,Boolean smartPick,Integer smartPickChance,Integer wordsPerRound,Integer highFreqPerRound,Integer namesPerRound,
                                    Boolean silenceEnabled,Float silenceTimeout,Float silenceDamage,Float silenceInterval,Boolean phoneticEnabled,Float phoneticThreshold) {
        onServer(server -> {
            boolean intervalChanged = false;
            if (intervalSeconds != null) {
                int clamped = Math.max(5, Math.min(3600, intervalSeconds));
                if (clamped != config.intervalSeconds) {
                    config.intervalSeconds = clamped;
                    intervalChanged = true;
                }
            }
            if (smartPick != null) {
                config.smartPick = smartPick;
            }
            if (smartPickChance != null) {
                config.smartPickChance = Math.max(0, Math.min(100, smartPickChance));
            }
            if (wordsPerRound != null) {
                config.wordsPerRound = Math.max(1, Math.min(20, wordsPerRound));
            }
            if (highFreqPerRound != null) {
                config.highFreqPerRound = Math.max(0, Math.min(config.wordsPerRound, highFreqPerRound));
            }
            if (namesPerRound != null) {
                config.namesPerRound = Math.max(0, Math.min(config.wordsPerRound, namesPerRound));
            }
            config.applySilenceRules(new com.example.voicekill.speech.RecognitionSilence.Rules(
                    silenceEnabled==null?config.silencePenaltyEnabled:silenceEnabled,
                    silenceTimeout==null?config.silenceTimeoutSeconds:silenceTimeout,
                    silenceDamage==null?config.silenceDamageAmount:silenceDamage,
                    silenceInterval==null?config.silenceDamageIntervalSeconds:silenceInterval));
            recognitionSilence.configure(config.silenceRules());
            if(phoneticEnabled!=null)config.phoneticMatching=phoneticEnabled;
            if(phoneticThreshold!=null)config.phoneticThreshold=new com.example.voicekill.speech.PhoneticMatcher.Settings(config.phoneticMatching,phoneticThreshold).threshold();
            config.save();

            if (intervalChanged) {
                int oldInterval = Math.max(1, ticksUntilRotate + ticksElapsed);
                double passed = (double) ticksElapsed / oldInterval;
                this.ticksUntilRotate = Math.max(1, (int) Math.round(intervalTicks() * (1 - passed)));
            }
            publishSnapshot(server);
        });
    }

    /** 把任务丢到服务端逻辑线程执行 */
    private void onServer(Consumer<MinecraftServer> action) {
        MinecraftServer server = this.lastServer;
        if (server == null) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 服务端还没就绪，网页指令被忽略");
            return;
        }
        if (server.isSameThread()) {
            action.accept(server);
        } else {
            server.execute(() -> {
                try {
                    action.accept(server);
                } catch (Throwable t) {
                    VoiceKillMod.LOGGER.error("[VoiceKill] 网页指令执行失败", t);
                }
            });
        }
    }

    // ==================================================================
    // 快照
    // ==================================================================

    private void publishSnapshot(MinecraftServer server) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running);
        map.put("speechPreview", speechPreview);
        map.put("currentWords", slotsToList(currentWords));
        map.put("nextWords", new ArrayList<>(nextWords));
        map.put("wordsPerRound", config.wordsPerRound);
        map.put("highFreqPerRound", config.highFreqPerRound);
        map.put("intervalSeconds", config.intervalSeconds);
        map.put("silencePenaltyEnabled",config.silencePenaltyEnabled);
        map.put("phoneticMatching",config.phoneticMatching);
        map.put("phoneticThreshold",config.phoneticThreshold);
        map.put("silenceTimeoutSeconds",config.silenceTimeoutSeconds);
        map.put("silenceDamageAmount",config.silenceDamageAmount);
        map.put("silenceDamageIntervalSeconds",config.silenceDamageIntervalSeconds);
        map.put("remainingSeconds", Math.max(0, ticksUntilRotate / TICKS_PER_SECOND));
        map.put("elapsedSeconds", ticksElapsed / TICKS_PER_SECOND);

        map.put("smartPick", config.smartPick);
        map.put("smartPickChance", config.smartPickChance);
        map.put("statsTopN", config.statsTopN);
        map.put("usePresetWords", config.usePresetWords);

        map.put("customWords", WordBank.custom(config));
        map.put("slangWords", WordBank.slang());
        map.put("formalWords", WordBank.formal());
        map.put("presetWords", WordBank.presetFor(config));
        map.put("highFreqWords", WordBank.highFreqFor(config));
        map.put("highFreqBuiltin", WordBank.highFreq());
        map.put("highFreqExtra", config.highFreqExtra);
        map.put("playerNames", WordBank.playerNames(server));
        map.put("poolSize", WordBank.all(server, config).size());

        map.put("stats", toList(stats.currentTop(config.statsTopN,
                WordBank.knownWords(server, config))));
        map.put("lastCycle", toList(stats.lastCycleTop(config.statsTopN,
                WordBank.knownWords(server, config))));
        map.put("cumulative", toList(stats.cumulativeTop(config.statsTopN,
                WordBank.knownWords(server, config))));
        map.put("utterances", stats.utterances());
        map.put("history", new ArrayList<>(history));

        // 人名池 / 别名 / 言多必失榜
        map.put("nameWords", config.nameWords);
        map.put("namesPerRound", config.namesPerRound);
        map.put("namePool", WordBank.namePool(server, config));
        map.put("aliases", config.aliases);
        map.put("aliasesOf", aliasesOfCurrentRound());
        List<Map<String, Object>> board = new ArrayList<>();
        for (DeathBoard.Entry entry : deathBoard.top(10)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name());
            item.put("count", entry.count());
            board.add(item);
        }
        map.put("board", board);
        map.put("totalDeaths", deathBoard.total());

        // 麦克风 Mixin 应急开关状态
        boolean mixinOff = false;
        try {
            mixinOff = java.nio.file.Files.isRegularFile(
                    net.fabricmc.loader.api.FabricLoader.getInstance()
                            .getConfigDir().resolve("voicekill-mic-mixin.off"));
        } catch (Throwable ignored) {
        }
        map.put("micMixinOff", mixinOff);

        this.snapshot = map;

        // 顺手把四角要显示的内容推给客户端
        broadcastHud(server);
    }

    /** 本轮每个词的谐音变体，网页上用来展示「念这些也算中」 */
    private Map<String, Object> aliasesOfCurrentRound() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (WordSlot slot : currentWords) {
            if (slot.aliases() != null && !slot.aliases().isEmpty()) {
                map.put(slot.word(), slot.aliases());
            }
        }
        return map;
    }

    private static List<Map<String, Object>> slotsToList(List<WordSlot> slots) {
        List<Map<String, Object>> list = new ArrayList<>(slots.size());
        for (WordSlot slot : slots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", slot.word());
            item.put("source", slot.source());
            list.add(item);
        }
        return list;
    }

    private static List<Map<String, Object>> toList(List<WordCount> counts) {
        List<Map<String, Object>> list = new ArrayList<>(counts.size());
        for (WordCount wc : counts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", wc.word());
            item.put("count", wc.count());
            list.add(item);
        }
        return list;
    }

    private void pushHistory(List<WordSlot> slots) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalTime.now().format(TIME_FMT));
        List<String> words = new ArrayList<>(slots.size());
        Set<String> sources = new LinkedHashSet<>();
        for (WordSlot slot : slots) {
            words.add(slot.word());
            sources.add(slot.source());
        }
        entry.put("words", words);
        entry.put("sources", new ArrayList<>(sources));
        history.add(0, entry);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
    }

    private static String renderWords(List<WordSlot> slots) {
        StringBuilder sb = new StringBuilder();
        for (WordSlot slot : slots) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(slot.word());
        }
        return sb.toString();
    }

    // ==================================================================
    // 提示
    // ==================================================================

    private Component hintText() {
        MutableComponent text = Component.literal("本轮危险词：").withStyle(ChatFormatting.GRAY);
        boolean first = true;
        for (WordSlot slot : currentWords) {
            if (!first) {
                text.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            text.append(Component.literal(slot.word())
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            first = false;
        }
        return text;
    }

    private void broadcastActionBar(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(message, true);
        }
    }
}
