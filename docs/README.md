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

- [dev.5 句末处理与验证](DEV5_VALIDATION.md)：dev.5 历史候选版本的离线标点、复核与同组回放。

- [dev.6 完整句与输入设备修复](DEV6_VALIDATION.md)：10 秒 / 0.6 秒配置及蓝牙设备选择回归。

- [dev.7 安装与设置](DEV7_SETTINGS.md)：F7 设置、实时转写、换行和可移动榜单。
- [dev.7 自然断句与复测](DEV7_VALIDATION.md)：同组音频结果、保留问题和原生游戏界面验证。

- [dev.7 完整包安装教程](INSTALL_DEV7.md)：四模组、四模型的客户端与服务端安装。

- [dev.8 复核判定与高频词修复](DEV8_VALIDATION.md)：历史候选，包含新增保护与复测取舍。
- [dev.9 初识保留与游戏词纠错](DEV9_VALIDATION.md)：复核漏尾、同音字与受约束口音纠错。
- [dev.10 跨轮完整句与后组优先](DEV10_VALIDATION.md)：音频不中断、适用轮次与原生对照。
- [dev.11 无识别文字扣血](DEV11_VALIDATION.md)：等待时长、扣血量和间隔设置，实时识别活跃通知与验证。
- [dev.12 无方向扣血反馈](DEV12_VALIDATION.md)：历史镜头方案，dev.13 已恢复正常受伤摇晃。

- [dev.13 读音模糊判定](DEV13_VALIDATION.md)：最终句统一判定、通用口音距离、可调强度与回放取舍。

- [dev.14 物品栏提示背景](DEV14_VALIDATION.md)：背景贴合实际文字宽度且整块居中。

- [dev.15 背景自适应与故障保护](DEV15_VALIDATION.md)：实际文字背景、健康状态门控与0.30/20秒/4秒默认值。

- [dev.16 启动提示确认](DEV16_VALIDATION.md)：当前候选，入服即持续监测、开始不重置，启动同步/短暂故障不立即报警。
