# dev.4（历史验证快照）：基于 dev.3 的连续讲话结算与字母提示

本文描述 dev.4 当时的代码；当前 dev.5 增加受约束的句末音频复核和标点，见 [dev.5](DEV5_VALIDATION.md)。

按用户选择保留 dev.3 的 Zipformer 识别路线；SenseVoice/Silero 方案因近邻误触发没有进入默认或可选生产链路。

## 当前实现

- 16kHz PCM、40ms 采音、4 路束搜索、1 CPU 线程及服务端匹配规则沿用 dev.3。
- ASR 提示只保留原名和通用字母拼读形式，字母/汉字边界统一处理，不再使用汉字拟音提示；没有针对某个昵称写识别分支。
- 自然停顿仍可提前结算：较长段约 0.35 秒静音，短段约 0.6 秒。
- 共享 UtteranceBudget 在模型首次出现非空识别假设后计算 5 秒输入音频预算，空闲不消耗下一位说话者的预算；两种引擎都使用这一机制。
- 连续讲话结算时，Zipformer 冲刷已采集的尾部后新建 stream，Vosk 读取 UTF-8 final result 后 reset。
- Zipformer 的空白端点仅重置搜索，保留未解码的前瞻音频；有文本端点才新建 stream，兼顾空闲后首词与旧音节隔离。
- 5 秒是识别预算，不是严格的端到端处决时限；还包括首词识别延迟、解码、游戏 tick 和网络。
- 没有句后语义改写或标点恢复模块。两者不能伪装成已经完成的功能。

## 原组音频三轮实时回放

| 危险词 | dev.3 命中 | dev.4 命中 |
| --- | --- | --- |
| qaq | 0/9 | 0/9 |
| p老板 | 9/9 | 9/9 |
| 豆腐汤宝宝 | 9/9 | 9/9 |
| 干 | 9/9 | 9/9 |
| 才 | 9/9 | 9/9 |

总体命中 51/63 → 51/63；丢失旧命中 0；原语料误触发 0/267，额外近邻对照 0/210。
相同样本和轮次均命中的 51 次，延迟中位数 0.79 → 0.79 秒。

原 22 段 WAV 的哈希完全不变，四个匹配源码哈希也不变。注意：22 段只有 17 种独立波形，Kangkang/Yaoyao 的对应 WAV 完全相同；这些是重复合成回放，不代表三个独立口音或多名玩家。

## 不停讲话与空闲后开口

| 引擎 / 样本 | 开口至首次最终文本（墙钟） | 相邻最终文本最大间隔（墙钟） | 目标触发 | 其他误触发 |
| --- | --- | --- | --- | --- |
| zipformer / continuous-tofu | 5.50 秒 | 5.73 秒 | True | 0 |
| zipformer / continuous-qaq | 2.13 秒 | 5.72 秒 | True | 0 |
| zipformer / continuous-negative | 5.18 秒 | 5.40 秒 | False | 0 |
| zipformer / idle-then-continuous-tofu | 5.50 秒 | 5.72 秒 | True | 0 |
| vosk / continuous-tofu | 5.64 秒 | 5.84 秒 | True | 0 |
| vosk / continuous-qaq | 5.38 秒 | 5.62 秒 | False | 0 |
| vosk / continuous-negative | 5.66 秒 | 5.83 秒 | False | 0 |
| vosk / idle-then-continuous-tofu | 5.64 秒 | 5.83 秒 | True | 0 |

这是合成连续讲话与 4.8 秒额外空闲控制。每个引擎各回放一轮；用于验证结算机制，不能外推真人准确率。表中的目标触发沿用原有匹配，不等同于模型逐字正确。

## 被拒绝的句级识别实验与边界

中文音色使用 SSML 明确朗读 ASCII 字母，而不是用汉字拟音代替发音。另包含 ABC、CPU、MC、B站 和近邻负例。
实验 JSON 的 literalTarget 原字段只检查归一化子串，不能当作逐字准确率：例如 amc 包含 mc，仍是多识别了一个字母。这里不使用该字段宣称通用字母正确率。
49 条记录只有 33 种独立波形。SenseVoice 完整文件转写有改善，但分段接入后出现 D站 → B站 的误触发；两个登记音色输出的是同一波形，因此这是一个独立波形上的重复失败。扩大束搜索或追加静音也不能据此证明通用修复。
更宽的九词消融中，dev.3 与简化字母提示均有一次 MC → ABC 的错误转写和误触发；它与上面五个危险词的固定回放条件不同，不能用 0/267 掩盖这一已知泛化问题。
实验代码、清单与测试仅在 src/test；发布 JAR 不含 SenseVoiceDecoder/UtteranceDecoder 或 SenseVoice 模型清单，原模型权重仅留在 .local。
中文口音逐字母拼读尚未完成真人验收；qaq 合成音漏判仍保留。强制分段附近的词、长停顿跨段及真实游戏负载仍需实录验证，不宣称通用声学识别已经解决。

## 证据

[dev.3 基线](../.local/diagnostics/nickname-replay/optimized-dev3/results.json) · [dev.4 回放](../.local/diagnostics/nickname-replay/optimized-dev4/results.json) · [近邻对照](../.local/diagnostics/nickname-replay/optimized-dev4/negative-results.json)
[连续讲话 Zipformer](../.local/diagnostics/nickname-replay/optimized-dev4/continuous-zipformer.json) · [连续讲话 Vosk](../.local/diagnostics/nickname-replay/optimized-dev4/continuous-vosk.json) · [拒绝实验](../.local/diagnostics/letter-spelling/)

验收脚本：`python tools/summarize-dev4-validation.py`。实时回放不得与其他模型推理或编译并行。

## 构建

35 项 JUnit 测试通过；`test build -x deployToGame` 成功，未部署游戏。
[dev.4 JAR](../build/libs/voicekill-1.0.15-dev.4.jar)，27,976,255 字节。
SHA256：`3dc4a750cea2760235e71fff264f79598e7cb41c9b60f975bd79f35f45d7a6f0`。
已检查版本元数据、四个 native/JVM 依赖、未内嵌模型权重；四个匹配类和 HUD 数据包的字节码与 dev.3 完全相同。
