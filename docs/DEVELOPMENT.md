# VoiceKill — 开发交接包

> 合并说明（2026-10-19）：本文来自原始开发包。代码与用户仓库一致，已合并为一个项目。
> 当前结构与源码核对结果见 [ARCHITECTURE.md](ARCHITECTURE.md)。作者的历史实测在本工作区尚未复验。
> 本文描述 1.0.14；1.0.15-dev.1 已改为独立 Vosk 识别器，并修复候选/断句处理，详见 [修复与验收说明](FIXES_AND_VALIDATION.md)。
> 本仓库未启用 `game_mods_dir`；原始开发包中的个人路径仅保存在 `.local/archives/`。

> Minecraft 1.21.11 / Fabric 语音「危险词」模组。当前版本 **1.0.14**。
>
> 这个包比 GitHub 版多两样东西：**踩坑清单** 和 **Shriek 反编译速查**
> （当初为了搞清楚 Shriek 的接口，拆了它的字节码；这里把结论都留下了，不用再拆一遍）。

---

## 1. 先跑起来（约 10 分钟）

### 需要的东西

| 项 | 版本 | 备注 |
| --- | --- | --- |
| JDK | **21** | 编辑 `JAVA_HOME` 后再跑 gradlew |
| Minecraft | **1.21.11** | `gradle.properties` 里已固定 |
| Fabric Loader | 0.19.5（开发环境） | 运行时最低 0.17.3，被 Fabric API 卡着 |
| 运行期 4 个模组 | voicekill / fabric-api / shriek / architectury | Shriek 是**双端**模组 |

Shriek、Architectury、Fabric API 由 Loom 自动从 Modrinth 拉取，**不需要手动装进 `mods`**
（`gradlew runClient/runServer` 会自动处理）。

### 三条命令

```bash
# 1. 编译
./gradlew build

# 2. 跑客户端（会开一个真的 Minecraft 窗口）
./gradlew runClient

# 3. 跑服务端（网页控制台在 http://127.0.0.1:38471/）
./gradlew runServer
```

`build` 结束后会顺带执行 `deployToGame`，把 jar 复制到 `gradle.properties` 里的 `game_mods_dir`。
**不想部署就加 `-x deployToGame`**，或者把那行路径删掉（任务会自动跳过）。

### 语音模型（不装就听不见人说话）

Shriek 从 `<游戏目录>/shriek/vosk/<模型名>/` 读模型。开发环境对应 `run/shriek/vosk/`。

```bash
# 手动准备（约 42MB）
curl -LO https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
unzip vosk-model-small-cn-0.22.zip -d run/shriek/vosk/
```

不手动放也行，首次启动会后台自动下载（`VoiceKill-ModelPrepare` 线程，不卡游戏）。

---

## 2. 代码地图

```
src/main/java/com/example/voicekill/
├── VoiceKillMod.java              入口：装配配置、注册事件、起网页、注册 S2C 通道
├── DangerWordManager.java         核心：组词、轮换、智能选词、匹配、快照
├── SpeechHandler.java             语音文本 → 统计 → 判定 → kill（含跨句拼接）
├── DeathBoard.java                言多必失榜（内存态）
├── command/VoiceKillCommand.java  /voicekill start|stop|status
├── config/ModConfig.java          JSON 配置读写（Gson）
├── speech/SpeechStats.java        中文 n-gram 分词 + 词频（周期 / 累计）
├── wordbank/WordBank.java         词库：口语 / 正式 / 高频 / 人名
├── util/
│   ├── TextUtil.java              归一化（小写 + 去全部空白）★ 匹配和统计共用
│   ├── WordAliases.java           别名生成：字母读音、同音字、英文夹中文
│   └── FuzzyMatch.java            编辑距离（等长滑窗）
├── network/HudStatePayload.java   S2C 自定义包（本轮词/倒计时/榜单）
├── web/WebConsole.java            JDK HttpServer，零依赖
├── client/
│   ├── VoiceKillClient.java       模型准备、F8 键位、HUD 注册、开机自动接管录音
│   ├── MicCapture.java            ★ 自己接管录音 + 多候选识别（反射调 org.vosk）
│   ├── MicScreen.java             F8 界面
│   ├── AudioDevices.java          设备枚举 + 设备名同步
│   └── HudOverlay.java            四角渲染（HudElementRegistry）
└── mixin/                         目前是空壳（见「踩坑」第 3 条）
```

