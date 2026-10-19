# 中英昵称识别与延迟优化复测

对比：1.0.15-dev.2 → 1.0.15-dev.3。所有音频 SHA256 与原 22 段样本一致，每段仍实时回放三轮。
WordMatcher、FuzzyMatch、WordAliases、SpeechWindow 四个匹配源文件 SHA256 完全不变。

**本报告使用合成语音、生产 Decoder/CaptureSession 和匹配代码；没有启动 Minecraft 或实际处决玩家。**

## 同组音频对比

| 原词 | 原命中 | 新命中 | 原延迟中位数 | 新延迟中位数 |
| --- | --- | --- | --- | --- |
| qaq | 0/9 | 0/9 | — | — |
| p老板 | 6/9 | 9/9 | 0.82 秒 | 0.75 秒 |
| 豆腐汤宝宝 | 9/9 | 9/9 | 0.63 秒 | 0.30 秒 |
| 干 | 9/9 | 9/9 | 1.25 秒 | 0.86 秒 |
| 才 | 3/9 | 9/9 | 1.48 秒 | 0.79 秒 |

延迟仅统计成功命中的回放；不同音色新增命中会改变单词内的统计群体。

严格按相同样本/轮次配对，旧新均命中的 36 次：中位延迟 1.01 秒 → 0.80 秒。

匹配目标始终使用规范名称 `qaq`、`p老板` 等。下面的汉字是合成输入读法，`Q A Q` 是字母拼读输入；不代表新增了危险词，也不代表原始小写 qaq 合成音已识别成功。

| 合成输入读法 | 匹配目标 | 原命中 | 新命中 | 新延迟 |
| --- | --- | --- | --- | --- |
| 扣哎扣 | qaq | 0/3 | 0/3 | — |
| Q A Q | qaq | 0/3 | 3/3 | 0.77 秒 |
| 劈老板 | p老板 | 0/3 | 3/3 | 0.88 秒 |
| 豆腐汤 / 宝 / 宝 | 豆腐汤宝宝 | 3/3 | 3/3 | 0.84 秒 |
| 干活 | 干 | 3/3 | 3/3 | 0.67 秒 |
| 刚才 | 才 | 3/3 | 3/3 | 0.96 秒 |

## 误触发与提前触发

- 原语料：0/267 个负判定；基线 0/267。
- 新增误触发 0；丢失基线命中 0；整段说完前提前触发 0。
- 额外近邻对照：42 次实时回放、210 个负判定，误触发 0。
- 额外对照与原语料分开统计，包含“老板/新老板/替老板/Q A/QQ/P/你好/今天/猜猜看/菜市场/发财/我回来了/天气很好”和静音。
- 额外对照的旧贪心、束搜索及不同提示强度快速探测也分别为零误触发；这些是合成语音的有限观察，不是对所有真人输入的保证。

## 采用的改动

- 识别侧使用 4 路有限束搜索；由游戏下发的当前词中，只取带 ASCII 字母的多字昵称作为 ASR 提示，中文单字不进入提示表。
- 识别提示强度限制在 1.5 以内；词表仅用原名、英语字母拼读及既有完整读音别名，不修改服务端匹配。
- 采音分块由 128ms 改为 40ms。语音段至少 1.2 秒时允许 0.35 秒静音确认，短段保留 0.6 秒确认，15 秒长段上限保留。
- 每段和词表变化时重建语音流，不继承旧段的编码/搜索状态；词表顺序变化不重置。
- 新增并校验 12,564 字节 bpe.vocab，模型权重及运行库版本不变。

## 未采用的方案与限制

- 直接将所有语音的静音确认缩短到 0.25～0.45 秒，会产生截词或提前触发，未采用。
- 沿用旧识别流状态的部分候选会在一次“宝”后输出“宝宝”，未采用。
- 更高提示强度没有改善这组 qaq 的中文读法，未扩大强度；SenseVoice 离线对照也没有优于当前候选，未切换或随模组分发。
- 原始小写 qaq 的三种 TTS 发音只有约 0.25～0.36 秒，仍不能稳定识别为三个字母。中文读法仍可能输出“口爱扣”，保留漏判，不把它硬映射为昵称。
- 延迟从机器估计的发音结束算到匹配命中，未包含游戏 tick、网络、真实采集驱动及模型初始化。真实录音、Mac native 和游戏帧率影响仍待验收。

## 逐段识别原文

| 样本 | 原结果 | 新结果 |
| --- | --- | --- |
| huihui-qaq | （无最终文本） | （无最终文本） |
| huihui-p | 替老板 | P老板 |
| huihui-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 |
| huihui-gan | 干 | 干 |
| huihui-cai | 才 | 才 |
| kangkang-qaq | （无最终文本） | （无最终文本） |
| kangkang-p | P老板 | P老板 |
| kangkang-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 |
| kangkang-gan | 干 | 干 |
| kangkang-cai | 猜 | 才 |
| yaoyao-qaq | （无最终文本） | （无最终文本） |
| yaoyao-p | P老板 | P老板 |
| yaoyao-tofu | 豆腐汤宝宝 | 豆腐汤宝宝 |
| yaoyao-gan | 干 | 干 |
| yaoyao-cai | 猜 | 才 |
| qaq-phonetic | 口爱口 | 口爱扣 |
| qaq-english-spelling | W | Q A Q |
| p-phonetic | 新老板 | P老板 |
| tofu-split | 豆腐汤 / 饱饱 | 豆腐汤 / 好 / 好 |
| gan-context | 干活 | 干活 |
| cai-context | 刚才 | 刚才 |
| silence | （无最终文本） | （无最终文本） |

## 证据和复现

[基线 JSON](../.local/diagnostics/nickname-replay/baseline-dev2/results.json) · [新版 JSON](../.local/diagnostics/nickname-replay/optimized-dev3/results.json) · [额外负样本 JSON](../.local/diagnostics/nickname-replay/optimized-dev3/negative-results.json)
[原音频及试听索引](NICKNAME_REPLAY.md) · [消融实验](../.local/diagnostics/nickname-replay/optimization/)

```powershell
.\gradlew.bat replayNicknames '-PreplayOutput=.local/diagnostics/nickname-replay/optimized-dev3/results.json' -x deployToGame --console=plain
.\gradlew.bat replayNicknames '-PcorpusDir=.local/diagnostics/nickname-replay/negative-controls' '-PreplayOutput=.local/diagnostics/nickname-replay/optimized-dev3/negative-results.json' -x deployToGame --console=plain
python tools/compare-nickname-replay.py
```

不重新生成 WAV；新增对照仅本地保存。比较脚本会检查音频哈希、四个匹配源文件哈希、新增误触发、丢失基线命中和提前触发。
