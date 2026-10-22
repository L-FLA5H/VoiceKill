param([string]$OutputDirectory='.local/diagnostics/dev7/mixed-corpus')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$root=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath (Join-Path $root 'synthesis.json')){throw 'Preserve the existing audio; use another directory.'}
New-Item -ItemType Directory -Force -Path (Join-Path $root 'raw') | Out-Null
$cases=@(
 @{id='mixed-p-name';target='p老板';gold=@('我','老板','p老板');body='今天我和<say-as interpret-as="characters">P</say-as>老板一起回村庄'},
 @{id='mixed-qaq';target='qaq';gold=@('我','qaq');body='今天我和<say-as interpret-as="characters">QAQ</say-as>一起去村庄'},
 @{id='mixed-minecraft';target='minecraft';gold=@('我','minecraft');body='我们在Minecraft里面修一个村庄'},
 @{id='mixed-cpu';target='cpu';gold=@('我','cpu');body='这个<say-as interpret-as="characters">CPU</say-as>有点热我们先休息一下'},
 @{id='mixed-gpu-control';target=$null;gold=@('我');body='这个<say-as interpret-as="characters">GPU</say-as>有点热我们先休息一下'},
 @{id='mixed-chinese-name';target='豆腐汤宝宝';gold=@('我','豆腐汤宝宝');body='今天豆腐汤宝宝和我们一起回村庄'},
 @{id='mixed-save-world';target='world';gold=@('我','save','world');body='我想先save这个world然后回到村庄'},
 @{id='mixed-abc';target='abc';gold=@('我','abc');body='今天我们先聊一下<say-as interpret-as="characters">ABC</say-as>再回村庄'},
 @{id='mixed-mc';target='mc';gold=@('我','mc');body='我们今天一起玩<say-as interpret-as="characters">MC</say-as>然后回到村庄'},
 @{id='mixed-b-station';target='b站';gold=@('b站');body='今天去找<say-as interpret-as="characters">B</say-as>站的朋友'},
 @{id='mixed-d-control';target=$null;gold=@();body='今天去找<say-as interpret-as="characters">D</say-as>站的朋友'},
 @{id='mixed-qa-control';target=$null;gold=@('我');body='我说的是<say-as interpret-as="characters">QA</say-as>'},
 @{id='mixed-boss-control';target='老板';gold=@('老板');body='老板今天不在村庄'},
 @{id='mixed-tofu-control';target=$null;gold=@();body='这碗豆腐汤很好喝'}
)
$synth=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
$items=[System.Collections.Generic.List[object]]::new()
try {
 $synth.SelectVoice('Microsoft Huihui Desktop')
 foreach($case in $cases) {
  $relative='raw/'+$case.id+'-0.wav'
  $ssml='<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN">'+$case.body+'</speak>'
  $synth.SetOutputToWaveFile((Join-Path $root $relative),$format);$synth.SpeakSsml($ssml);$synth.SetOutputToNull()
  $text=[regex]::Replace($case.body,'<[^>]+>','')
  $items.Add([ordered]@{id=$case.id;target=$case.target;expectedWords=@($case.gold);text=$text;voice='Microsoft Huihui Desktop';kind='mixed-sentence';segments=@($text);gapMs=0;rawFiles=@($relative);ssml=$ssml})
 }
} finally {$synth.Dispose()}
$spec=[ordered]@{source='Chinese TTS mixed-language complete-sentence controls; synthetic, not human recordings';sampleRate=16000;items=@($items)}
[IO.File]::WriteAllText((Join-Path $root 'synthesis.json'),($spec|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