| 想改什么 | 改哪里 |
| --- | --- |
| 加/删词库词条 | `wordbank/WordBank.java`（高频词在 `HIGH_FREQ`，口语在 `SLANG`） |
| 换组词规则（每轮几个、配额） | `DangerWordManager.buildRound` |
| 匹配更严 / 更松 | `DangerWordManager.match` + `util/FuzzyMatch` |
| 改网页 | `resources/voicekill/console.html`（单文件，内联 CSS/JS） |
| 改 HUD 布局 | `client/HudOverlay.java` |
| 换识别引擎 | 只需要替换 `client/MicCapture.loop` 里的「读音频 → 出文本」这一段 |

---

## 3. 数据流（一句话版）

```
麦克风 → MicCapture（客户端） → Vosk 识别 → PlayerSpeakPacket（Shriek 的通道）
      → 服务端 ServerPlayerTalkEvent → SpeechHandler（统计 + 判定）
      → 命中 → player.kill() + 聊天广播 + 记榜单
      ↘ 每秒把状态用 HudStatePayload 推给所有客户端 → HudOverlay 画四角
```

**判定永远在服务端**：客户端只负责「录音 + 识别 + 把文本发上去」，改客户端免不了死。

---

## 4. 三条硬规则（违反会炸，全是实测撞出来的）

### ① 渲染线程上不许做任何可能阻塞的事

`ClientTickEvents` 跑在渲染线程。1.0.0 把「下载 42MB 模型」放在了 tick 里，
结果游戏冻结 8 分钟，表现为**启动直接卡死**。

现在：下载/解压走 `VoiceKill-ModelPrepare` 后台线程，渲染线程只做最后一步
`EventHandler.loadVoskModel()`。

### ② 不要 `resetListener()` 之后再自己 `loadVoskModel()`

Shriek 的 `resetListener()` 只做 `interrupt()` + 把字段置空，**不等线程结束**。
紧接着重建识别器 → 两个线程喂同一个 `org.vosk.Recognizer` → 触发原生断言：

```
ASSERTION_FAILED (VoskAPI): (!iter.Done() && oarc != NULL)
> Task :runClient FAILED        ← 整个 JVM 直接死
```

而且它**停了不会自己重连**（日志里 `listenThread` 永远是 null），所以「等它重连」也不可行。

**正确做法**：自己开录音线程（`MicCapture`），线程归自己管，换设备就是关自己的线程再开。

### ③ Mixin 改 Shriek 的取设备那一行，在正式环境不稳定

同一个 jar，dev 环境正常，正式环境时好时坏：

```
Failed to initialize microphone: Mixin transformation of com.pryzmm.speech.MicrophoneHandler failed
```

一旦失败，**整个语音功能全废**（异常被 Shriek 自己吞了，只留一行 message）。
现在的思路是根本不碰它的类：`MicCapture` 用公开入口自己搭管线。

> `voicekill.mixins.json` 里 `client` 数组是空的；`mixin/` 下两个类留着当历史记录，
> 想再试可以，但**别在没实机验证的情况下发版**。

---

## 5. 识别管线的几个关键点

