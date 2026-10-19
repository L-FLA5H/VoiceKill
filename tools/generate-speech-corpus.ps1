param(
    [string]$OutputDirectory = '.local/diagnostics/nickname-replay/corpus'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $root 'synthesis.json')) {
    throw '该语料已存在；请指定新的 OutputDirectory，保留已有回放样本。'
}
New-Item -ItemType Directory -Force -Path (Join-Path $root 'raw') | Out-Null
$voices = [ordered]@{
    huihui = 'Microsoft Huihui Desktop'
    kangkang = 'Microsoft Kangkang'
    yaoyao = 'Microsoft Yaoyao'
}
$targets = [ordered]@{ qaq='qaq'; p='p老板'; tofu='豆腐汤宝宝'; gan='干'; cai='才' }
$items = [System.Collections.Generic.List[object]]::new()
foreach ($voice in $voices.GetEnumerator()) {
    foreach ($target in $targets.GetEnumerator()) {
        $items.Add([ordered]@{id="$($voice.Key)-$($target.Key)";voice=$voice.Value;
            target=$target.Value;text=$target.Value;kind='canonical';segments=@($target.Value);gapMs=0})
    }
}
$items.Add([ordered]@{id='qaq-phonetic';voice=$voices.huihui;target='qaq';text='扣哎扣';kind='pronunciation';segments=@('扣哎扣');gapMs=0})
$items.Add([ordered]@{id='qaq-english-spelling';voice='Microsoft Zira Desktop';target='qaq';text='Q A Q';kind='english-spelling';segments=@('Q A Q');gapMs=0})
$items.Add([ordered]@{id='p-phonetic';voice=$voices.huihui;target='p老板';text='劈老板';kind='pronunciation';segments=@('劈老板');gapMs=0})
$items.Add([ordered]@{id='tofu-split';voice=$voices.huihui;target='豆腐汤宝宝';text='豆腐汤 / 宝 / 宝';kind='split';segments=@('豆腐汤','宝','宝');gapMs=1000})
$items.Add([ordered]@{id='gan-context';voice=$voices.huihui;target='干';text='干活';kind='context';segments=@('干活');gapMs=0})
$items.Add([ordered]@{id='cai-context';voice=$voices.huihui;target='才';text='刚才';kind='context';segments=@('刚才');gapMs=0})
$synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
try {
    $synth.Rate = 0
    foreach ($item in $items) {
        $synth.SelectVoice($item.voice)
        $rawFiles = [System.Collections.Generic.List[string]]::new()
        for ($i=0; $i -lt $item.segments.Count; $i++) {
            $relative = "raw/$($item.id)-$i.wav"
            $synth.SetOutputToWaveFile((Join-Path $root $relative), $format)
            $synth.Speak($item.segments[$i])
            $synth.SetOutputToNull()
            $rawFiles.Add($relative)
        }
        $item.rawFiles = @($rawFiles)
        Write-Output "已合成 $($item.id)"
    }
} finally { $synth.Dispose() }
$json = [ordered]@{source='Windows System.Speech synthetic speech; not human recordings';
    sampleRate=16000;rate=0;items=@($items)} | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $root 'synthesis.json'),$json,[Text.UTF8Encoding]::new($false))
