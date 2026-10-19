# 识别模型与回放测试：1.0.15-dev.5

默认识别后端已切换为 sherpa-onnx 1.13.5 + 小型中英双语流式 Zipformer。
Vosk 保留为可选兼容/自动回退模式，沿用此前的麦克风修复与服务端匹配规则。
当前沿用 dev.3 的有限束搜索及自适应端点，新增连续讲话 5 秒结算上限。ASR 提示仅包含原名及通用字母拼读形式，不再加入汉字拟音；原始候选匹配规则不变；新增复核候选走服务端独立的精确包含策略。历史验收见 [dev.3 优化复测](REPLAY_OPTIMIZATION.md)，历史见 [dev.4 验证](DEV4_VALIDATION.md)，当前见 [dev.5 验证](DEV5_VALIDATION.md)。

## 模型选择

使用官方文档列出的 `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`，
选 32 帧 chunk 的 INT8 encoder / INT8 joiner，decoder 使用 FP32（符合官方示例）。
5 个必要文件（增加 bpe.vocab）共 60,155,435 字节（约 57.4 MiB），无需下载包含多种精度和 chunk 的 458MB 整包。

- [官方模型说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-bilingual-chinese-english)
- [模型来源及许可](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t)：Apache-2.0
- [Java 平台支持](https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html)

不是按发布日期选模型：本项目优先中英混说、本地 CPU、流式输入和可控资源占用。
dev.5 使用 SenseVoice INT8 对基础识别器已结算的音频做句末复核，复用原有端点与 5 秒预算；
其公开推理时间不能直接当成本游戏的说完到处决延迟。[SenseVoice 示例](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)

## 安装与配置

模组内带 Java API 及 Windows x64、macOS x64/arm64 JNI；模型不打进 JAR。
首次启动后台下载模型，缓存到 `<游戏目录>/voicekill/models/zipformer-bilingual-small-int8/`。
每个文件固定来源 revision `e2382758de9a0219b4efe682b95af30b399db3b8`、大小与 SHA256，完整校验后才加载。
下载失败不会把半成品作为有效模型；已存在但校验失败的文件会保留并提示移走后重下。
所有识别在本机进行，没有上传音频或云 API 费用。

`config/voicekill.json` 的新增字段：

```json
{
  "speechEngine": "zipformer",
  "speechThreads": 1,
  "speechContextHints": true,
  "speechEndpointSilenceSeconds": 0.35,
  "speechMaxUtteranceSeconds": 5.0,
  "speechSentenceReview": true,
  "speechPunctuation": true,
  "speechPostModelDir": "",
  "allowVoskFallback": true,
  "speechModelDir": "",
  "autoModelDownload": true
}
```

想使用旧模型时将 `speechEngine` 设为 `vosk`，再重新连接麦克风/重启客户端。
不希望下载失败后悄悄继续使用兼容模型时，可将 `allowVoskFallback` 设为 false。
网页麦克风状态显示实际引擎及回退原因，F8 也显示实际引擎。
网络不可用时可以在可联网的开发环境预先下载，再把目录复制到游戏中：

```powershell
.\gradlew.bat prepareAsrModel -x deployToGame --console=plain
```

默认写入本仓库 `.local/models/zipformer-bilingual-small/`。可用带引号的
`'-PzipformerDir=C:/path/to/model-cache'` 指定目录。关闭 `autoModelDownload` 后只读现有模型。

## 处理方式

16kHz 单声道 PCM 每 40ms 送入识别器，CPU 默认 1 线程。语音段至少 1.2 秒时允许 0.35 秒静音确认，短段仍至少等待 0.6 秒。首次出现非空识别假设后，连续讲话预算默认为 5 秒；此前空闲不消耗预算。实际输出还受首词识别、模型分块与推理耗时影响。
Vosk 兼容模式使用同一上限；强制结算时读取 UTF-8 final result 并重置 native recognizer，后续音频继续处理。
有限束搜索保留 4 条路径，当前词中的多字 ASCII/中英昵称使用最高 1.5 的识别提示强度；中文单字和普通中文词不参与提示。
每段结束或提示词表变化时重建 native stream；不在服务端增加别名或放宽编辑距离。
dev.5 已增加离线标点和受约束的句末音频复核。没有无音频依据的文字联想改写；原来的 SenseVoice/Silero 替换流式识别方案仍仅用于测试，当前复核没有替换基础 Zipformer。SenseVoice 复核与 CT Transformer 标点共约 315MB，独立后台加载，正文/候选来源由服务端限制。
只在端点确认后发送文本，不拿可能反复修订的临时文本直接处决玩家。
因此仍存在分块、端点等待、客户端 tick 和网络延迟，端点设置值不是端到端延迟承诺。
新引擎当前返回一个最终结果；Vosk 继续支持最多 3 个候选。

本次回放另复现了 Vosk 的 Windows 编码问题：native UTF-8 JSON 被 JNA 按默认系统编码解码后出现乱码。
兼容模式现在显式从 native 指针按 UTF-8 读 JSON，不修改全局 `jna.encoding` 或 JVM 编码设置。

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

## 标点恢复（尚未接入）

当前 Java 运行库提供 [离线标点恢复接口](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/OfflinePunctuation.java)，可另接 CT-Transformer 对转写文字补充标点，无需更换默认 ASR。
标点可用于展示和句末判断，不能单凭句号决定处罚或代替 5 秒预算；标点文本应与用于匹配的原始转写分开，避免插入标点造成危险词漏判。
这不是中文语义纠错模型，不能据此声称字母误识别已修复。

## 句末模型清单

`src/main/resources/voicekill/asr/sensevoice-review-int8.json` 与 `punctuation-zh-en-int8.json` 固定资源版本、大小和 SHA256。标点文件从官方发布包核对后使用字节一致的固定 revision 镜像下载，官方包来源及哈希保留在清单中。两个模型均采用 Apache-2.0；不上传语音，不随 JAR 内嵌权重。`speechPostModelDir` 下需按清单 ID 建子目录；默认游戏缓存使用相同结构。

关闭 `speechSentenceReview` 可禁用额外音频复核；关闭 `speechPunctuation` 可禁用标点，两项都关闭时回到 dev.4 基础链路。加载失败时继续基础识别，在控制台展示失败原因；修复缓存后重新连接麦克风可重试。