| 点 | 做法 |
| --- | --- |
| 拿识别器 | 反射取 `EventHandler.speechRecognizer`（`SpeechRecognizer` 实例） |
| 多候选 | 反射拿它 public 字段 `recognizer`（`org.vosk.Recognizer`），调 `setMaxAlternatives(3)` |
| 出文本 | `acceptWaveForm(byte[], int)` → `getResult()` → 解析 JSON 的 `alternatives[].text` |
| 退化 | 任一步失败就退回 `SpeechRecognizer.getStringMsg(byte[])` 单结果 |
| 发包 | `ShriekPackets.sendToServer(new PlayerSpeakPacket(text))` |
| 多候选怎么传 | 用 `\u0001` 把候选串在同一个包里；服务端第 1 段进统计，**全部参与匹配** |
| 词被切成两截 | 服务端每人保留上一句最后 8 字，5 秒内拼上再匹配 |
| 停 Shriek 麦克风 | `resetListener()` + 反射取消 `retryTimer`（否则它每 10 秒回来抢设备） |

细节见 `docs/shriek-api.md`。

---

## 6. 怎么在没有麦克风的情况下测

`runServer` 起来后有一组本机 HTTP 接口：

```python
# 假装有人说了一句（不需要麦）
POST http://127.0.0.1:38471/api/debug/speech   {"text":"苦 力 怕"}

# 看当前状态（本轮词、榜单、词频、别名表）
GET  http://127.0.0.1:38471/api/state

# 指定本轮词（测匹配最方便）
POST http://127.0.0.1:38471/api/words/set      {"words":["秒切","p老板"]}

# 开始 / 停止
POST http://127.0.0.1:38471/api/start
POST http://127.0.0.1:38471/api/stop
```

多候选可以直接这样模拟（`\u0001` 是分隔符）：

```python
{"text": "秒 且\u0001秒 切"}   # 最优结果错、第二候选对 → 应该命中「秒切」
```

> ⚠️ 测之前先 `netstat -ano | grep 38471` 确认端口没被上一个没退干净的 runServer 占着，
> 否则新服务端的 WebConsole 会 `BindException`，而你调到的还是**旧进程的旧代码**——
> 这个坑让排查浪费过半小时。

---

## 7. 1.21.11 上踩过的 API 变更（都是编译报错才知道的）

| 以前 | 1.21.11 |
| --- | --- |
| `net.minecraft.resources.ResourceLocation` | **`net.minecraft.resources.Identifier`**（官方改名） |
| `new KeyMapping(..., String category)` | 第四参改成 `KeyMapping.Category` 对象（`Category.register(Identifier)`） |
| `CommandSourceStack.hasPermission(int)` | **不存在了**，改 Permission 对象；本项目干脆不做权限限制 |
| `player.kill()` | `player.kill(ServerLevel)` |

---

## 8. 当前状态与待办

**能用**：选词、匹配、HUD、网页、F8 换麦（含开机自动沿用）、言多必失榜、多候选、跨句拼接。

**已知短板**：

1. **中英混说** —— Vosk 没有双语模型，现在靠「字母读音变体」硬救（`p老板` → 屁老板）。
   要真解决得换引擎（SenseVoice + sherpa-onnx，单模型中/英/日/韩/粤，229MB int8），
   但那要重写识别管线（非流式，需要 VAD 切句），是一个 v2 的工程量。
2. **中文准确率** —— 小模型 CER 23.5%。换 `vosk-model-cn-0.22`（1.3GB）可到 14%，
   代价是内存（官方建议 16GB 预算）和十几秒的加载卡顿。
3. **`config.audioDevice` 只做子串匹配**，同名设备可能选错。

---

## 9. 文档索引

| 文件 | 内容 |
| --- | --- |
| [详细说明](详细说明.md) | 原来那份很长的 README：功能细节、每版改了什么、历次事故复盘 |
| [Shriek 接口速查](shriek-api.md) | **Shriek 的接口速查**（反编译结论），含重新提取的脚本 |
| [inspect-shriek.ps1](../tools/inspect-shriek.ps1) | 一键 dump Shriek 关键类的签名，不用装反编译器 |
