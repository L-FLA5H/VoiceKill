# 项目结构与运行机制

依据 2026-10-21 的 1.0.15-dev.16 源码；历史交接资料来自 1.0.14。
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
├── src/main/java/            Java 源文件
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
| `DeathMessages.java` | 服务端死亡文案清理、随机选择、字面占位符渲染及保留 generic-kill 类型的死亡消息 |
| `client/DeathMessagesScreen.java` | 本地世界死亡文本草稿编辑、开关与示例预览 |
| `DeathBoard.java` | 内存中的死亡次数排行 |
| `command/VoiceKillCommand.java` | `/voicekill start/stop/status` 及 `/vk` 别名；当前没有 OP 限制 |
| `config/ModConfig.java` | Gson 配置读写与范围校验 |
| `wordbank/WordBank.java` | 自定义、预置、高频、人名及在线玩家名字词池 |
| `speech/SpeechStats.java` | 中文 2～4 字 n-gram、英文词计数与统计筛选 |
| `speech/SpeechPacket.java` | Shriek 文本通道中的编号事件封装；兼容旧纯文本，最多 3 个候选 |
| `speech/SpeechWindow.java` | 按玩家隔离的断句历史、候选分支和事件 ID 去重 |
| `speech/RoundSpeechWindow.java` | 服务端词表快照、语句轮次范围、跨轮事件去重与后组优先；最多 32 组、历史保留约 60 秒 |
| `speech/RecognitionSilence.java`、`SilenceDamage.java` | 可选的无识别文字扣血计时与独立伤害类型，服务端 tick 执行 |
| `client/RecognitionActivity.java`、`network/RecognitionActivityPayload.java` | 新识别文字的限速活跃通知，仅携带会话 UUID，不参与匹配/词频 |
| `speech/WordMatcher.java` | 命中数据、优先排序及历史匹配规则 |
| `speech/PhoneticMatcher.java` | 最终句的统一精确/别名/字母/加权音节距离匹配，保留证据区间 |
| `util/TextUtil.java` | 匹配用的小写及去空白归一化 |
| `util/WordAliases.java` | 展示别名生成和无需展开笛卡尔积的读音规则 |
| `util/FuzzyMatch.java` | 有长度门槛的滑窗编辑距离匹配 |
| `network/HudStatePayload.java` | 最多 20 个危险词、计时与死亡榜的 S2C 状态 |
| `client/VoiceKillClient.java` | HUD/F7/F8 注册，后台启动模型和录音会话 |
| `client/MicCapture.java` | 停止 Shriek 采音，可切换的识别器工厂、实际引擎/回退提示及主线程发包 |
| `client/ZipformerDecoder.java` | 有限束搜索、动态词表、自适应端点、段状态隔离与资源释放 |
| `client/RecognitionHints.java` | 从本轮原词及优先标记构造字母/低强度长中文提示；不改匹配规则 |
| `client/UtteranceBudget.java` | 两种引擎共用的连续讲话采样预算；空闲不计时、不依赖危险词内容 |
| `client/RoundTrackingDecoder.java` | 在句末复核前按音频采样范围绑定词表编号；换轮保留音频，换会话/停止时清除 |
| `client/EnhancedDecoder.java`、`client/PcmUtterance.java` | 有界保留段音频，后台加载可选模型，沿用原识别结算边界 |
| `client/SentencePostprocessor.java`、`client/NativeSentenceModels.java` | 两次一致的音频复核、正文不变的标点展示与故障回退 |
| `speech/ReviewMatcher.java` | 保留历史规则对照；当前正式判定通过 acceptFinal，不再使用初识否决 |
| `client/SpeechModels.java` | 固定模型清单、后台下载、SHA256 校验、本地缓存及按目录隔离的不可变实时进度快照 |
| `client/ModelDownloadsScreen.java` | F7 模型实时进度页；渲染时只读取快照，不在渲染线程下载或校验权重 |
| `client/AudioCapture.java` | Java Sound 格式探测/设备打开，转换为 16kHz mono PCM |
| `client/CaptureSession.java` | 独占输入与识别器的工作线程、音量、退出状态和资源清理 |
| `client/AudioDevices.java`、`client/MicScreen.java` | 设备枚举、分页选择、实时状态和错误显示 |
| `client/HudOverlay.java` | 危险词、倒计时与死亡榜渲染 |
| `web/WebConsole.java` | JDK HttpServer 和 JSON 操作接口 |
| `mixin/` | 历史设备重定向类仍未注册；客户端仅注册 VoiceKillActionBarMixin 做自有提示换行；普通受伤镜头由原版处理，静默伤害保留 no_knockback/no_impact 标签 |

