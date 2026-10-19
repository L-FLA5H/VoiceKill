import argparse
import collections
import json
from pathlib import Path
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('results', nargs='?', default='.local/diagnostics/nickname-replay/results.json')
parser.add_argument('--output', default='docs/NICKNAME_REPLAY.md')
args = parser.parse_args()
source = Path(args.results)
report = json.loads(source.read_text(encoding='utf-8'))
rows = report['rows']
targets = ['qaq','p老板','豆腐汤宝宝','干','才']

def primary(row):
    return ' / '.join(event['candidates'][0] for event in row['events']) or '（无最终文本）'

def delay(group):
    values = [row['hits'][row['clip']['target']]['afterSpeechEndMs'] for row in group if row['targetHit']]
    if not values: return '—（未命中）'
    # Speech endpoint is machine-estimated; do not imply submillisecond annotation accuracy.
    rounded = lambda value: int(round(value / 10) * 10)
    return f"约 {rounded(statistics.median(values))} ms（{rounded(min(values))}～{rounded(max(values))}）"

canonical = [row for row in rows if row['clip']['kind'] == 'canonical']
negative_count = sum(5 - (1 if row['clip'].get('target') else 0) for row in rows)
false_kills = sum(row['falseKillCount'] for row in rows)
positives = [row for row in rows if row['clip'].get('target')]
summary = dict(completedReplays=len(rows),positiveReplays=len(positives),targetHits=sum(row['targetHit'] for row in positives),
               negativeDecisions=negative_count,falseKillDecisions=false_kills,targets={})
lines = ['# 五个危险词的合成语音回放', '',
         '版本：1.0.15-dev.2，Zipformer 小型中英双语 INT8，CPU 1 线程。', '',
         '**这是 Windows 本地 TTS 合成语音，不是真人、麦克风或 Minecraft 联机验收。**', '',
         '三个登记的中文音色各读五个原词，补充中文谐音、英语字母拼读、断句、上下文和纯静音。',
         '后续哈希审计发现 Kangkang/Yaoyao 的对应 WAV 完全相同：22 个文件只有 17 种独立波形。下列数字保留原回放次数，不能当作三个独立口音的准确率。',
         '每个样本实时节奏回放三轮；模型初始化不计入触发延迟。每次分别将五个词作为唯一危险词，来源为 manual，不额外添加别名。', '',
         f"完成 {len(rows)} 次回放；正样本命中 {summary['targetHits']}/{len(positives)}，误触发 {false_kills}/{negative_count} 个负判定。", '',
         '## 原词结果', '', '| 危险词 | 命中回放 | 命中后的延迟中位数及范围 |', '| --- | --- | --- |']
for target in targets:
    group = [row for row in canonical if row['clip']['target'] == target]
    hits = sum(row['targetHit'] for row in group)
    summary['targets'][target] = dict(hits=hits,total=len(group),latency=delay(group))
    lines.append(f'| {target} | {hits}/{len(group)} | {delay(group)} |')
lines += ['', '延迟仅统计命中的回放。负值表示在整段刺激说完之前已经触发；“未命中”没有触发延迟。', '',
          '## 每种音色的识别原文', '', '| 样本 | 原文（同一音色多轮不同结果均列出） | 应命中词 | 命中 |', '| --- | --- | --- | --- |']
by_clip = collections.defaultdict(list)
for row in rows: by_clip[row['clip']['id']].append(row)
for clip_id, group in by_clip.items():
    if group[0]['clip']['kind'] != 'canonical': continue
    texts = list(dict.fromkeys(primary(row) for row in group))
    lines.append(f"| {clip_id} | {'；'.join(texts)} | {group[0]['clip']['target']} | {sum(r['targetHit'] for r in group)}/{len(group)} |")
