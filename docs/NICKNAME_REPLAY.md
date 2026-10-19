# 五个危险词的合成语音回放

版本：1.0.15-dev.2，Zipformer 小型中英双语 INT8，CPU 1 线程。

**这是 Windows 本地 TTS 合成语音，不是真人、麦克风或 Minecraft 联机验收。**

三个登记的中文音色各读五个原词，补充中文谐音、英语字母拼读、断句、上下文和纯静音。
后续哈希审计发现 Kangkang/Yaoyao 的对应 WAV 完全相同：22 个文件只有 17 种独立波形。下列数字保留原回放次数，不能当作三个独立口音的准确率。
每个样本实时节奏回放三轮；模型初始化不计入触发延迟。每次分别将五个词作为唯一危险词，来源为 manual，不额外添加别名。

完成 66 次回放；正样本命中 36/63，误触发 0/267 个负判定。

## 原词结果

| 危险词 | 命中回放 | 命中后的延迟中位数及范围 |
| --- | --- | --- |
| qaq | 0/9 | —（未命中） |
| p老板 | 6/9 | 约 820 ms（810～830） |
| 豆腐汤宝宝 | 9/9 | 约 630 ms（620～720） |
| 干 | 9/9 | 约 1250 ms（1240～1370） |
| 才 | 3/9 | 约 1480 ms（1480～1490） |

延迟仅统计命中的回放。负值表示在整段刺激说完之前已经触发；“未命中”没有触发延迟。

## 每种音色的识别原文

| 样本 | 原文（同一音色多轮不同结果均列出） | 应命中词 | 命中 |
| --- | --- | --- | --- |
| huihui-qaq | （无最终文本） | qaq | 0/3 |
| huihui-p | 替老板 | p老板 | 0/3 |
| huihui-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 | 3/3 |
| huihui-gan | 干 | 干 | 3/3 |
| huihui-cai | 才 | 才 | 3/3 |
| kangkang-qaq | （无最终文本） | qaq | 0/3 |
| kangkang-p | P老板 | p老板 | 3/3 |
| kangkang-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 | 3/3 |
| kangkang-gan | 干 | 干 | 3/3 |
| kangkang-cai | 猜 | 才 | 0/3 |
| yaoyao-qaq | （无最终文本） | qaq | 0/3 |
| yaoyao-p | P老板 | p老板 | 3/3 |
| yaoyao-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 | 3/3 |
| yaoyao-gan | 干 | 干 | 3/3 |
| yaoyao-cai | 猜 | 才 | 0/3 |

## 补充读法和对照

| 样本 | 实际合成文本 | 识别结果 | 命中 | 延迟 |
| --- | --- | --- | --- | --- |
| qaq-phonetic | 扣哎扣 | 口爱口 | 0/3 | —（未命中） |
| qaq-english-spelling | Q A Q | W | 0/3 | —（未命中） |
| p-phonetic | 劈老板 | 新老板 | 0/3 | —（未命中） |
| tofu-split | 豆腐汤 / 宝 / 宝 | 豆腐汤 / 饱饱 | 3/3 | 约 1000 ms（1000～1000） |
| gan-context | 干活 | 干活 | 3/3 | 约 1070 ms（1070～1080） |
| cai-context | 刚才 | 刚才 | 3/3 | 约 1020 ms（1020～1030） |
| silence | （纯静音） | （无最终文本） | 0/3 | —（未命中） |

## 误触发与提前触发

- 说“才”时，危险词为“干”：0/12 次误触发。
- 说“干”时，危险词为“才”：0/12 次误触发。
- 本轮没有观察到整段刺激说完之前的提前触发。
- 断句样本输入为“豆腐汤 / 宝 / 宝”，模型实际返回“豆腐汤 / 饱饱”两个最终结果，验证了跨两个识别结果的拼接，不能视为三包逐字上报验证。

## 试听与核查

