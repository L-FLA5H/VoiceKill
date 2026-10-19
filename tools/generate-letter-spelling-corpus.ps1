param([string]$OutputDirectory = '.local/diagnostics/letter-spelling/corpus', [switch]$IncludeGeneralization)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $root 'synthesis.json')) { throw '语料已存在，请使用新目录以保留原始音频。' }
New-Item -ItemType Directory -Force -Path (Join-Path $root 'raw') | Out-Null
$voices = [ordered]@{ huihui='Microsoft Huihui Desktop'; kangkang='Microsoft Kangkang'; yaoyao='Microsoft Yaoyao' }
$cases = @(
    @{id='qaq'; target='qaq'; text='Q A Q'; body='<say-as interpret-as="characters">QAQ</say-as>'},
    @{id='p'; target='p老板'; text='P老板'; body='<say-as interpret-as="characters">P</say-as>老板'},
    @{id='qaq-context'; target='qaq'; text='我说的是 Q A Q'; body='我说的是<say-as interpret-as="characters">QAQ</say-as>'},
    @{id='p-context'; target='p老板'; text='我说的是 P老板'; body='我说的是<say-as interpret-as="characters">P</say-as>老板'},
    @{id='qq'; target=$null; text='Q Q'; body='<say-as interpret-as="characters">QQ</say-as>'},
    @{id='qa'; target=$null; text='Q A'; body='<say-as interpret-as="characters">QA</say-as>'},
    @{id='p-only'; target=$null; text='P'; body='<say-as interpret-as="characters">P</say-as>'},
    @{id='boss'; target=$null; text='老板'; body='老板'}
)
if ($IncludeGeneralization) {
    foreach ($letters in @('ABC','CPU','MC')) {
        $cases += @{id=$letters.ToLower();target=$letters.ToLower();text=($letters.ToCharArray() -join ' ');body="<say-as interpret-as=`"characters`">$letters</say-as>"}
    }
    $cases += @{id='b-station';target='b站';text='B站';body='<say-as interpret-as="characters">B</say-as>站'}
    foreach ($letters in @('ABD','GPU','MP')) {
        $cases += @{id=$letters.ToLower();target=$null;text=($letters.ToCharArray() -join ' ');body="<say-as interpret-as=`"characters`">$letters</say-as>"}
    }
    $cases += @{id='d-station';target=$null;text='D站';body='<say-as interpret-as="characters">D</say-as>站'}
}
$items = [System.Collections.Generic.List[object]]::new()
$synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
try {
    foreach ($voice in $voices.GetEnumerator()) {
        $synth.SelectVoice($voice.Value)
        foreach ($case in $cases) {
            $id = "$($voice.Key)-$($case.id)"
            $relative = "raw/$id-0.wav"
            $ssml = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN">' + $case.body + '</speak>'
            $synth.SetOutputToWaveFile((Join-Path $root $relative),$format)
            $synth.SpeakSsml($ssml)
            $synth.SetOutputToNull()
            $items.Add([ordered]@{id=$id;voice=$voice.Value;target=$case.target;text=$case.text;
                kind='explicit-letter-spelling';segments=@($case.text);gapMs=0;rawFiles=@($relative);ssml=$ssml})
        }
    }
} finally { $synth.Dispose() }
$json = [ordered]@{source='Windows Chinese TTS with explicit SSML letter spelling; synthetic diagnostic proxy, not human Mandarin-accent recordings';sampleRate=16000;rate=0;items=@($items)} | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $root 'synthesis.json'),$json,[Text.UTF8Encoding]::new($false))
Write-Output "已生成 $($items.Count) 段中文音色字母拼读诊断音频。"