lines += ['', '## 补充读法和对照', '', '| 样本 | 实际合成文本 | 识别结果 | 命中 | 延迟 |', '| --- | --- | --- | --- | --- |']
for clip_id, group in by_clip.items():
    clip = group[0]['clip']
    if clip['kind'] == 'canonical': continue
    texts = list(dict.fromkeys(primary(row) for row in group))
    lines.append(f"| {clip_id} | {clip['spokenText'] or '（纯静音）'} | {'；'.join(texts)} | {sum(r['targetHit'] for r in group)}/{len(group)} | {delay(group)} |")
lines += ['', '## 误触发与提前触发', '']
for spoken, danger in [('才','干'),('干','才')]:
    group = [row for row in positives if row['clip']['target'] == spoken]
    count = sum(danger in row['hits'] for row in group)
    lines.append(f'- 说“{spoken}”时，危险词为“{danger}”：{count}/{len(group)} 次误触发。')
early = []
for row in rows:
    for target, hit in row['hits'].items():
        if target != row['clip'].get('target'):
            lines.append(f"- 误触发：{row['clip']['id']} 第 {row['round']+1} 轮 → {target}，证据“{hit['evidence']}”，方式 {hit['mode']}。")
        if hit['afterSpeechEndMs'] < 0:
            early.append(dict(clip=row['clip']['id'],round=row['round'],target=target,**hit))
            lines.append(f"- 提前触发：{row['clip']['id']} 第 {row['round']+1} 轮 → {target}，距整段结束 {hit['afterSpeechEndMs']:.0f} ms，证据“{hit['evidence']}”，方式 {hit['mode']}。")
summary['earlyHits'] = early
if not early:
    lines.append('- 本轮没有观察到整段刺激说完之前的提前触发。')
lines.append('- 断句样本输入为“豆腐汤 / 宝 / 宝”，模型实际返回“豆腐汤 / 饱饱”两个最终结果，验证了跨两个识别结果的拼接，不能视为三包逐字上报验证。')
lines += ['', '## 试听与核查', '']
for clip_id, group in by_clip.items():
    clip = group[0]['clip']
    lines.append(f"- [{clip_id}](../.local/diagnostics/nickname-replay/corpus/{clip['file']})：{clip['spokenText'] or '纯静音'}；{clip['voice']}。")
lines += ['', '## 测量边界与复现', '',
          '- 实际链路：16kHz 单声道文件源 → 4096 字节实时分块 → 生产 CaptureSession / ZipformerDecoder → 生产 SpeechWindow / WordMatcher。',
          '- 终点以 10ms RMS 帧高于 -50dBFS 估计；10ms 是分帧粒度，不代表人工发音边界只有 ±10ms 误差。',
          '- 触发延迟包含分块等待、模型端点和本机匹配，未包含客户端 tick、网络和 Minecraft 服务端调度，也没有实际处决玩家。',
          '- 原始字母写法的 TTS 发音可能与玩家口头读法不同，应结合补充拼读及 WAV 听辨，不将这些样本当作总体识别率。',
          '- 纯静音和其他词作为负样本；观察零误触发不等于真实游戏不会误杀。',
          '- 每段末尾追加 3 秒静音；未在该观察窗口命中即记为漏判。', '',
          '```powershell', '.\\tools\\generate-speech-corpus.ps1', 'python tools/prepare-speech-corpus.py',
          '.\\gradlew.bat replayNicknames -x deployToGame --console=plain',
          'python tools/summarize-nickname-replay.py', '```', '',
          '生成器拒绝覆盖已有 synthesis.json；已生成语料时跳过前两条命令，重用同一 WAV。另建语料时使用新的 OutputDirectory。', '',
          '[原始 JSON](../.local/diagnostics/nickname-replay/results.json) · [音频清单和哈希](../.local/diagnostics/nickname-replay/corpus/manifest.json)', '',
          '这些音频与原始结果仅本地保存，未纳入 Git。']
output = Path(args.output)
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text('\n'.join(lines)+'\n',encoding='utf-8')
source.with_name('summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Report: {output}; replays={len(rows)}; false kills={false_kills}/{negative_count}')
