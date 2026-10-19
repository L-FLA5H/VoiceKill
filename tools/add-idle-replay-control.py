"""Add a leading-idle control without modifying the source speech samples."""
import hashlib
import json
from pathlib import Path
import wave

root=Path('.local/diagnostics/continuous-replay/corpus')
manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
clip_id='idle-then-continuous-tofu'
if not any(c['id']==clip_id for c in manifest['items']):
    original=next(c for c in manifest['items'] if c['id']=='continuous-tofu')
    with wave.open(str(root/original['file']),'rb') as stream:
        params=stream.getparams();pcm=stream.readframes(stream.getnframes())
    idle_samples=4800*16
    path=root/(clip_id+'.wav')
    assert not path.exists(), 'preserve existing audio'
    with wave.open(str(path),'wb') as stream:
        stream.setparams(params);stream.writeframes(bytes(idle_samples*2)+pcm)
    item=dict(original,id=clip_id,file=path.name,kind='idle-then-continuous',
              speechStartSample=original['speechStartSample']+idle_samples,
              speechEndSample=original['speechEndSample']+idle_samples,
              durationSeconds=original['durationSeconds']+4.8,
              sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    manifest['items'].append(item)
    (root/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Continuous corpus includes idle-then-speech control')