## 语音与规则链路

```text
客户端 AudioCapture → PCM 格式转换 → CaptureSession → NaturalSpeechDecoder（Silero 边界 + 同一 Zipformer 双语流）
  或原 Zipformer 端点 / Vosk（兼容）
  → RoundTrackingDecoder（讲话音频的起止轮次）
  → EnhancedDecoder（可选：句末音频复核 + 展示用标点）
  → 带事件 ID / 候选来源标记的 SpeechPacket → Shriek PlayerSpeakPacket
  → 服务端 ServerPlayerTalkEvent → SpeechHandler（切回服务端线程）
  → RoundSpeechWindow → 各轮 SpeechWindow.acceptFinal + PhoneticMatcher → 词频 / 命中证据 / 处决

服务端 tick → 换词与快照 → HudStatePayload + 可选 RecognitionLexiconPayload → 客户端 HUD / 词表提示
实时新识别文字 → RecognitionActivityPayload → 服务端逐玩家重置无文字等待
服务端 tick → 可选 RecognitionSilence → 按超时/间隔施加 SilenceDamage
网页 console.html → HTTP API → 管理器操作入口 → 服务端任务
```

识别在客户端、判定在服务端。判定输入仍由客户端提供，不能据此宣称防止修改客户端绕过上报。
新事件按 UUID 去重，重复文字不去重；旧客户端的自身去重行为仍需升级客户端才能修复。
同一句候选互斥，只有不同事件才能拼接；窗口 5 秒、每条路径尾部最多 64 字，最多 32 条路径。
命中必须包含本次新片段，不能仅命中旧尾巴。当前词更新时清理窗口，断开或处决时清理该玩家。
跨轮仅保留词表快照，先前语句的拼接残片仍清除。同句按起止轮次范围由新到旧检查，新组命中直接优先；该句结束后才出现的轮次不参与。词组编号由服务端产生，客户端不上传自选词组；无轮次元数据的旧文本仅按当前轮处理。重新开始生成新会话编号，停止时清空所有旧快照。
正式判定只使用 SpeechPacket.finalText() 选定句；精确、配置别名、字母读音、中文加权音节距离依次匹配。中文覆盖所有来源及单字，默认强度 0.30；常见 l/n、h/f、平翘舌与 n/ng 距离较低。最小读音距离优先，同分再比较自定义/人名优先标记和完整长度。不同有效轮次仍优先较新组。

## 轮次和状态

| 配置 | 默认值 |
| --- | --- |
| `wordsPerRound` | 5 |
| `namesPerRound` / `highFreqPerRound` | 1 / 1 |
| `intervalSeconds` | 30 |
| `phoneticMatching` / `phoneticThreshold` | true / 0.30（0.05～0.75） |
| `smartPick` / `smartPickChance` | true / 70 |
| `speechEngine` / `speechThreads` | zipformer / 1 |
| `speechContextHints` / `speechEndpointSilenceSeconds` | true / 0.6 |
| `speechMaxUtteranceSeconds` | 10（Zipformer/Vosk 的连续讲话结算上限，非端到端处决时间） |
| `allowVoskFallback` | true，界面会提示实际引擎与回退原因 |
| `voskModel` | vosk-model-small-cn-0.22 |
| `audioDevice` | 空字符串（系统默认），完整设备名优先 |
| `webEnabled` / `webPort` | true / 38471 |