- [huihui-qaq](../.local/diagnostics/nickname-replay/corpus/huihui-qaq.wav)：qaq；Microsoft Huihui Desktop。
- [huihui-p](../.local/diagnostics/nickname-replay/corpus/huihui-p.wav)：p老板；Microsoft Huihui Desktop。
- [huihui-tofu](../.local/diagnostics/nickname-replay/corpus/huihui-tofu.wav)：豆腐汤宝宝；Microsoft Huihui Desktop。
- [huihui-gan](../.local/diagnostics/nickname-replay/corpus/huihui-gan.wav)：干；Microsoft Huihui Desktop。
- [huihui-cai](../.local/diagnostics/nickname-replay/corpus/huihui-cai.wav)：才；Microsoft Huihui Desktop。
- [kangkang-qaq](../.local/diagnostics/nickname-replay/corpus/kangkang-qaq.wav)：qaq；Microsoft Kangkang。
- [kangkang-p](../.local/diagnostics/nickname-replay/corpus/kangkang-p.wav)：p老板；Microsoft Kangkang。
- [kangkang-tofu](../.local/diagnostics/nickname-replay/corpus/kangkang-tofu.wav)：豆腐汤宝宝；Microsoft Kangkang。
- [kangkang-gan](../.local/diagnostics/nickname-replay/corpus/kangkang-gan.wav)：干；Microsoft Kangkang。
- [kangkang-cai](../.local/diagnostics/nickname-replay/corpus/kangkang-cai.wav)：才；Microsoft Kangkang。
- [yaoyao-qaq](../.local/diagnostics/nickname-replay/corpus/yaoyao-qaq.wav)：qaq；Microsoft Yaoyao。
- [yaoyao-p](../.local/diagnostics/nickname-replay/corpus/yaoyao-p.wav)：p老板；Microsoft Yaoyao。
- [yaoyao-tofu](../.local/diagnostics/nickname-replay/corpus/yaoyao-tofu.wav)：豆腐汤宝宝；Microsoft Yaoyao。
- [yaoyao-gan](../.local/diagnostics/nickname-replay/corpus/yaoyao-gan.wav)：干；Microsoft Yaoyao。
- [yaoyao-cai](../.local/diagnostics/nickname-replay/corpus/yaoyao-cai.wav)：才；Microsoft Yaoyao。
- [qaq-phonetic](../.local/diagnostics/nickname-replay/corpus/qaq-phonetic.wav)：扣哎扣；Microsoft Huihui Desktop。
- [qaq-english-spelling](../.local/diagnostics/nickname-replay/corpus/qaq-english-spelling.wav)：Q A Q；Microsoft Zira Desktop。
- [p-phonetic](../.local/diagnostics/nickname-replay/corpus/p-phonetic.wav)：劈老板；Microsoft Huihui Desktop。
- [tofu-split](../.local/diagnostics/nickname-replay/corpus/tofu-split.wav)：豆腐汤 / 宝 / 宝；Microsoft Huihui Desktop。
- [gan-context](../.local/diagnostics/nickname-replay/corpus/gan-context.wav)：干活；Microsoft Huihui Desktop。
- [cai-context](../.local/diagnostics/nickname-replay/corpus/cai-context.wav)：刚才；Microsoft Huihui Desktop。
- [silence](../.local/diagnostics/nickname-replay/corpus/silence.wav)：纯静音；none。

## 测量边界与复现

- 实际链路：16kHz 单声道文件源 → 4096 字节实时分块 → 生产 CaptureSession / ZipformerDecoder → 生产 SpeechWindow / WordMatcher。
- 终点以 10ms RMS 帧高于 -50dBFS 估计；10ms 是分帧粒度，不代表人工发音边界只有 ±10ms 误差。
- 触发延迟包含分块等待、模型端点和本机匹配，未包含客户端 tick、网络和 Minecraft 服务端调度，也没有实际处决玩家。
- 原始字母写法的 TTS 发音可能与玩家口头读法不同，应结合补充拼读及 WAV 听辨，不将这些样本当作总体识别率。
- 纯静音和其他词作为负样本；观察零误触发不等于真实游戏不会误杀。
- 每段末尾追加 3 秒静音；未在该观察窗口命中即记为漏判。

```powershell
.\tools\generate-speech-corpus.ps1
python tools/prepare-speech-corpus.py
.\gradlew.bat replayNicknames -x deployToGame --console=plain
python tools/summarize-nickname-replay.py
```

生成器拒绝覆盖已有 synthesis.json；已生成语料时跳过前两条命令，重用同一 WAV。另建语料时使用新的 OutputDirectory。

[原始 JSON](../.local/diagnostics/nickname-replay/results.json) · [音频清单和哈希](../.local/diagnostics/nickname-replay/corpus/manifest.json)

这些音频与原始结果仅本地保存，未纳入 Git。
