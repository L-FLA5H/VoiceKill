"""Audit the frozen replay corpus and dev.3 -> dev.4 acceptance evidence."""
import hashlib
import json
from pathlib import Path
import statistics

root=Path('.local/diagnostics/nickname-replay')
out=root/'optimized-dev4'
read=lambda path:json.loads(Path(path).read_text(encoding='utf-8-sig'))
before=read(root/'optimized-dev3/results.json')['rows']
after=read(out/'results.json')['rows']
key=lambda r:(r['round'],r['clip']['id'])
a={key(r):r for r in before};b={key(r):r for r in after}
assert len(a)==len(b)==66 and a.keys()==b.keys(), 'incomplete paired replay'
for k,row in b.items():
    c=row['clip'];assert c['sha256']==a[k]['clip']['sha256']
    assert hashlib.sha256((root/'corpus'/c['file']).read_bytes()).hexdigest()==c['sha256']
for record in read(root/'baseline-dev2/matching-hashes.json'):
    assert hashlib.sha256(Path(record['Path']).read_bytes()).hexdigest().upper()==record['Hash']
for record in read(out/'validated-source-hashes.json'):
    assert hashlib.sha256(Path(record['Path']).read_bytes()).hexdigest().upper()==record['Hash']
controls=read(out/'negative-results.json')['rows'];assert len(controls)==42
lost=[k for k in a if a[k]['targetHit'] and not b[k]['targetHit']]
errors=[(key(r),word) for r in after for word in r['hits'] if word!=r['clip'].get('target')]
control_errors=sum(r['falseKillCount'] for r in controls)
common=[k for k in a if a[k]['targetHit'] and b[k]['targetHit']]
latency=lambda rows:statistics.median(r['hits'][r['clip']['target']]['afterSpeechEndMs'] for r in rows)
continuous={name:read(out/f'continuous-{name}.json')['rows'] for name in ['zipformer','vosk']}
checks=[]
for engine,rows in continuous.items():
    assert len(rows)==5
    for row in rows:
        clip=row['clip']
        assert hashlib.sha256((Path('.local/diagnostics/continuous-replay/corpus')/clip['file']).read_bytes()).hexdigest()==clip['sha256']
        if clip['kind']=='silence':continue
        if clip['kind']=='idle-then-continuous':assert row['targetHit'], 'idle transition lost the first dangerous word'
        events=row['events'];assert len(events)>=2, (engine,clip['id'],'no periodic results')
        first=events[0]['audioMs']-clip['speechStartSample']/16
        gap=max([y['audioMs']-x['audioMs'] for x,y in zip(events,events[1:])]+[0])
        first_wall=events[0]['wallMs']-clip['speechStartSample']/16
        wall_gap=max([y['wallMs']-x['wallMs'] for x,y in zip(events,events[1:])]+[0])
        checks.append(dict(engine=engine,id=clip['id'],firstAfterSpeechStartMs=first,maxEventAudioGapMs=gap,
                           firstWallAfterSpeechStartMs=first_wall,maxEventWallGapMs=wall_gap,
                           targetHit=row['targetHit'],falseKills=row['falseKillCount']))
        assert first<7000 and gap<7000, 'continuous speech waited too long'
        assert first_wall<7500 and wall_gap<7500, 'wall-clock processing fell behind'
summary=dict(baselineHits=sum(r['targetHit'] for r in before),candidateHits=sum(r['targetHit'] for r in after),
             pairedReplays=66,lostBaselineHits=lost,misfires=errors,controlMisfires=control_errors,
             pairedCommonMedianBeforeMs=latency([a[k] for k in common]),
             pairedCommonMedianAfterMs=latency([b[k] for k in common]),continuous=checks,
             originalFiles=22,originalUniqueHashes=len({r['clip']['sha256'] for r in after}))
(out/'comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['# dev.4：基于 dev.3 的连续讲话结算与字母提示', '',
       '按用户选择保留 dev.3 的 Zipformer 识别路线；SenseVoice/Silero 方案因近邻误触发没有进入默认或可选生产链路。', '',
       '## 当前实现', '',
       '- 16kHz PCM、40ms 采音、4 路束搜索、1 CPU 线程及服务端匹配规则沿用 dev.3。',
       '- ASR 提示只保留原名和通用字母拼读形式，字母/汉字边界统一处理，不再使用汉字拟音提示；没有针对某个昵称写识别分支。',
       '- 自然停顿仍可提前结算：较长段约 0.35 秒静音，短段约 0.6 秒。',
       '- 共享 UtteranceBudget 在模型首次出现非空识别假设后计算 5 秒输入音频预算，空闲不消耗下一位说话者的预算；两种引擎都使用这一机制。',
       '- 连续讲话结算时，Zipformer 冲刷已采集的尾部后新建 stream，Vosk 读取 UTF-8 final result 后 reset。',
       '- Zipformer 的空白端点仅重置搜索，保留未解码的前瞻音频；有文本端点才新建 stream，兼顾空闲后首词与旧音节隔离。',
       '- 5 秒是识别预算，不是严格的端到端处决时限；还包括首词识别延迟、解码、游戏 tick 和网络。',
       '- 没有句后语义改写或标点恢复模块。两者不能伪装成已经完成的功能。', '',
       '## 原组音频三轮实时回放', '',
       '| 危险词 | dev.3 命中 | dev.4 命中 |', '| --- | --- | --- |']