组词：手动词优先，预留人名与高频配额，填入人名，尝试智能词、高频词，最后随机补齐。
智能候选分数为上一轮次数 ×3 加累计次数 ×1；某次概率未命中后会终止本轮后续智能槽位尝试。
没有在线玩家时倒计时重置。计时按服务端 tick，服务器卡顿会影响现实中的时长。
未开始时不统计、不匹配；停止玩法不代表停止物理采音，后者可用 F8 停止。
无识别文字扣血默认关闭，启用后只对健康状态有效的生存/冒险玩家执行，死亡/离线清理计时。规则改变和重开重新等待，换轮不重置；最终句与实时新文字都可刷新等待。纯音量、标点和不变的旧假设不计，通知不携带待处罚文本。参数、单位与验收边界见 [dev.11 说明](DEV11_VALIDATION.md)。
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
| `/api/mixin/toggle` | 历史开关文件，不启用历史设备重定向实现 |

## 开发与排查

依赖仍为 Minecraft 1.21.11、Java 21、Loader 0.19.5、Fabric API 0.141.6、Shriek 1.1.22+fabric、Architectury 19.0.1+fabric。

```powershell
.\gradlew.bat test build -x deployToGame --console=plain
.\tools\inspect-shriek.ps1 -Jar 'C:\path\to\shriek.jar'
```

第二条为手动工具，输出 `docs/shriek-javap.txt`，已忽略。
开发游戏入口仍为 `runClient` / `runServer`。设置 `game_mods_dir` 后普通 `build` 会删除目标中的旧 JAR 并部署；验证时明确跳过部署。
本机 Windows 的两套 native 模型已通过实际音频回放；真实设备、Mac native 加载、多人联机尚未验证；dev.7 在独立 Minecraft 实例检查 F7、HUD 预览及实际 20 词换轮提示。
模型选择、配置和回放结果见 [识别模型说明](ASR_MODELS.md)；音频回放不是麦克风或游戏接受证据。

## 合并来源与验证边界

保留原 Git 历史、MIT LICENSE 与资料；开发包的 README 作为历史开发交接合入 docs。
原始 ZIP、作者个人部署配置及重复解压内容位于 `.local/archives/`，不纳入 Git。
原始 ZIP 的 SHA256：`864C30C2F7ABBE26C4BAB043D87292BB08797882286E29A758D9A1DA4769E763`。
开发包 38 个文件在最初合并时逐一核对，其中 33 个忽略换行后相同；随后修复的代码不再与原包一致。

当前识别优化及相同音频前后对照见 [优化复测](REPLAY_OPTIMIZATION.md)。四个匹配源文件保留 dev.2 原哈希。

## dev.5 句末处理边界

本节为 dev.5 历史：基础模型当时负责自然停顿及 5 秒连续讲话预算。句末处理会增加推理时间，不把标点模型输出当成声学端点。原始识别候选保留，音频复核不接收危险词表；两种尾静音条件输出归一化一致才添加带来源标记的候选。两次输出一致是稳定性筛选，不是独立模型投票或准确率保证。复核候选只参与 `ReviewMatcher` 的当前段精确包含，不进入模糊/读音/跨段历史。纯中文、单字符以及没有原始中文依据的混合目标均不能靠复核处罚。

后处理模型在 daemon 线程下载、验证及加载；麦克风仍可使用基础模型。退出期间会中断加载并释放迟到的模型，同一缓存目录串行准备。网页 `/api/audio` 的 `postprocess` 显示模型状态、最近原文/复核/标点文本、耗时与错误。所有音频只在本地处理。

当前已改为最终句统一读音匹配，不再沿用上述 ReviewMatcher 处罚限制。默认断句参数及指定输入设备修复见 [dev.6 验证](DEV6_VALIDATION.md)；dev.5 节保留当时的 5 秒配置记录。

## dev.7 边界、优先级与显示分离

`NaturalSpeechDecoder` 将 PCM 同时送入 Silero VAD 与同一个 Zipformer 预览流。VAD 确认声学停顿或达到兜底上限后，冲刷这一条流并结算；不会把分段音频裁剪后重新识别为另一条流。带时间戳环形缓存提供对应整段音频给句末复核；临时假设经 `SpeechTranscript` 显示，只有最终候选进入发包。换词不重置语句、VAD 或 watchdog，新提示用于下一识别流；停止/重新开始、断线或切服仍清理旧音频。`RoundTrackingDecoder` 在音频时间线上保留词表编号，按 Silero 语音段及首尾能量定位选择适用范围，先绑定范围再进入句末复核。S2C `recognition_lexicon_v2` 原子发送词表、优先标记、会话编号、轮次编号和运行状态；C2S 沿用 Shriek 文本通道的 `R=起轮UUID,止轮UUID` 元数据。

