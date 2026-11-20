# 切り替え操作の所要時間を測定する。
#
# 測定する項目:
# 1. タップから切り替え完了（成功バナー表示）まで
# 2. タップから一覧が新しい状態になるまで
#
# 使い方:
#   .\scripts\measure-switch-action.ps1 -Serial 6ad7aa65 -Runs 3
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [int]$Runs = 3,

    [string]$Package = "com.tonbiattack.soundcoreswitch"
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"

function Invoke-Adb([string[]]$Arguments) {
    return & adb -s $Serial @Arguments 2>&1
}

function Get-UiXml() {
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    return (Invoke-Adb @('shell', 'cat', '/sdcard/ui.xml')) -join ''
}

function Find-Bounds([string]$Xml, [string]$Text) {
    # text="..." または content-desc="..." に一致するノードの bounds を返す。
    $pattern = '(?:text|content-desc)="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($Xml, $pattern)
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return @{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Wait-ForText([string]$Text, [int]$TimeoutMs = 30000) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $xml = Get-UiXml
        if ($xml -match [regex]::Escape($Text)) {
            return $sw.ElapsedMilliseconds
        }
        Start-Sleep -Milliseconds 150
    }
    return -1
}

# 純正アプリは RFCOMM を占有するため、測定前に停止する。
Invoke-Adb @('shell', 'am', 'force-stop', 'com.oceanwing.soundcore') | Out-Null
Start-Sleep -Seconds 2

Write-Host "=== 切り替え測定開始 (${Runs}回) ==="

# アプリを起動して接続先が出るまで待つ。
Invoke-Adb @('shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
$ready = Wait-ForText '接続先' 30000
if ($ready -lt 0) {
    Write-Error "接続先の一覧が表示されませんでした"
    exit 1
}
Start-Sleep -Seconds 3

$results = @()
for ($i = 1; $i -le $Runs; $i++) {
    $xml = Get-UiXml

    # 切断中の端末を1つ探す（接続中でないもの）。
    $disconnected = [regex]::Matches($xml, 'content-desc="([^"]+) 切断中"')
    if ($disconnected.Count -eq 0) {
        Write-Host "  [$i/$Runs] 切断中の端末が無いため中断"
        break
    }
    $name = $disconnected[0].Groups[1].Value
    $bounds = Find-Bounds $xml "$name 切断中"
    if (-not $bounds) {
        Write-Host "  [$i/$Runs] 座標が取れないため中断"
        break
    }

    Write-Host "  [$i/$Runs] $name へ切り替え..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Adb @('shell', 'input', 'tap', $bounds.X, $bounds.Y) | Out-Null

    # バナーは数秒で消える。uiautomator dump は1回あたり数百msかかるため、
    # タップ直後から連続で確認する。最初の dump で取れなければ次の dump を待つ。
    $done = -1
    $current = ''
    $success = $false
    while ($sw.ElapsedMilliseconds -lt 30000) {
        $current = Get-UiXml
        if ($current -match 'に切り替えました') {
            $success = $true
            $done = $sw.ElapsedMilliseconds
            break
        }
        if ($current -match '切り替えを確認できませんでした' -or
            $current -match '応答がありませんでした' -or
            $current -match '切り替えできません' -or
            $current -match '切ってしまいました') {
            $done = $sw.ElapsedMilliseconds
            break
        }
    }
    $results += @{ Target = $name; Elapsed = $done; Success = $success }
    Write-Host ("    → {0} ({1}ms)" -f $(if ($success) { "成功" } else { "失敗" }), $done)

    # バナーを閉じて次の測定へ。
    $close = Find-Bounds $current '閉じる'
    if ($close) {
        Invoke-Adb @('shell', 'input', 'tap', $close.X, $close.Y) | Out-Null
    }
    # イヤホンが切断・接続の処理を終えるまで待つ。
    # 連続で切り替えると応答が返らなくなる（実機で確認）。
    Start-Sleep -Seconds 5
}

$successful = $results | Where-Object { $_.Success -and $_.Elapsed -gt 0 }
if ($successful.Count -gt 0) {
    $times = $successful | ForEach-Object { [long]$_.Elapsed } | Sort-Object
    $mid = [int][math]::Floor($times.Count / 2)
    $median = if ($times.Count % 2 -eq 0) { [long](($times[$mid - 1] + $times[$mid]) / 2) } else { [long]$times[$mid] }
    $p95Index = [int][math]::Ceiling($times.Count * 0.95) - 1
    $p95 = [long]$times[[math]::Max(0, $p95Index)]

    Write-Host ""
    Write-Host "=== 結果 ==="
    Write-Host ("切り替え完了まで: 中央値 {0}ms / P95 {1}ms" -f $median, $p95)
    Write-Host ("成功率: {0}/{1}" -f $successful.Count, $results.Count)

    @{
        serial = $Serial
        runs = $results.Count
        successCount = $successful.Count
        median = $median
        p95 = $p95
        details = $results
    } | ConvertTo-Json -Depth 4 | Out-File -FilePath "measure-switch-result.json" -Encoding utf8
    Write-Host "JSON: measure-switch-result.json"
} else {
    Write-Host "成功した測定がありません"
}
