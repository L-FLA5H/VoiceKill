param([string]$OutputDirectory = '.local/diagnostics/continuous-replay/corpus')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $root 'synthesis.json')) { throw '语料已存在，请保留原始样本。' }
New-Item -ItemType Directory -Force -Path (Join-Path $root 'raw') | Out-Null
$continuation = '我们继续往前走看看前面的房间里面有什么东西然后打开设置调整声音大小接下来大家一起回到门口继续沿着这条路向前走看看能不能找到新的房间然后我们再回来一起讨论下一步怎么安排'
$cases = @(
    @{id='continuous-tofu';target='豆腐汤宝宝';text=('豆腐汤宝宝正在门口等我们'+$continuation);body=('豆腐汤宝宝正在门口等我们'+$continuation)},
    @{id='continuous-qaq';target='qaq';text=('我说的是 Q A Q 现在已经进房间了'+$continuation);body=('我说的是<say-as interpret-as="characters">QAQ</say-as>现在已经进房间了'+$continuation)},
    @{id='continuous-negative';target=$null;text=$continuation;body=$continuation}
)
$items=[System.Collections.Generic.List[object]]::new()
$synth=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
try {
    $synth.SelectVoice('Microsoft Huihui Desktop')
    foreach($case in $cases) {
        $relative="raw/$($case.id)-0.wav"
        $ssml='<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN">'+$case.body+'</speak>'
        $synth.SetOutputToWaveFile((Join-Path $root $relative),$format)
        $synth.SpeakSsml($ssml);$synth.SetOutputToNull()
        $items.Add([ordered]@{id=$case.id;target=$case.target;text=$case.text;voice='Microsoft Huihui Desktop';kind='continuous-speech';segments=@($case.text);gapMs=0;rawFiles=@($relative);ssml=$ssml})
    }
} finally {$synth.Dispose()}
$json=[ordered]@{source='Chinese TTS continuous-speech stress probe; not human recordings';sampleRate=16000;items=@($items)}|ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $root 'synthesis.json'),$json,[Text.UTF8Encoding]::new($false))
