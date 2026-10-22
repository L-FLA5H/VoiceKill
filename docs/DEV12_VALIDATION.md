# dev.12：无识别文字扣血不产生受击方向

## 原因和修复

dev.11 的静默伤害使用正常 `hurtServer` 流程，却缺少 `no_knockback` 标记。Minecraft 1.21.11 对没有攻击者/来源位置的伤害仍进入击退及 `indicateDamage` 分支，产生受击方向动画包。无来源并不等于无击退或无镜头倾斜。

- 仅给 `voicekill:silence` 增加 `no_knockback`、`no_impact` 两个标签，阻止随机击退、受击方向包和伤害造成的运动标记；`replace:false` 保留所有原版条目。
- 普通伤害事件仍会让客户端启动 `hurtTime`，相机会使用原有 `hurtDir`。新增客户端 `VoiceKillSilenceCameraMixin`，仅当镜头角色存活、最后伤害类型确为 `voicekill:silence` 时取消 `GameRenderer.bobHurt`，避免沿用上次攻击的角度。
- 不取消伤害事件、不改全局受伤设置，不改变扣血数值/间隔、生命同步、伤害提示、普通攻击/摔落反馈或死亡镜头。语音和危险词判定未改。

## 验证和边界

通过原版映射字节码核对 `NO_KNOCKBACK` 对 `knockback`、`indicateDamage` 的分支控制，及客户端伤害事件启动 `hurtTime` 的路径；旧 dev.11 JAR 确实缺少上述两个标签。

126 项测试与 `./gradlew.bat test build -x deployToGame` 通过。回归要求伤害标签仅追加自有类型，实际 Minecraft 伤害 Codec 接受定义，仅静默伤害抑制镜头、普通攻击/摔落不抑制、死亡保留，并检查真实目标方法签名及 Mixin 注册。补齐此测试自身的 Minecraft Bootstrap 初始化，避免单独运行依赖其他测试的执行顺序。

本次未启动 Minecraft 目视验收镜头，也未重跑未改动的语音模型回放；不把接口/策略测试说成真人联机验收。

客户端与服务端均更新到 dev.12。无需换模型、覆盖旧配置或改变静默扣血参数。完整包仍不含 Vosk，保留用户 WordBank 原文；未自动部署到玩家实例。
