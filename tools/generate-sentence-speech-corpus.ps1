param([string]$OutputDirectory='.local/diagnostics/sentence-profile/corpus')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$root=[IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $root 'synthesis.json')) { throw 'Preserve existing corpus.' }
New-Item -ItemType Directory -Force -Path (Join-Path $root 'raw') | Out-Null
$cases=@(
 @{id='complete-sentence';target='豆腐汤宝宝';segments=@('今天我们先回到村庄门口然后一起沿着这条路向前走看看豆腐汤宝宝在哪里');gap=0},
 @{id='breath-in-name';target='豆腐汤宝宝';segments=@('豆腐汤','宝宝已经准备好和我们一起回家看看箱子里面还有什么东西');gap=450},
 @{id='complete-negative';target=$null;segments=@('我们先回到村庄门口然后一起沿着这条路向前走看看里面有什么东西');gap=0}
)
$synth=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
$items=[System.Collections.Generic.List[object]]::new()
try {
 $synth.SelectVoice('Microsoft Huihui Desktop')
 foreach ($case in $cases) {
  $files=@()
  for($i=0;$i -lt $case.segments.Count;$i++) {
   $relative="raw/$($case.id)-$i.wav"
   $synth.SetOutputToWaveFile((Join-Path $root $relative),$format)
   $synth.Speak($case.segments[$i]);$synth.SetOutputToNull()
   $files+=$relative
  }
  $items.Add([ordered]@{id=$case.id;target=$case.target;text=($case.segments -join ' ');voice='Microsoft Huihui Desktop';kind='sentence-boundary';segments=$case.segments;gapMs=$case.gap;rawFiles=$files})
 }
} finally {$synth.Dispose()}
$spec=[ordered]@{source='Synthetic full-sentence and 450ms breath probes; not human recordings';sampleRate=16000;items=@($items)}
[IO.File]::WriteAllText((Join-Path $root 'synthesis.json'),($spec|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
