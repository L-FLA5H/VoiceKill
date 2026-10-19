"""Compare frozen dev.2 and dev.3 replay evidence; fail on added misfires or early hits."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('--baseline', default='.local/diagnostics/nickname-replay/baseline-dev2/results.json')
parser.add_argument('--candidate', default='.local/diagnostics/nickname-replay/optimized-dev3/results.json')
parser.add_argument('--controls', default='.local/diagnostics/nickname-replay/optimized-dev3/negative-results.json')
parser.add_argument('--output', default='docs/REPLAY_OPTIMIZATION.md')
args = parser.parse_args()
old = json.loads(Path(args.baseline).read_text(encoding='utf-8'))
new = json.loads(Path(args.candidate).read_text(encoding='utf-8'))
key = lambda row: (row['round'], row['clip']['id'])
before = {key(row): row for row in old['rows']}
after = {key(row): row for row in new['rows']}
assert len(before) == len(after) == 66 and before.keys() == after.keys(), 'incomplete paired replays'
for pair, row in after.items():
    clip = row['clip']
    assert clip['sha256'] == before[pair]['clip']['sha256'], 'different audio'
    wav = Path('.local/diagnostics/nickname-replay/corpus') / clip['file']
    assert hashlib.sha256(wav.read_bytes()).hexdigest() == clip['sha256'], wav
for record in json.loads(Path('.local/diagnostics/nickname-replay/baseline-dev2/matching-hashes.json').read_text(encoding='utf-8-sig')):
    assert hashlib.sha256(Path(record['Path']).read_bytes()).hexdigest().upper() == record['Hash'], record['Path']

def latencies(rows):
    return [r['hits'][r['clip']['target']]['afterSpeechEndMs'] for r in rows if r['targetHit']]

def delay(rows):
    values = latencies(rows)
    return '—' if not values else f'{statistics.median(values)/1000:.2f} 秒'

def count(rows):
    return f"{sum(r['targetHit'] for r in rows)}/{len(rows)}"

def select(data, target=None, clip_id=None, canonical=False):
    return [r for r in data if (target is None or r['clip'].get('target') == target)
            and (clip_id is None or r['clip']['id'] == clip_id)
            and (not canonical or r['clip']['kind'] == 'canonical')]

lost = [pair for pair in before if before[pair]['targetHit'] and not after[pair]['targetHit']]
new_errors = [(pair, danger) for pair, row in after.items() for danger in row['hits']
              if danger != row['clip'].get('target') and danger not in before[pair]['hits']]
early = [(pair, danger) for pair, row in after.items() for danger, hit in row['hits'].items()
         if hit['afterSpeechEndMs'] < 0]
common = [pair for pair in before if before[pair]['targetHit'] and after[pair]['targetHit']]
common_old = [before[pair] for pair in common]
common_new = [after[pair] for pair in common]
negative = sum(5 - bool(row['clip'].get('target')) for row in after.values())
controls = json.loads(Path(args.controls).read_text(encoding='utf-8'))
assert len(controls['rows']) == 42, 'negative controls must be 14 clips x 3 rounds'
control_errors = sum(r['falseKillCount'] for r in controls['rows'])
summary = dict(sameAudioHashes=True,matchingRuleHashesUnchanged=True,replays=len(after),
               baselineHits=sum(r['targetHit'] for r in before.values()),candidateHits=sum(r['targetHit'] for r in after.values()),
               negativeDecisions=negative,baselineMisfires=sum(r['falseKillCount'] for r in before.values()),
               candidateMisfires=sum(r['falseKillCount'] for r in after.values()),
               addedMisfires=new_errors,lostBaselineHits=lost,earlyHits=early,
               controlReplays=len(controls['rows']),controlNegativeDecisions=len(controls['rows'])*5,controlMisfires=control_errors,
               commonHitPairs=len(common),baselineCommonMedianMs=statistics.median(latencies(common_old)),
               candidateCommonMedianMs=statistics.median(latencies(common_new)))
summary_path=Path(args.candidate).with_name('comparison.json')
summary_path.write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['# 中英昵称识别与延迟优化复测', '',
       '对比：1.0.15-dev.2 → 1.0.15-dev.3。所有音频 SHA256 与原 22 段样本一致，每段仍实时回放三轮。',
       'WordMatcher、FuzzyMatch、WordAliases、SpeechWindow 四个匹配源文件 SHA256 完全不变。', '',
       '**本报告使用合成语音、生产 Decoder/CaptureSession 和匹配代码；没有启动 Minecraft 或实际处决玩家。**', '',
       '## 同组音频对比', '',
       '| 原词 | 原命中 | 新命中 | 原延迟中位数 | 新延迟中位数 |', '| --- | --- | --- | --- | --- |']
for target in ['qaq','p老板','豆腐汤宝宝','干','才']:
    a=select(old['rows'],target=target,canonical=True);b=select(new['rows'],target=target,canonical=True)
    lines.append(f'| {target} | {count(a)} | {count(b)} | {delay(a)} | {delay(b)} |')
lines+=['', '延迟仅统计成功命中的回放；不同音色新增命中会改变单词内的统计群体。', '',
        f'严格按相同样本/轮次配对，旧新均命中的 {len(common)} 次：中位延迟 {delay(common_old)} → {delay(common_new)}。', '',
        '匹配目标始终使用规范名称 `qaq`、`p老板` 等。下面的汉字是合成输入读法，`Q A Q` 是字母拼读输入；不代表新增了危险词，也不代表原始小写 qaq 合成音已识别成功。', '',
        '| 合成输入读法 | 匹配目标 | 原命中 | 新命中 | 新延迟 |', '| --- | --- | --- | --- | --- |']
for clip_id in ['qaq-phonetic','qaq-english-spelling','p-phonetic','tofu-split','gan-context','cai-context']:
    a=select(old['rows'],clip_id=clip_id);b=select(new['rows'],clip_id=clip_id)
    lines.append(f"| {a[0]['clip']['spokenText']} | {a[0]['clip']['target']} | {count(a)} | {count(b)} | {delay(b)} |")
lines+=['', '## 误触发与提前触发', '',
        f'- 原语料：{summary["candidateMisfires"]}/{negative} 个负判定；基线 {summary["baselineMisfires"]}/{negative}。',
        f'- 新增误触发 {len(new_errors)}；丢失基线命中 {len(lost)}；整段说完前提前触发 {len(early)}。',
        f'- 额外近邻对照：{len(controls["rows"])} 次实时回放、{len(controls["rows"])*5} 个负判定，误触发 {control_errors}。',
        '- 额外对照与原语料分开统计，包含“老板/新老板/替老板/Q A/QQ/P/你好/今天/猜猜看/菜市场/发财/我回来了/天气很好”和静音。',
        '- 额外对照的旧贪心、束搜索及不同提示强度快速探测也分别为零误触发；这些是合成语音的有限观察，不是对所有真人输入的保证。', '',
        '## 采用的改动', '',
        '- 识别侧使用 4 路有限束搜索；由游戏下发的当前词中，只取带 ASCII 字母的多字昵称作为 ASR 提示，中文单字不进入提示表。',
        '- 识别提示强度限制在 1.5 以内；词表仅用原名、英语字母拼读及既有完整读音别名，不修改服务端匹配。',
        '- 采音分块由 128ms 改为 40ms。语音段至少 1.2 秒时允许 0.35 秒静音确认，短段保留 0.6 秒确认，15 秒长段上限保留。',
        '- 每段和词表变化时重建语音流，不继承旧段的编码/搜索状态；词表顺序变化不重置。',
        '- 新增并校验 12,564 字节 bpe.vocab，模型权重及运行库版本不变。', '',
        '## 未采用的方案与限制', '',
        '- 直接将所有语音的静音确认缩短到 0.25～0.45 秒，会产生截词或提前触发，未采用。',
        '- 沿用旧识别流状态的部分候选会在一次“宝”后输出“宝宝”，未采用。',
        '- 更高提示强度没有改善这组 qaq 的中文读法，未扩大强度；SenseVoice 离线对照也没有优于当前候选，未切换或随模组分发。',
        '- 原始小写 qaq 的三种 TTS 发音只有约 0.25～0.36 秒，仍不能稳定识别为三个字母。中文读法仍可能输出“口爱扣”，保留漏判，不把它硬映射为昵称。',
        '- 延迟从机器估计的发音结束算到匹配命中，未包含游戏 tick、网络、真实采集驱动及模型初始化。真实录音、Mac native 和游戏帧率影响仍待验收。', '',
        '## 逐段识别原文', '', '| 样本 | 原结果 | 新结果 |', '| --- | --- | --- |']
for clip_id in dict.fromkeys(r['clip']['id'] for r in old['rows']):
    texts=lambda data:'；'.join(dict.fromkeys(' / '.join(e['candidates'][0] for e in r['events']) or '（无最终文本）' for r in select(data,clip_id=clip_id)))
    lines.append(f'| {clip_id} | {texts(old["rows"])} | {texts(new["rows"])} |')
lines+=['', '## 证据和复现', '',
        '[基线 JSON](../.local/diagnostics/nickname-replay/baseline-dev2/results.json) · [新版 JSON](../.local/diagnostics/nickname-replay/optimized-dev3/results.json) · [额外负样本 JSON](../.local/diagnostics/nickname-replay/optimized-dev3/negative-results.json)',
        '[原音频及试听索引](NICKNAME_REPLAY.md) · [消融实验](../.local/diagnostics/nickname-replay/optimization/)', '',
        '```powershell',
        ".\\gradlew.bat replayNicknames '-PreplayOutput=.local/diagnostics/nickname-replay/optimized-dev3/results.json' -x deployToGame --console=plain",
        ".\\gradlew.bat replayNicknames '-PcorpusDir=.local/diagnostics/nickname-replay/negative-controls' '-PreplayOutput=.local/diagnostics/nickname-replay/optimized-dev3/negative-results.json' -x deployToGame --console=plain",
        'python tools/compare-nickname-replay.py', '```', '',
        '不重新生成 WAV；新增对照仅本地保存。比较脚本会检查音频哈希、四个匹配源文件哈希、新增误触发、丢失基线命中和提前触发。']
Path(args.output).write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=True))
if new_errors or lost or early or control_errors:
    raise SystemExit('Replay acceptance failed; see comparison.json')
