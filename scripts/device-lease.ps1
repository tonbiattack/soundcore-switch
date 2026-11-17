# Device Warden のリースを取得し、heartbeat を送りながら作業するためのヘルパー。
#
# 背景: リースは 120 秒で失効する。claim したまま heartbeat を送らないと
# 「要確認」に移行し、他のエージェントがその端末を使えなくなる。
# このスクリプトは heartbeat を裏で送り続けることで、それを防ぐ。
#
# 使い方:
#   # 1. リースを取得して heartbeat を開始する
#   $lease = .\scripts\device-lease.ps1 -Action claim -Title "APK 導入"
#
#   # 2. 作業する（$lease を使って adb を実行）
#   python -m device_warden.cli adb $lease -- install -r app-debug.apk
#
#   # 3. 解放する（heartbeat も止まる）
#   .\scripts\device-lease.ps1 -Action release -LeaseId $lease
#
#   # 状態確認
#   .\scripts\device-lease.ps1 -Action status
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claim', 'release', 'status', 'heartbeat')]
    [string]$Action,

    [string]$LeaseId,
    [string]$Title = "作業",
    [string]$Project = "soundcore-switch",
    [string]$SessionId = "dsh-d287f6b0-166d",
    [string]$Tool = "dsh",
    [int]$HeartbeatSeconds = 30
)

$ErrorActionPreference = 'Stop'

# heartbeat のバックグラウンドジョブを保持するファイル。
# プロセスをまたいで止められるようにするため、PID を残す。
$stateDir = Join-Path $env:TEMP 'device-warden-lease'
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Path $stateDir -Force | Out-Null }

function Get-HeartbeatPidFile([string]$id) {
    Join-Path $stateDir "heartbeat-$id.pid"
}

function Start-Heartbeat([string]$id) {
    $pidFile = Get-HeartbeatPidFile $id
    if (Test-Path $pidFile) {
        $existing = Get-Content $pidFile -ErrorAction SilentlyContinue
        if ($existing -and (Get-Process -Id $existing -ErrorAction SilentlyContinue)) {
            Write-Host "heartbeat は既に動作中 (PID $existing)"
            return
        }
    }

    # heartbeat を送り続ける子プロセスを起動する。
    # リースが解放されたら自然に終了するよう、失敗が続いたら止める。
    $script = @"
`$failures = 0
while (`$true) {
    Start-Sleep -Seconds $HeartbeatSeconds
    `$out = python -m device_warden.cli heartbeat $id 2>&1
    if (`$LASTEXITCODE -ne 0) {
        `$failures++
        if (`$failures -ge 3) { break }
    } else {
        `$failures = 0
    }
}
"@
    $scriptPath = Join-Path $stateDir "heartbeat-$id.ps1"
    Set-Content -Path $scriptPath -Value $script -Encoding UTF8

    $proc = Start-Process -FilePath 'pwsh' `
        -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-File', $scriptPath `
        -PassThru -WindowStyle Hidden
    Set-Content -Path $pidFile -Value $proc.Id
    Write-Host "heartbeat を開始 (PID $($proc.Id), ${HeartbeatSeconds}秒間隔)"
}

function Stop-Heartbeat([string]$id) {
    $pidFile = Get-HeartbeatPidFile $id
    if (-not (Test-Path $pidFile)) { return }
    $heartbeatPid = Get-Content $pidFile -ErrorAction SilentlyContinue
    if ($heartbeatPid) {
        Stop-Process -Id $heartbeatPid -Force -ErrorAction SilentlyContinue
        Write-Host "heartbeat を停止 (PID $heartbeatPid)"
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $stateDir "heartbeat-$id.ps1") -Force -ErrorAction SilentlyContinue
}

switch ($Action) {
    'claim' {
        $workdir = (Get-Location).Path
        $json = python -m device_warden.cli claim `
            --tool $Tool --session-id $SessionId `
            --title $Title --project $Project --workdir $workdir 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Error "リースを取得できませんでした: $json"
            exit 1
        }
        $claim = $json | ConvertFrom-Json
        $id = $claim.lease_id
        Write-Host "リースを取得: $id"
        Start-Heartbeat $id
        # 呼び出し側が使えるよう、lease_id だけを出力する
        Write-Output $id
    }

    'release' {
        if (-not $LeaseId) { Write-Error "-LeaseId が必要です"; exit 1 }
        Stop-Heartbeat $LeaseId
        python -m device_warden.cli release $LeaseId 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "リースを解放: $LeaseId"
        } else {
            Write-Error "解放に失敗しました"
            exit 1
        }
    }

    'heartbeat' {
        if (-not $LeaseId) { Write-Error "-LeaseId が必要です"; exit 1 }
        python -m device_warden.cli heartbeat $LeaseId 2>&1 | Out-Null
        Write-Host "heartbeat を送信"
    }

    'status' {
        $list = python -m device_warden.cli list 2>&1 | ConvertFrom-Json
        $list | Select-Object display_name, connection, lease_state, recovery_reason |
            Format-Table -AutoSize

        $recovery = $list | Where-Object { $_.lease_state -eq 'recovery_required' }
        if ($recovery) {
            Write-Host ""
            Write-Host "要確認のリースがあります:" -ForegroundColor Yellow
            $recovery | ForEach-Object {
                Write-Host "  $($_.display_name): $($_.recovery_reason)"
            }
            Write-Host "自分が取得したものなら --resume で復旧できます。"
        }
    }
}
