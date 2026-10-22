# 识别模型、自然断句与回放

当前为 dev.16，模型权重沿用dev.15。底层使用 sherpa-onnx 1.13.5 的小型双语 Zipformer INT8，CPU 默认单线程、beam 4。保留 dev.3 基础方案；新增 Silero VAD 边界、句末 SenseVoice INT8 音频复核与 CT Transformer 离线标点。

## 模型与缓存

| 模型目录（游戏目录 `voicekill/models/` 下） | 作用 | 文件数 |
| --- | --- | --- |
| `zipformer-bilingual-small-int8` | 中英同一流式识别器，包括 tokens 和 bpe.vocab | 5 |
| `sensevoice-review-int8` | 对已结算段做不带危险词表的音频复核 | 2 |
| `punctuation-zh-en-int8` | 最终展示文字恢复标点 | 1 |
| `silero-vad` | 约 0.64MB，检测语音及声学停顿 | 1 |

所有权重在 JAR 外，来源、大小、SHA256 固定在 `src/main/resources/voicekill/asr/*.json`。基础约 60MB，句末模型另约 315MB。升级已有完整离线缓存只新增 Silero 文件，原权重不换。基础模型来源固定 revision `e2382758de9a0219b4efe682b95af30b399db3b8`，详见 [上游模型卡](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t)。

下载和初始化在后台；下载失败/校验失败可见，不把半成品当有效模型。全程本地识别，不上传音频。自动下载开关同时约束这些模型；离线包模板关闭自动下载与 Vosk 回退，不含 Vosk 模型。只有显式使用 Vosk 兼容模式时才另配它的模型。

模组携带 Java API 及 Windows x64、macOS x64/arm64 JNI；本次实机仅验证 Windows。Zipformer / SenseVoice / 标点清单声明 Apache-2.0，Silero 为 MIT。

```json
{
  "speechEngine": "zipformer",
  "speechThreads": 1,
  "speechContextHints": true,
  "speechNaturalBoundary": true,
  "speechEndpointSilenceSeconds": 0.6,
  "speechMaxUtteranceSeconds": 10.0,
  "speechSentenceReview": true,
  "speechPunctuation": true,
  "speechPostModelDir": "",
  "speechModelDir": "",
  "allowVoskFallback": false,
  "autoModelDownload": false
}
```

这段是离线包建议配置，不要覆盖已有整份词库配置。原有 5 / 0.35 数值不会自动迁移，使用 F7 设置后保存并重连。`speechPostModelDir` 是三个后处理目录的父目录；主模型自定义目录直接指向其文件夹。

## 自然断句与中英同句

PCM 每 40ms 送入 Silero 和同一个 Zipformer 流；实时假设仅显示。Silero 约 0.6 秒停顿确认后，冲刷这一条流的右侧上下文，输出最终候选。短暂换气不重建识别流；不将句子按中文/英文切开，也不裁剪音频重开 ASR。句末复核使用对应语音段音频而非混入旧段的缓存。

持续讲话约 10 秒兜底，另加声学检测、解码、客户端 tick 和网络耗时。词表变化不重置当前句或 watchdog，识别提示在下一识别流更新。客户端按讲话音频范围记录实际经历的词表编号，服务端按这些轮次由新到旧判定，不把等待复核时才出现的新词追溯加入。停止/重新开始、断线和切服仍丢弃旧段。空闲不消耗下一句话的完整预算。它按声学停顿决定边界，不能保证理解语法或所有换气都不拆句。标点恢复在结算之后，只影响展示。

自然边界模型不可用时准备备用流式端点，但暂停识别/判定与无识别扣血，并在 F8/网页及底部显示原因；关闭 `speechNaturalBoundary` 可主动用此路径。明确关闭句末复核/标点后，对应模型不纳入就绪要求；更改识别配置需重连。开启的模型准备中或运行异常时暂停识别，状态恢复重新等待。dev.15 客户端和服务端都要更新，原模型文件不变。

## 词表优先级和读音匹配

支持可选识别词表元数据，本轮自定义词/人名优先。多字字母/混合词保持原有最高提示强度 1.5，普通字母词减弱，优先长中文词只加低强度提示；中文单字和短词不加偏置。提示只使用原词及通用逐字母格式，没有昵称专用的中文替换表。

服务端只使用最终选定句，已接受的复核不会因初识没命中而失效。精确/别名/字母读音后，统一计算中文音节距离，支持前后鼻音、h/f、l/n、平翘舌；所有词源包括单字使用同一规则。同分优先自定义词/人名及较完整词，跨轮仍按新组优先。

