# 项目结构与运行机制

依据 2026-10-19 的 1.0.15-dev.5 源码；历史交接资料来自 1.0.14。
实际修复和验证边界见 [修复与验收说明](FIXES_AND_VALIDATION.md)。

## 目录约定

```text
VoiceKill/
├── README.md                 用户入口
├── LICENSE                   MIT 许可
├── build.gradle              Fabric Loom 构建、JUnit 与可选部署
├── gradle.properties         依赖/候选版本；个人部署路径默认不启用
├── gradlew / gradlew.bat      Gradle Wrapper 入口
├── gradle/wrapper/            Wrapper 配置与 JAR（纳入 Git）
├── src/main/java/            34 个 Java 源文件
├── src/main/resources/       模组元数据、语言、Mixin 配置、网页
├── src/test/java/            匹配、断句、录音生命周期与 PCM 转换回归
├── docs/                     开发说明、接口资料和展示图
├── tools/                    手动维护工具
└── .local/                   原始开发包归档及本地诊断证据（Git 忽略）
```

Java 包名仍为 `com.example.voicekill`。网页是资源中的单文件 HTML/CSS/JS，没有 Node.js 前端工程。

## 模块职责

| 文件（相对 Java 包根目录） | 职责 |
| --- | --- |
| `VoiceKillMod.java` | 装配配置、语音/命令/tick/连接/服务器生命周期事件 |
| `DangerWordManager.java` | 开始/停止、计时与组词、当前词更新、快照和 HUD、网页操作与安全预览 |
| `SpeechHandler.java` | 将完整语音处理排到服务端线程，统计最优文本、匹配、日志和处决 |
| `DeathBoard.java` | 内存中的死亡次数排行 |
| `command/VoiceKillCommand.java` | `/voicekill start/stop/status` 及 `/vk` 别名；当前没有 OP 限制 |
| `config/ModConfig.java` | Gson 配置读写与范围校验 |
| `wordbank/WordBank.java` | 自定义、预置、高频、人名及在线玩家名字词池 |
| `speech/SpeechStats.java` | 中文 2～4 字 n-gram、英文词计数与统计筛选 |
| `speech/SpeechPacket.java` | Shriek 文本通道中的编号事件封装；兼容旧纯文本，最多 3 个候选 |
| `speech/SpeechWindow.java` | 按玩家隔离的断句历史、候选分支和事件 ID 去重 |
| `speech/WordMatcher.java` | 精确、别名、逐字读音、有限编辑距离匹配及命中证据 |
| `util/TextUtil.java` | 匹配用的小写及去空白归一化 |
| `util/WordAliases.java` | 展示别名生成和无需展开笛卡尔积的读音规则 |
| `util/FuzzyMatch.java` | 有长度门槛的滑窗编辑距离匹配 |
| `network/HudStatePayload.java` | 最多 20 个危险词、计时与死亡榜的 S2C 状态 |
| `client/VoiceKillClient.java` | HUD/F8 注册，后台启动模型和录音会话 |
| `client/MicCapture.java` | 停止 Shriek 采音，可切换的识别器工厂、实际引擎/回退提示及主线程发包 |
| `client/ZipformerDecoder.java` | 有限束搜索、动态词表、自适应端点、段状态隔离与资源释放 |
| `client/RecognitionHints.java` | 从游戏词表中提取带字母的多字昵称，构造 ASR 提示；不改匹配规则 |
| `client/UtteranceBudget.java` | 两种引擎共用的连续讲话采样预算；空闲不计时、不依赖危险词内容 |
| `client/EnhancedDecoder.java`、`client/PcmUtterance.java` | 有界保留段音频，后台加载可选模型，沿用原识别结算边界 |
| `client/SentencePostprocessor.java`、`client/NativeSentenceModels.java` | 两次一致的音频复核、正文不变的标点展示与故障回退 |
| `speech/ReviewMatcher.java` | 复核候选仅按当前段的字母词精确包含；中文部分必须有原始识别依据 |
| `client/SpeechModels.java` | 固定模型清单、后台下载、SHA256 校验及本地缓存 |
| `client/AudioCapture.java` | Java Sound 格式探测/设备打开，转换为 16kHz mono PCM |
| `client/CaptureSession.java` | 独占输入与识别器的工作线程、音量、退出状态和资源清理 |
| `client/AudioDevices.java`、`client/MicScreen.java` | 设备枚举、分页选择、实时状态和错误显示 |
| `client/HudOverlay.java` | 危险词、倒计时与死亡榜渲染 |
| `web/WebConsole.java` | JDK HttpServer 和 JSON 操作接口 |
| `mixin/` 下两个类 | 历史设备重定向方案；当前 Mixin JSON 没有注册它们 |

## 语音与规则链路

```text
客户端 AudioCapture → PCM 格式转换 → CaptureSession → Zipformer（默认）/ Vosk（兼容）
  → EnhancedDecoder（可选：句末音频复核 + 展示用标点）
  → 带事件 ID / 候选来源标记的 SpeechPacket → Shriek PlayerSpeakPacket
  → 服务端 ServerPlayerTalkEvent → SpeechHandler（切回服务端线程）
  → SpeechWindow + WordMatcher → 词频 / 命中证据 / 处决

服务端 tick → 换词与快照 → HudStatePayload → 客户端 HUD
网页 console.html → HTTP API → 管理器操作入口 → 服务端任务
```

