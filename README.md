# VoiceKill

> 说到「危险词」就当场暴毙。危险词每 30 秒换一组，其中一部分甚至会「实时更新」。

Minecraft **1.21.11** / Fabric 模组。默认使用 Zipformer 中英双语离线模型，文本传输复用 [Shriek](https://modrinth.com/mod/shriek)，
说到本轮任意一个危险词就会被服务端 `kill()`。

当前工作区为 **1.0.15-dev.5 修复候选版**：基于 dev.3 的中英双语流式识别，连续讲话约每 5 秒结算，新增离线标点与句末音频复核，保留 Vosk 兼容/回退模式。
自动测试与构建已验证，真实 Windows/macOS 麦克风和联机仍待验收。客户端与服务端请使用同一候选版本。

![控制台](docs/console-desktop.png)

## 声明
- 本 Mod 的功能设计，测试迭代由 [L\_FLA5H](https://github.com/L-FLA5H) 完成，代码开发由 AI 完成，

- 当前为开发候选版，语音仍可能误识别或漏判，真实设备与联机尚未验收，适合朋友间测试体验。

## 功能

- **每轮一组危险词**（默认 5 个），说出任意一个直接带带带
- **智能选词** —— 上一轮高频 ×3 + 整局累计 ×1，越常说越容易出
- **匹配容错** —— 长人名同音字（豆腐唐包包 → 豆腐汤宝宝）、已有别名/有限编辑距离、多候选回退，以及停顿拆词的有界拼接；单字只使用原始首候选
- **离线句末处理** —— SenseVoice INT8 结合整段音频复核中文口音字母拼读；CT Transformer 恢复标点，正文校验失败则保留原文
- **连续讲话结算** —— 自然停顿提前结算，一直讲话也按约 5 秒音频预算提交
- **四角 HUD** —— 本轮最多 20 个词、倒计时、言多必失榜，常驻显示
- **网页控制台** —— `http://127.0.0.1:38471/` 改词库、看统计、模拟输入
- **游戏内选麦克风** —— F8，选完立即生效，下次启动自动沿用
- **故障可见** —— F8 / 网页查看输入音量和连接错误，支持刷新、重新连接与停止录音
- **安全预览** —— 网页「仅测匹配」显示命中证据，不处决玩家、不改词频

## 环境要求

| 东西 | 版本 |
| --- | --- |
| Minecraft Java 版 | **1.21.11** |
| Fabric Loader | **0.17.3+** |
| Java | **21** |

> 0.17.3 是 Fabric API 0.141.6 的硬性下限，不是本模组的要求。

## 安装

1. 把 **4 个模组**丢进 `mods`：`voicekill`、[Fabric API](https://modrinth.com/mod/fabric-api)、
   [Shriek](https://modrinth.com/mod/shriek)、[Architectury API](https://modrinth.com/mod/architectury-api)
2. 默认双语模型（约 60MB）首次启动在后台下载到 `<游戏目录>/voicekill/models/zipformer-bilingual-small-int8/`，不阻塞渲染线程。
   模型准备失败且允许回退时，使用 Vosk 中文模型（另约 42MB）；网页会显示实际引擎和回退原因。
3. 可选句末模型默认启用，后台另下载约 315MB（SenseVoice 约 240MB、标点约 76MB），缓存到同一 `voicekill/models/` 下；下载或加载期间继续基础识别。失败原因可在网页麦克风状态查看。
4. 模型准备好后按下方命令开始游戏；首次准备进度和失败原因可在网页/F8 状态查看

**Shriek 是双端模组**，单人游戏也要装；联机时服务端和每个客户端都要装齐这 4 个。

## 使用

```
/voicekill start     开始（别名 /vk start）
/voicekill stop      停止
/voicekill status    查看状态
```

模组默认**不启用**，不输入 start 不会判定、也不会统计词频。

Windows / macOS 均可按 **F8** 选择音频输入设备；也可在网页「麦克风状态」下拉选择后点击「连接并保存」。连接成功才保存配置，**下次启动会自动用同一个设备**。
设备较多时可翻页；麦克风断开后状态会显示失败，可重新选择设备。
输入音量长期为 0 时检查系统静音、输入设备和游戏/启动器的麦克风权限。
Mac 的权限还可能受启动器影响，见 [SVC 的 macOS 权限说明](https://modrepo.de/minecraft/voicechat/wiki/macos)。

单字危险词只接受最优候选，不再自动扩展中文单字谐音。长名字保留容错；英文/中英混合词按字母读音匹配，
不再受展示用别名数量上限限制。双语模型可直接输出英文，但昵称、字母读法仍可能听错，保留匹配容错。

句末复核不接收危险词列表，也不把中文谐音强行改成英文。只有两次音频复核得到一致的新字母结果才追加候选，服务端仅允许英文/中英混合多字符词按**精确包含**触发；中文部分须已出现在原始首候选中。复核候选不进入谐音、编辑距离或跨句拼接。单字「干 / 才」仍只接受原始首候选。标点仅用于展示与排查，不决定结算，也不改变处罚正文。

这些约束降低新增误杀风险，但不能保证识别绝不出错；合成回放也不代表真人口音验收。详见 [dev.5 验证记录](docs/DEV5_VALIDATION.md)。

## 配置

`config/voicekill.json`，常改的几项：

| 字段 | 说明 |
| --- | --- |
| `wordsPerRound` | 每轮几个危险词（1~20） |
| `highFreqPerRound` | 其中几个是「我/你/他」这类高频词 |
| `intervalSeconds` | 换词间隔（秒） |
| `smartPickChance` | 智能选词概率 0~100 |
| `audioDevice` | 录音设备名，留空 = 系统默认 |
| `voskModel` | Vosk 模型名 |
| `speechEngine` | `zipformer`（默认，中英双语）或 `vosk`（兼容模式） |
| `speechContextHints` | 对当前危险词中的中英昵称提供识别提示，默认 true；不提示中文单字 |
| `speechEndpointSilenceSeconds` | 较长语音段的静音等待，默认 0.35 秒；短段至少保留 0.6 秒 |
| `speechMaxUtteranceSeconds` | 首次识别到文本后的连续讲话预算，默认 5 秒，范围 2～15；空闲不计时，另加首词识别、解码、网络及游戏 tick 耗时 |
| `speechThreads` | 各离线模型 CPU 线程数，默认 1，范围 1～4 |
| `speechSentenceReview` | 句末音频复核，默认 true；关闭可回到基础流式识别 |
| `speechPunctuation` | 离线标点恢复，默认 true；仅用于展示 |
| `speechPostModelDir` | 句末模型缓存根目录，留空使用 `<游戏目录>/voicekill/models` |
| `allowVoskFallback` | 双语模型加载失败时是否允许回退，默认 true |
| `speechModelDir` | 双语模型自定义目录，空字符串使用默认缓存目录 |
| `autoModelDownload` | 自动下载所选模型；关闭后需要提前安装模型文件 |

改完重启游戏生效；网页控制台上改的那几项立刻生效。

## 构建

```bash
./gradlew build -x deployToGame
```

产物在 `build/libs/voicekill-<版本>.jar`。

Windows 使用 `./gradlew.bat build -x deployToGame`。当前仓库未启用自动部署；如果自行设置
`game_mods_dir`，普通 `build` 会执行部署，并先删除目标目录中的旧 VoiceKill JAR。

## 开发文档

- [dev.5 句末处理与复测](docs/DEV5_VALIDATION.md)：标点、音频复核、安全约束与当前验证结果
- [文档索引](docs/README.md)：用户说明、开发交接、接口资料的入口
- [项目结构与运行机制](docs/ARCHITECTURE.md)：按当前源码整理的模块、数据流、配置与验证边界
- [开发交接指南](docs/DEVELOPMENT.md)：开发环境、历史故障与调试方法
- [修复与验收说明](docs/FIXES_AND_VALIDATION.md)：回归覆盖、构建命令、实机验收项目
- [识别模型与回放测试](docs/ASR_MODELS.md)：双语模型、资源占用、回退与本机对比结果
- [识别优化与同组复测](docs/REPLAY_OPTIMIZATION.md)：原音频和匹配规则不变的前后对照
- [五个危险词回放报告](docs/NICKNAME_REPLAY.md)：合成语音下的识别原文、触发延迟及漏判/误触发

用户版与开发包已合并：保留仓库源码和构建配置，补入开发包的文档与工具。
原始开发 ZIP 和作者的部署配置保存在本地 `.local/archives/`，由 Git 忽略。

## 致谢

- [Shriek](https://modrinth.com/mod/shriek)（MIT）—— 语音识别与网络通道
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）—— Zipformer、SenseVoice 与离线标点推理
- [Vosk](https://alphacephei.com/vosk/)（Apache-2.0）—— 离线识别模型
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) —— 「自己接管录音才能热切换设备」的思路来自它

## 许可

[MIT](LICENSE) © 2026 L_FLA5H
