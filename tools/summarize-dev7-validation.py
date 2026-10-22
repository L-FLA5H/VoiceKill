"""Summarize fixed-WAV dev.7 evidence; no inference, downloads or packaging."""
from pathlib import Path
import json,statistics,hashlib,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
folder=root/'.local/diagnostics/dev7'
def read(path):return json.loads((root/path).read_text(encoding='utf-8'))
def expected(row):return row.get('expectedWords',[row['clip']['target']] if row['clip'].get('target') else [])
def pairs(data,positive):return {(r['clip']['id'],word) for r in data['rows'] for word in r['hits'] if (word in expected(r))==positive}
def compare(before,after):
 a={r['clip']['id']:r['clip']['sha256'] for r in before['rows']};b={r['clip']['id']:r['clip']['sha256'] for r in after['rows']}
 assert a==b,'WAV identifiers or hashes changed'
 for report in [before,after]:
  for row in report['rows']:
   clip=row['clip']; candidates=list((root/'.local/diagnostics').glob('**/'+clip['file']))
   assert any(hashlib.sha256(p.read_bytes()).hexdigest()==clip['sha256'] for p in candidates),clip['file']
 return dict(newFalse=sorted(pairs(after,False)-pairs(before,False)),lost=sorted(pairs(before,True)-pairs(after,True)),gained=sorted(pairs(after,True)-pairs(before,True)))
reports={name:read(f'.local/diagnostics/dev7/final-{name}.json') for name in ['original','negative','letters','sentences','continuous','mixed']}
base={'original':read('.local/diagnostics/dev5/original.json'),'negative':read('.local/diagnostics/dev5/negative.json'),'letters':read('.local/diagnostics/dev5/general-realtime.json'),'mixed':read('.local/diagnostics/dev7/mixed-baseline.json')}
comparisons={name:compare(before,reports[name]) for name,before in base.items()}
assert all(not value['newFalse'] and not value['lost'] for value in comparisons.values()),comparisons
rows=[];metrics={}
for name,d in reports.items():
 positive=sum(len(expected(r)) for r in d['rows']);hit=sum(len(set(expected(r))&set(r['hits'])) for r in d['rows']);false=sum(r['falseKillCount'] for r in d['rows']);negative=len(d['rows'])*len(d['evaluatedWords'])-positive
 delays=[r['hits'][r['clip']['target']]['afterSpeechEndMs'] for r in d['rows'] if r['targetHit']]
 metric=dict(clips=len(d['rows']),hits=hit,expected=positive,false=false,negativeChecks=negative,realtime=d['realtime'])
 if d['realtime'] and delays and name!='continuous':metric.update(medianDelayMs=statistics.median(delays),maxDelayMs=max(delays))
 if name=='continuous':
  metric['settlements']=[dict(clip=r['clip']['id'],firstSecondsFromSpeechStart=(r['events'][0]['wallMs']/1000-r['clip']['speechStartSample']/16000) if r['events'] else None,maxGapSeconds=max([(b['wallMs']-a['wallMs'])/1000 for a,b in zip(r['events'],r['events'][1:])],default=None)) for r in d['rows']]
 metrics[name]=metric
 rows.append(f"| {name} | {len(d['rows'])} | {hit}/{positive} | {false}/{negative} | {'实时' if d['realtime'] else '加速，仅能力'} |")
watchdog=read('.local/diagnostics/dev7/final-watchdog.json')
summary=dict(metrics=metrics,comparisons=comparisons,watchdog=watchdog)
(folder/'final-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
xml=[ET.parse(p).getroot() for p in (root/'build/test-results/test').glob('TEST-*.xml')]
tests=sum(int(x.attrib['tests']) for x in xml);assert all(int(x.attrib['failures'])==0 and int(x.attrib['errors'])==0 for x in xml)
sentence_rows=[]
baseline_sentence=read('.local/diagnostics/sentence-profile/baseline.json')
before={r['clip']['id']:r for r in baseline_sentence['rows']}
for r in reports['sentences']['rows']:
 assert before[r['clip']['id']]['clip']['sha256']==r['clip']['sha256']
 sentence_rows.append(f"| {r['clip']['id']} | {len(before[r['clip']['id']]['events'])} | {len(r['events'])} | {'命中' if r['targetHit'] else '负例无误触发'} |")
# Keep curated settings/release notes intact; this tool only emits machine-readable evidence.
print(json.dumps(summary,ensure_ascii=True))
