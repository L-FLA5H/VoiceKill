# 一键 dump Shriek 的接口签名（不用装反编译器）
# 用法：.\inspect-shriek.ps1 -Jar "D:\...\mods\shriek-fabric-1.1.22.jar"
# 输出：docs\shriek-javap.txt

param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$OutFile = "$PSScriptRoot\..\docs\shriek-javap.txt"
)

if (-not (Test-Path $Jar)) { Write-Error "找不到 jar：$Jar"; exit 1 }

# 找 javap：优先 JAVA_HOME，其次 PATH
$javap = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\javap.exe"
    if (Test-Path $candidate) { $javap = $candidate }
}
if (-not $javap) { $javap = (Get-Command javap -ErrorAction SilentlyContinue).Source }
if (-not $javap) { Write-Error "找不到 javap，请先装 JDK 21 或设置 JAVA_HOME"; exit 1 }

$classes = @(
    "com.pryzmm.api.ShriekApi",
    "com.pryzmm.api.events.ServerPlayerTalkEvent",
    "com.pryzmm.client.event.EventHandler",
    "com.pryzmm.speech.SpeechRecognizer",
    "com.pryzmm.speech.MicrophoneHandler",
    "com.pryzmm.networking.ShriekPackets",
    "com.pryzmm.networking.packets.PlayerSpeakPacket",
    "com.pryzmm.ShriekConstants"
)

$outDir = Split-Path $OutFile -Parent
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

"# Shriek javap dump" | Set-Content -Path $OutFile -Encoding UTF8
"# jar : $Jar"          | Add-Content -Path $OutFile -Encoding UTF8
"# time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Path $OutFile -Encoding UTF8
""                      | Add-Content -Path $OutFile -Encoding UTF8

foreach ($c in $classes) {
    "# ===== $c =====" | Add-Content -Path $OutFile -Encoding UTF8
    & $javap -p -classpath $Jar $c 2>&1 | Add-Content -Path $OutFile -Encoding UTF8
    "" | Add-Content -Path $OutFile -Encoding UTF8
}

Write-Host "已输出：$OutFile"
Write-Host "想同时看字节码，把 -p 改成 -c -p（文件会大很多）"
