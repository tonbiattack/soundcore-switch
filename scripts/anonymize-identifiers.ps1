# 実機で観測した識別子を、公開してよい架空の値へ置き換える。
#
# 対象は git の追跡ファイルのみ。ビルド成果物や作業用 worktree は触らない。
# 置換は「同じ実値 → 同じ架空値」の対応を保つ。対応が崩れるとテストの
# 意味（同じ端末か別端末か）が変わってしまう。
#
# 使い方:
#   .\scripts\anonymize-identifiers.ps1 -WhatIf   # 変更内容の確認
#   .\scripts\anonymize-identifiers.ps1           # 実行
param(
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

# 実値 → 架空値。MAC はローカル管理ビットを立てた文書用アドレスに寄せる。
$replacements = [ordered]@{
    # イヤホン本体（Liberty 4 NC）
    'F0:00:00:00:00:01' = 'F0:00:00:00:00:01'
    # イヤホン本体（Space A40）
    'F0:00:00:00:00:02' = 'F0:00:00:00:00:02'
    # イヤホン本体（Liberty 4 NC 2台目）
    'F0:00:00:00:00:03' = 'F0:00:00:00:00:03'
    # 操作元のスマートフォン
    'F0:00:00:00:00:10' = 'F0:00:00:00:00:10'
    # デスクトップ PC
    'F0:00:00:00:00:20' = 'F0:00:00:00:00:20'
    # ノート PC
    'F0:00:00:00:00:21' = 'F0:00:00:00:00:21'
    # タブレット
    'F0:00:00:00:00:30' = 'F0:00:00:00:00:30'
    # ゲーム機
    'F0:00:00:00:00:40' = 'F0:00:00:00:00:40'
    # 開発ボード
    'F0:00:00:00:00:50' = 'F0:00:00:00:00:50'
    # タブレット（別型番）
    'F0:00:00:00:00:31' = 'F0:00:00:00:00:31'
    # ノート PC（別個体）
    'F0:00:00:00:00:22' = 'F0:00:00:00:00:22'
    # 携帯ゲーム機
    'F0:00:00:00:00:41' = 'F0:00:00:00:00:41'
    # イヤホン（V20i）
    'F0:00:00:00:00:04' = 'F0:00:00:00:00:04'
    # イヤホン（Liberty 4 NC 3台目）
    'F0:00:00:00:00:05' = 'F0:00:00:00:00:05'

    # 端末名。実在の個体を特定できる名前を避ける。
    'Pixel 9' = 'Pixel 9'
    'DESKTOP-A' = 'DESKTOP-A'
    'DESKTOP-B' = 'DESKTOP-B'
    'LAPTOP-A' = 'LAPTOP-A'
    'LAPTOP-B' = 'LAPTOP-B'
    'DEVBOARD-A' = 'DEVBOARD-A'
    'HANDHELD-A' = 'HANDHELD-A'
    'Tablet A' = 'Tablet A'
    'Tablet B' = 'Tablet B'
    'Tablet C' = 'Tablet C'
    'Mouse A' = 'Mouse A'
    'Keyboard A' = 'Keyboard A'
}

# 追跡ファイルだけを対象にする。作業用 worktree やビルド成果物は含めない。
$files = git ls-files | Where-Object {
    $_ -match '\.(md|kt|kts|ps1|yml|yaml|json|properties|xml|toml|txt)$'
}

$changed = @()
foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $original = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $file))
    $updated = $original
    foreach ($pair in $replacements.GetEnumerator()) {
        $updated = $updated.Replace($pair.Key, $pair.Value)
    }
    if ($updated -ne $original) {
        $changed += $file
        if (-not $WhatIf) {
            # 改行コードを保つため、テキストをそのまま書き戻す。
            [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $file), $updated)
        }
    }
}

Write-Output "置換対象: $($changed.Count) ファイル"
$changed | ForEach-Object { "  $_" }
if ($WhatIf) { Write-Output "(WhatIf のため書き込みなし)" }
