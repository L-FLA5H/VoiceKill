"""Normalize generated PCM, annotate speech bounds, preserve original TTS WAVs."""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import wave

parser = argparse.ArgumentParser()
parser.add_argument('directory', nargs='?', default='.local/diagnostics/nickname-replay/corpus')
root = Path(parser.parse_args().directory)
spec = json.loads((root / 'synthesis.json').read_text(encoding='utf-8'))
rate = 16000

def read_wave(path):
    with wave.open(str(path), 'rb') as stream:
        assert (stream.getframerate(), stream.getnchannels(), stream.getsampwidth()) == (rate, 1, 2)
        return array.array('h', stream.readframes(stream.getnframes()))

def bounds(samples):
    # 10 ms RMS > -50 dBFS, annotation only; this is not a human-labeled speech endpoint.
    active = []
    for start in range(0, len(samples), 160):
        block = samples[start:start + 160]
        rms = math.sqrt(sum(s*s for s in block) / max(1,len(block)))
        if rms > 32768 * 10 ** (-50/20): active.append(start)
    if not active: raise ValueError('no measurable speech')
    return active[0], min(len(samples), active[-1] + 160)

items = []
for item in spec['items']:
    joined = array.array('h', [0]) * 4000  # 250 ms leading silence
    for index, raw in enumerate(item['rawFiles']):
        samples = read_wave(root / raw)
        start, end = bounds(samples)
        if index: joined.extend(array.array('h', [0]) * (item['gapMs'] * 16))
        joined.extend(samples[start:end])
    speech_start, speech_end = bounds(joined)
    joined.extend(array.array('h',[0]) * 48000)  # 3 seconds of endpoint observation
    path = root / (item['id'] + '.wav')
    with wave.open(str(path), 'wb') as stream:
        stream.setparams((1,2,rate,0,'NONE','not compressed'))
        stream.writeframes(joined.tobytes())
    items.append(dict(id=item['id'],target=item['target'],spokenText=item['text'],voice=item['voice'],
                      kind=item['kind'],file=path.name,speechStartSample=speech_start,speechEndSample=speech_end,
                      durationSeconds=len(joined)/rate,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
path = root / 'silence.wav'
with wave.open(str(path),'wb') as stream:
    stream.setparams((1,2,rate,0,'NONE','not compressed'));stream.writeframes(bytes(rate*2*3))
items.append(dict(id='silence',target=None,spokenText='',voice='none',kind='silence',file=path.name,
                  speechStartSample=0,speechEndSample=0,durationSeconds=3,
                  sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
manifest=dict(source=spec['source'],sampleRate=rate,endpointAnnotation='10 ms RMS frames > -50 dBFS; machine-estimated boundary, not human annotation',
              leadSilenceMs=250,tailSilenceMs=3000,items=items)
(root/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Prepared {len(items)} synthetic clips; {sum(i["durationSeconds"] for i in items):.1f}s per replay round')