`phoneticMatching: true`、`phoneticThreshold: 0.30` 为默认服务端设置，范围 0.05～0.75。模型权重和断句参数不变，不增加复核轮次；泛化匹配不改写模型输出。“菜/才”“刚/干”等同音/近音会产生额外触发，完整判定回放与边界见 [dev.13 验证](DEV13_VALIDATION.md)。下方各 dev 版本保留当时的历史策略及数字，不能当作当前规则。

## 复现

原 WAV 存在时不要重新生成。当前 NicknameReplay 默认使用最终句统一读音匹配（true/0.30），报告记录两字段；旧规则对照由 ReviewedReplayCheck 不传强度参数执行。Gradle `replayNicknames` 支持 `replayEngine=priority`、`replayMaxUtteranceSeconds=10`、`replayEndpointSilenceSeconds=0.6`，以及可选 `replayLexicon` JSON（text/source/aliases/preferred）。完整命令见 dev.7 验证文档。

能力回放加速时采用输入音频时间推进 5 秒匹配历史，不将压缩墙钟当真实历史；延迟只看 `replayRealtime=true` 报告，排除模型初始化、Minecraft tick 和网络。Vosk 兼容模式显式按 UTF-8 读取 native JSON，避免 Windows 默认编码乱码。

## dev.2 历史吞吐基线

以下数字属于 dev.2 的贪心解码基线，不能当作 dev.3 的吞吐或触发延迟；当前策略回放见优化复测。
环境：Windows x64，AMD Ryzen 9 8945HX，Java 21；新引擎 1 线程，未启动 Minecraft、未打开麦克风。
输入为模型来源仓库提供的 `0.wav`、`1.wav`、`2.wav`（约 10.053、5.100、4.690 秒，中英混合）。
三轮交替顺序回放；两者均使用实际生产 Decoder，分块送入同一 PCM，补相同尾部静音。
Vosk 对照为 `vosk-model-small-cn-0.22`，并已修复上述 UTF-8 读取问题。

| 模型 | 9 次解码 RTF 中位数 | 10.053 秒音频的解码耗时中位数 |
| --- | --- | --- |
| 双语 Zipformer | 0.0337 | 0.316 秒 |
| Vosk 中文小模型 | 0.2367 | 2.548 秒 |

RTF = CPU 回放解码耗时 / 原音频时长，越小越快。这批音频中，新引擎解码吞吐约为对照的 7 倍。
新引擎能输出示例中的 `MONDAY`、`TODAY`、`ALWAYS`、`FREQUENTLY`；Vosk 中文模型将它们转写成中文近音。
新引擎仍有错词，未据此计算总体准确率，更没有证明昵称/单字辨识已经解决。
这些数据不表示游戏触发延迟快 7 倍，也不能外推到 Mac、低配 CPU 或游戏运行时。

复现命令（先准备两套模型和音频）：

```powershell
.\gradlew.bat benchmarkAsr '-PvoskDir=.local/models/vosk-model-small-cn-0.22' -x deployToGame --console=plain
```

原始结果：`.local/diagnostics/sherpa/benchmark.json`（不纳入 Git）。
`ModelBenchmark` 在 `src/test/java` 中，不随模组发布。麦克风权限、Mac native 加载、游戏帧率影响及
`qaq`、`p老板`、`豆腐汤宝宝`、`干/才` 的真实录音验收仍待完成。

dev.2 的 [五个危险词合成回放基线](NICKNAME_REPLAY.md)：三个音色标签及补充读法共 66 次实时节奏回放；其中 Kangkang/Yaoyao 对应波形重复，22 个文件仅 17 个独立波形，不能代表三个独立口音。
当时原词 `qaq` 为 0/9、`p老板` 为 6/9、`才` 为 3/9；这表明 CPU 吞吐提高没有解决全部特殊昵称/单字漏判。
dev.3 的 [同组音频复测](REPLAY_OPTIMIZATION.md) 中，`p老板` 与 `才` 均为 9/9，英语逐字母拼读 `Q A Q` 为 3/3；原始小写 `qaq` 合成音仍为 0/9。
该回放不含真实麦克风、游戏 tick 或网络；真人录音验收仍待完成。

升级 dev.2 缓存时只需补充 12,564 字节 bpe.vocab，原模型权重的 URL/SHA256 均不变。关闭自动下载的用户需手动补齐该文件。

## dev.4 历史标点接口调研（dev.5 已接入）

当时 Java 运行库提供 [离线标点恢复接口](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/OfflinePunctuation.java)，现已接入 CT-Transformer 对转写文字补充标点，无需更换默认 ASR。
标点可用于展示和句末判断，不能单凭句号决定处罚或代替连续讲话兜底预算；标点文本应与用于匹配的原始转写分开，避免插入标点造成危险词漏判。
这不是中文语义纠错模型，不能据此声称字母误识别已修复。