for word in ['qaq','p老板','豆腐汤宝宝','干','才']:
    pick=lambda data:[r for r in data if r['clip'].get('target')==word and r['clip']['kind']=='canonical']
    count=lambda rows:f'{sum(r["targetHit"] for r in rows)}/{len(rows)}'
    lines.append(f'| {word} | {count(pick(before))} | {count(pick(after))} |')
lines+=['',f'总体命中 {summary["baselineHits"]}/63 → {summary["candidateHits"]}/63；丢失旧命中 {len(lost)}；原语料误触发 {len(errors)}/267，额外近邻对照 {control_errors}/210。',
        f'相同样本和轮次均命中的 {len(common)} 次，延迟中位数 {summary["pairedCommonMedianBeforeMs"]/1000:.2f} → {summary["pairedCommonMedianAfterMs"]/1000:.2f} 秒。', '',
        '原 22 段 WAV 的哈希完全不变，四个匹配源码哈希也不变。注意：22 段只有 17 种独立波形，Kangkang/Yaoyao 的对应 WAV 完全相同；这些是重复合成回放，不代表三个独立口音或多名玩家。', '',
        '## 不停讲话与空闲后开口', '',
        '| 引擎 / 样本 | 开口至首次最终文本（墙钟） | 相邻最终文本最大间隔（墙钟） | 目标触发 | 其他误触发 |',
        '| --- | --- | --- | --- | --- |']
for r in checks:
    lines.append(f'| {r["engine"]} / {r["id"]} | {r["firstWallAfterSpeechStartMs"]/1000:.2f} 秒 | {r["maxEventWallGapMs"]/1000:.2f} 秒 | {r["targetHit"]} | {r["falseKills"]} |')
lines+=['','这是合成连续讲话与 4.8 秒额外空闲控制。每个引擎各回放一轮；用于验证结算机制，不能外推真人准确率。表中的目标触发沿用原有匹配，不等同于模型逐字正确。', '',
        '## 被拒绝的句级识别实验与边界', '',
        '中文音色使用 SSML 明确朗读 ASCII 字母，而不是用汉字拟音代替发音。另包含 ABC、CPU、MC、B站 和近邻负例。',
        '实验 JSON 的 literalTarget 原字段只检查归一化子串，不能当作逐字准确率：例如 amc 包含 mc，仍是多识别了一个字母。这里不使用该字段宣称通用字母正确率。',
        '49 条记录只有 33 种独立波形。SenseVoice 完整文件转写有改善，但分段接入后出现 D站 → B站 的误触发；两个登记音色输出的是同一波形，因此这是一个独立波形上的重复失败。扩大束搜索或追加静音也不能据此证明通用修复。',
        '更宽的九词消融中，dev.3 与简化字母提示均有一次 MC → ABC 的错误转写和误触发；它与上面五个危险词的固定回放条件不同，不能用 0/267 掩盖这一已知泛化问题。',
        '实验代码、清单与测试仅在 src/test；发布 JAR 不含 SenseVoiceDecoder/UtteranceDecoder 或 SenseVoice 模型清单，原模型权重仅留在 .local。',
        '中文口音逐字母拼读尚未完成真人验收；qaq 合成音漏判仍保留。强制分段附近的词、长停顿跨段及真实游戏负载仍需实录验证，不宣称通用声学识别已经解决。', '',
        '## 证据', '',
        '[dev.3 基线](../.local/diagnostics/nickname-replay/optimized-dev3/results.json) · [dev.4 回放](../.local/diagnostics/nickname-replay/optimized-dev4/results.json) · [近邻对照](../.local/diagnostics/nickname-replay/optimized-dev4/negative-results.json)',
        '[连续讲话 Zipformer](../.local/diagnostics/nickname-replay/optimized-dev4/continuous-zipformer.json) · [连续讲话 Vosk](../.local/diagnostics/nickname-replay/optimized-dev4/continuous-vosk.json) · [拒绝实验](../.local/diagnostics/letter-spelling/)', '',
        '验收脚本：`python tools/summarize-dev4-validation.py`。实时回放不得与其他模型推理或编译并行。']
build=out/'build.json'
if build.exists():
    info=read(build)
    assert info['failures']==info['errors']==0
    artifact=Path(info['path']);assert hashlib.sha256(artifact.read_bytes()).hexdigest()==info['sha256']
    lines+=['','## 构建', '',
            f'{info["tests"]} 项 JUnit 测试通过；`test build -x deployToGame` 成功，未部署游戏。',
            '[dev.4 JAR](../build/libs/voicekill-1.0.15-dev.4.jar)，'+f'{info["size"]:,} 字节。',
            f'SHA256：`{info["sha256"]}`。',
            '已检查版本元数据、四个 native/JVM 依赖、未内嵌模型权重；四个匹配类和 HUD 数据包的字节码与 dev.3 完全相同。']
Path('docs/DEV4_VALIDATION.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=True))
assert not lost and not errors and not control_errors, 'regression in paired corpus'
assert not any(r['falseKills'] for r in checks), 'continuous probe misfire; do not conceal'
