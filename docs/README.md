# 文档索引

| 文档 | 用途 |
| --- | --- |
| [项目 README](../README.md) | 安装、玩法与用户配置 |
| [项目结构与运行机制](ARCHITECTURE.md) | 当前源码结构、数据流、开发入口与已知边界 |
| [修复与验收说明](FIXES_AND_VALIDATION.md) | 1.0.15-dev.1 的修改、自动验证和实机缺项 |
| [识别模型与回放测试](ASR_MODELS.md) | 1.0.15-dev.2 的双语引擎、下载、兼容模式与实测边界 |
| [识别优化与同组复测](REPLAY_OPTIMIZATION.md) | dev.3 的前后命中、实时延迟及新增近邻对照 |
| [dev.4 回退与验证](DEV4_VALIDATION.md) | 基于 dev.3 的 5 秒连续讲话结算、字母提示及被拒绝的句级模型实验 |
| [五个危险词的语音回放](NICKNAME_REPLAY.md) | 合成语音识别原文、实时回放延迟与误触发矩阵 |
| [开发交接指南](DEVELOPMENT.md) | 1.0.14 开发包提供的环境、调试流程和历史故障经验 |
| [详细说明](详细说明.md) | 作者原有长篇说明和历史问题记录 |
| [Shriek 接口速查](shriek-api.md) | 作者提供的字节码分析结论，升级依赖后需重新核对 |

开发交接与详细说明来自 `VoiceKill-1.0.14-Dev.zip`，其中作者描述的历史实测不代表本工作区已复验。
涉及当前默认值和实现行为时，优先查源码及结构说明。

`console-desktop.png`、`console-mobile.png` 为控制台展示图。
`../tools/inspect-shriek.ps1` 为手动接口检查工具，其输出 `shriek-javap.txt` 不纳入版本控制。

- [dev.5 句末处理与验证](DEV5_VALIDATION.md)：当前候选版本的离线标点、复核与同组回放。