## 句末模型清单

`src/main/resources/voicekill/asr/sensevoice-review-int8.json` 与 `punctuation-zh-en-int8.json` 固定资源版本、大小和 SHA256。标点文件从官方发布包核对后使用字节一致的固定 revision 镜像下载，官方包来源及哈希保留在清单中。两个模型均采用 Apache-2.0；不上传语音，不随 JAR 内嵌权重。`speechPostModelDir` 下需按清单 ID 建子目录；默认游戏缓存使用相同结构。

关闭 `speechSentenceReview` 可禁用额外音频复核；关闭 `speechPunctuation` 可禁用标点，两项都关闭时回到 dev.4 基础链路。历史 dev.5 加载失败时曾继续基础识别；dev.15 改为暂停识别/无识别扣血，在控制台展示失败原因；修复缓存后重新连接麦克风可重试。

## dev.8 说明

中文复核修改也执行两次稳定性检查，并可否定原始误识别；新中文处罚仍需原始支持，不能凭复核新增单字。高频/智能词不自动扩展中文同音字、不跨段拼接，统计只记录最终句且修复短中文双计数。复核也会听错，原组 QAQ→QA 与干→日文假名 `が` 导致两条漏判，详见 [dev.8 验证记录](DEV8_VALIDATION.md)。

## dev.9 说明

不把复核当成一票否决：孤立 3～8 字母缩写仅少一个尾字母、孤立单字同音换字、完整游戏词仅漏一个尾音节时，保留已有完整初识，不因此生成新危险词。其他已接受复核仍可否定原始错误。

2270 个四字及以上游戏名来自固定 Minecraft 1.21.11 官方中文资源，26711 字读音由 ICU4J 77.1 离线生成；运行时不新增 ICU 依赖或模型。完整同音词至少一个字面锚点；一次 n/l 或前后鼻音变化需复核确认及至少两个锚点。合法竞争游戏名优先，等分歧义拒绝。使用单一默认读音，不能覆盖所有多音字/口音。规则、来源、复测和边界见 [dev.9 验证](DEV9_VALIDATION.md)。

## dev.10 跨轮完整句

不再因换词清空未完成音频；同句跨轮使用服务端保存的词表快照，新组命中优先于旧组命中。开始新句时不加入旧组，复核耗时也不扩大适用轮次，跨轮不拼接不同语句的旧残片。每 4 秒换词的原生对照与不开换词时首段同在输入音频 10.36 秒结算，文字一致、epoch 未重置；这不是实机端到端延迟。协议与验证详见 [dev.10 验证](DEV10_VALIDATION.md)。

## dev.11 无文字活跃状态

新增可选无识别文字扣血玩法。实时识别出现新文字时最多每秒发送一个不含原文的会话通知，只重置服务端等待，不参与危险词处罚。原端点 Zipformer/Vosk 补齐已有临时结果的透传，模型与解码参数不变。原连续语音回放验证了等待设为 5 秒时不会因长句约 10 秒才结算而提前扣血，见 [dev.11 说明](DEV11_VALIDATION.md)。

## dev.13 最终句读音匹配

语音模型及 104 条 WAV 未改；复用 dev.10 保存的原生识别事件重新经过当前服务端规则。正确命中为原组 17/21、逐字母 24/24、完整句 2/2、混合句 21/22；无已有正确命中丢失，新增 4 个同音/近音触发，保留混合组 1 个既有误触发。此结果只检验判定层，不是新模型推理、真人口音准确率或端到端延迟。见 [完整记录](DEV13_VALIDATION.md)。

## dev.15 健康状态与同组复测

启用模型未就绪、故障或采音/识别无进展时暂停识别与语音处罚，底部间歇提醒；正常静音不豁免无文字规则。客户端/服务端都要更新dev.15。默认读音强度0.30、等待20秒、伤害2点、间隔4秒，已有配置不强制迁移。相同104条WAV已重新跑原生链路，音频哈希及候选一致、既有正确命中无丢失，0.30移除混合组1个旧误触发，仍保留4个近音触发；加速回放不是实机延迟。详见 [dev.15 验证](DEV15_VALIDATION.md)。

## dev.16 入服持续监测

健康检查与玩法开关分离，进入世界/服务器即持续上报与检查，start/stop不清空当前有效设备状态。初始报告/短暂异常确认后才提醒，保护仍立即生效。首帧等待视为未确认，不增加识别线程或模型加载。完整功能需两端dev.16，模型/推理/默认参数不变；详见 [启动监测与性能验证](DEV16_VALIDATION.md)。