识别在客户端、判定在服务端。判定输入仍由客户端提供，不能据此宣称防止修改客户端绕过上报。
新事件按 UUID 去重，重复文字不去重；旧客户端的自身去重行为仍需升级客户端才能修复。
同一句候选互斥，只有不同事件才能拼接；窗口 5 秒、每条路径尾部最多 64 字，最多 32 条路径。
命中必须包含本次新片段，不能仅命中旧尾巴。当前词更新时清理窗口，断开或处决时清理该玩家。
单字只接受最优候选，中文单字不自动生成谐音；显式配置别名仍可匹配最优候选。
人名/手动长词保留编辑距离容错和逐字同音匹配；英文及中英混合词按位置匹配字母读音。

## 轮次和状态

| 配置 | 默认值 |
| --- | --- |
| `wordsPerRound` | 5 |
| `namesPerRound` / `highFreqPerRound` | 1 / 1 |
| `intervalSeconds` | 30 |
| `smartPick` / `smartPickChance` | true / 70 |
| `speechEngine` / `speechThreads` | zipformer / 1 |
| `speechContextHints` / `speechEndpointSilenceSeconds` | true / 0.35（短段仍至少 0.6 秒） |
| `speechMaxUtteranceSeconds` | 5（Zipformer/Vosk 的连续讲话结算上限，非端到端处决时间） |
| `allowVoskFallback` | true，界面会提示实际引擎与回退原因 |
| `voskModel` | vosk-model-small-cn-0.22 |
| `audioDevice` | 空字符串（系统默认），完整设备名优先 |
| `webEnabled` / `webPort` | true / 38471 |

组词：手动词优先，预留人名与高频配额，填入人名，尝试智能词、高频词，最后随机补齐。
智能候选分数为上一轮次数 ×3 加累计次数 ×1；某次概率未命中后会终止本轮后续智能槽位尝试。
没有在线玩家时倒计时重置。计时按服务端 tick，服务器卡顿会影响现实中的时长。
未开始时不统计、不匹配；停止玩法不代表停止物理采音，后者可用 F8 停止。
词频、换词历史与死亡榜为内存态；配置和网页词库修改写入 `config/voicekill.json`。
`start` 不清空累计统计与死亡榜；服务器停止时退出玩法，下一世界仍需手动开始。

## 网页控制台

服务器启动后绑定 `127.0.0.1:38471`，停止时关闭。远程服务器的网页位于服务器本机。
页面每 1500ms 获取玩法与录音状态；管理器通常每 20 tick 发布玩法快照。

| 路径 | 用途 |
| --- | --- |
| `/api/state` | 状态，含安全预览结果 `speechPreview` |
| `/api/start`、`/api/stop`、`/api/reroll` | 游戏控制 |
| `/api/words/set`、`add`、`remove`，`/api/set-next` | 当前词和下一轮词 |
| `/api/pool/*`、`/api/nameword/*`、`/api/highfreq/*`、`/api/alias/*` | 词库和别名 |
| `/api/config` | 在线更新支持的配置 |
| `/api/audio` | 本机客户端设备、状态、音量和错误；POST 异步切换。独立服务器不操作玩家设备 |
| `/api/board/reset` | 清空死亡榜 |
| `/api/debug/match` | 安全预览匹配，不统计词频、不处决；网页默认入口 |
| `/api/debug/speech` | 历史完整模拟：选第一位在线玩家，命中会处决 |
| `/api/mixin/toggle` | 历史开关文件，当前空 Mixin 注册下不启用历史实现 |

## 开发与排查

依赖仍为 Minecraft 1.21.11、Java 21、Loader 0.19.5、Fabric API 0.141.6、Shriek 1.1.22+fabric、Architectury 19.0.1+fabric。

```powershell
.\gradlew.bat test build -x deployToGame --console=plain
.\tools\inspect-shriek.ps1 -Jar 'C:\path\to\shriek.jar'
```

第二条为手动工具，输出 `docs/shriek-javap.txt`，已忽略。
开发游戏入口仍为 `runClient` / `runServer`。设置 `game_mods_dir` 后普通 `build` 会删除目标中的旧 JAR 并部署；验证时明确跳过部署。
本机 Windows 的两套 native 模型已通过实际音频回放；真实设备、Mac native 加载、Minecraft F8 窗口及联机尚未验证。
模型选择、配置和回放结果见 [识别模型说明](ASR_MODELS.md)；音频回放不是麦克风或游戏接受证据。

## 合并来源与验证边界

保留原 Git 历史、MIT LICENSE 与资料；开发包的 README 作为历史开发交接合入 docs。
原始 ZIP、作者个人部署配置及重复解压内容位于 `.local/archives/`，不纳入 Git。
原始 ZIP 的 SHA256：`864C30C2F7ABBE26C4BAB043D87292BB08797882286E29A758D9A1DA4769E763`。
开发包 38 个文件在最初合并时逐一核对，其中 33 个忽略换行后相同；随后修复的代码不再与原包一致。

当前识别优化及相同音频前后对照见 [优化复测](REPLAY_OPTIMIZATION.md)。四个匹配源文件保留 dev.2 原哈希。

## dev.5 句末处理边界

基础模型继续负责自然停顿及 5 秒连续讲话预算。句末处理会增加推理时间，不把标点模型输出当成声学端点。原始识别候选保留，音频复核不接收危险词表；两种尾静音条件输出归一化一致才添加带来源标记的候选。两次输出一致是稳定性筛选，不是独立模型投票或准确率保证。复核候选只参与 `ReviewMatcher` 的当前段精确包含，不进入模糊/读音/跨段历史。纯中文、单字符以及没有原始中文依据的混合目标均不能靠复核处罚。

后处理模型在 daemon 线程下载、验证及加载；麦克风仍可使用基础模型。退出期间会中断加载并释放迟到的模型，同一缓存目录串行准备。网页 `/api/audio` 的 `postprocess` 显示模型状态、最近原文/复核/标点文本、耗时与错误。所有音频只在本地处理。