`RecognitionLexiconPayload` 是单独的可选 S2C 通道，发送本轮原词和优先标记，不带别名；发送前确认客户端支持。旧 HUD 包不变。客户端仅接受与当前 HUD 原词一致的元数据；不支持此通道时使用原字母提示。优先词保持已有字母提示最高强度 1.5，其他字母词降到 0.75，优先长中文词为 0.5；不偏置单字/短中文词。

dev.13 的 `PhoneticMatcher` 先检查全部原词的精确命中，再检查别名/字母读音，最后以半全局加权编辑距离寻找中文读音片段；插入/删除音节为 1，常见口音权重较小。仅接受包含新事件文字的证据，历史最多 5 秒/64 字。`SpeechWindow.acceptFinal` 不拼接互斥候选或已撤回原文；同句高频/智能词也接受读音匹配，仍不跨事件拼接这些词。旧 `accept`/`ReviewMatcher` 仅保留作回归对照。详细规则与权衡见 [dev.13 验证](DEV13_VALIDATION.md)。

`ModSettingsScreen/ClientSettings` 使用独立草稿和客户端字段白名单，本地游戏规则排至服务端线程，不覆盖远程服务器规则或新选设备。`SpeechHighlight` 只做字面颜色映射，`HudPlacement` 负责比例布局/边界；自有 actionbar Mixin 将 VoiceKill 换词提示交给 `HotbarHint` 换行。`hudHotbarWidth` 只决定换行上限；背景和居中位置按当前页实际最宽文字（含页码）计算，两侧各留 6 像素。详细使用见 [dev.7 设置](DEV7_SETTINGS.md)。

## dev.15 自适应背景

`HudOverlay.contentWidth` 共用 Minecraft 字体的实际像素测量：左上危险词按当前页可见行和标题（含页码）决定背景宽度，字幕按当前可见行和临时/最终标题决定宽度，原文样式保持；物品栏沿用相同测量并整块居中。左上两块分别返回实际 Rect，排行榜避让改用绘制出来的范围，最大换行宽度不再作为不可见占位。

## dev.15 采音/模型健康门控

`CaptureSession` 在调用识别器前检查启用模型状态，未就绪/失败时仅排空输入，跳过推理及转写输出；恢复或采样中断后丢弃旧音频。`SentencePostprocessor` 的模型运行异常保持失败状态，`EnhancedDecoder` 透传；超3秒没有采音/识别进展判为卡住。正常低音量和暂时没有识别文字不算故障。

`RecognitionHealthPayload` 每秒或状态变化时发送当前游戏会话UUID与固定状态码，不携带音频/设备名/转写。服务端 `RecognitionHealth` 有效期80tick，未知/过期/故障均拒绝语音判定与静默扣血，并每200tick通过actionbar通知该玩家。恢复、停止重开、断线清理旧计时与拼接；正常状态续期不刷新无新字等待。客户端/服务端都需要dev.15，旧客户端状态未知保持暂停。当前默认强度0.30、等待20秒、扣2点生命、间隔4秒；自定义配置不强制迁移。

## dev.16 启动同步与故障提示确认

`RecognitionHealth.noticeDue` 将保护状态与提示时机分离：未知/异常仍立即令ready为false；未知状态持续80tick、其他异常持续40tick后才允许告警，之后200tick限频。每位玩家记录待确认的问题类型和起点，READY在report入口就清除待确认状态，避免两次提示检查之间短暂恢复后沿用旧计时。重新开始/断线/死亡清理这些状态。协议、语音和默认参数不变，dev.15客户端兼容；服务端需dev.16以修复启动误报。

## dev.16 持续监测与玩法解耦

客户端健康上报不再依赖context.running；服务端在running=false也执行状态监测/告警，伤害仍显式要求running。健康时钟只在新世界attach初始化，不随玩法start重置；bindSession切换报告令牌但保留原有效期内的状态，旧令牌不能续期。stop清空处罚历史而保留设备监测。自动开麦/设备打开/首帧等待标记UNKNOWN，不误标麦克风损坏。完整功能需两端dev.16。
