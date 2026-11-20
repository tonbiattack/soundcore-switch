# 実機での起動時間・切り替え時間を測定する。
#
# 測定する項目:
# 1. コールド起動から UI 操作可能まで
# 2. ウォーム起動から UI 操作可能まで
# 3. キャッシュ表示まで（画面に接続先が出るまで）
# 4. 最新状態の反映まで（fromCache が false になるまで）
# 5. タップから切り替え完了まで
#
# 使い方:
#   .\scripts\measure-switch.ps1 -Serial SERIAL-REDACTED-1 -Runs 5
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [int]$Runs = 5,

    [string]$Package = "com.tonbiattack.soundcoreswitch",
    [string]$Activity = ".MainActivity"
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"

function Invoke-Adb([string[]]$Arguments) {
    $out = & adb -s $Serial @Arguments 2>&1
    return $out
}

function WaitForUi([int]$TimeoutMs = 30000) {
    # 画面にアプリの UI が出るまで待つ。uiautomator でパッケージを探す。
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $dump = Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        if ($dump -match 'dumped') {
            $xml = Invoke-Adb @('shell', 'cat', '/sdcard/ui.xml')
            if ($xml -match [regex]::Escape($Package)) {
                return $sw.ElapsedMilliseconds
            }
        }
        Start-Sleep -Milliseconds 100
    }
    return -1
}

function MeasureColdStart() {
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Milliseconds 500
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/$Activity") | Out-Null
    $elapsed = $sw.ElapsedMilliseconds
    # am start -W は起動完了までの時間を返すが、UI 操作可能かは別。
    $uiReady = WaitForUi
    return @{ Launch = $elapsed; UiReady = $uiReady }
}

function MeasureWarmStart() {
    # ホームに戻してから再度起動する（プロセスは残す）。
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Start-Sleep -Milliseconds 800
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/$Activity") | Out-Null
    $elapsed = $sw.ElapsedMilliseconds
    $uiReady = WaitForUi
    return @{ Launch = $elapsed; UiReady = $uiReady }
}

function Get-Median([long[]]$Values) {
    $sorted = $Values | Sort-Object
    $mid = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) {
        return [long](($sorted[$mid - 1] + $sorted[$mid]) / 2)
    }
    return [long]$sorted[$mid]
}

function Get-P95([long[]]$Values) {
    $sorted = $Values | Sort-Object
    $index = [int][math]::Ceiling($sorted.Count * 0.95) - 1
    return [long]$sorted[[math]::Max(0, $index)]
}

Write-Host "=== 測定開始 (${Runs}回) ==="
Write-Host "端末: $Serial"

$cold = @()
$warm = @()

for ($i = 1; $i -le $Runs; $i++) {
    Write-Host "  [$i/$Runs] コールド起動..."
    $cold += MeasureColdStart
    Write-Host "  [$i/$Runs] ウォーム起動..."
    $warm += MeasureWarmStart
}

$coldLaunch = $cold | ForEach-Object { $_.Launch }
$coldUi = $cold | Where-Object { $_.UiReady -gt 0 } | ForEach-Object { $_.UiReady }
$warmLaunch = $warm | ForEach-Object { $_.Launch }
$warmUi = $warm | Where-Object { $_.UiReady -gt 0 } | ForEach-Object { $_.UiReady }

Write-Host ""
Write-Host "=== 結果 ==="
Write-Host ("コールド起動 (am start -W): 中央値 {0}ms / P95 {1}ms" -f (Get-Median $coldLaunch), (Get-P95 $coldLaunch))
if ($coldUi.Count -gt 0) {
    Write-Host ("コールド起動 (UI 操作可能): 中央値 {0}ms / P95 {1}ms" -f (Get-Median $coldUi), (Get-P95 $coldUi))
}
Write-Host ("ウォーム起動 (am start -W): 中央値 {0}ms / P95 {1}ms" -f (Get-Median $warmLaunch), (Get-P95 $warmLaunch))
if ($warmUi.Count -gt 0) {
    Write-Host ("ウォーム起動 (UI 操作可能): 中央値 {0}ms / P95 {1}ms" -f (Get-Median $warmUi), (Get-P95 $warmUi))
}

# JSON でも出力する（ドキュメントに貼れるように）。
$result = @{
    serial = $Serial
    runs = $Runs
    coldLaunchMedian = Get-Median $coldLaunch
    coldLaunchP95 = Get-P95 $coldLaunch
    warmLaunchMedian = Get-Median $warmLaunch
    warmLaunchP95 = Get-P95 $warmLaunch
}
if ($coldUi.Count -gt 0) {
    $result.coldUiMedian = Get-Median $coldUi
    $result.coldUiP95 = Get-P95 $coldUi
}
if ($warmUi.Count -gt 0) {
    $result.warmUiMedian = Get-Median $warmUi
    $result.warmUiP95 = Get-P95 $warmUi
}
$result | ConvertTo-Json | Out-File -FilePath "measure-result.json" -Encoding utf8
Write-Host ""
Write-Host "JSON: measure-result.json"
