# Gradle を「括弧や引用符を含まない PATH」で実行する。
#
# 背景: この端末の PATH には `C:\Program Files (x86)\PowerShell\7` のような
# 括弧を含む要素と、`C:\Program Files\PowerShell\7";` のような二重引用符が
# 混入している。Gradle はテスト実行時に PATH から -Djava.library.path を
# 組み立てるため、これらが混ざるとテストワーカーが
#   ClassNotFoundException: Files\PowerShell\7;C:\windows\system32;...
# で落ちる。プロジェクトのコードとは無関係な環境要因である。
#
# 使い方:
#   .\scripts\gradle.ps1 :app:testDebugUnitTest
#   .\scripts\gradle.ps1 :app:assembleDebug --console=plain
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'

# テストワーカーに必要な最小限の PATH だけを通す。
$env:PATH = @(
    'C:\windows\system32'
    'C:\windows'
    'C:\windows\System32\Wbem'
    'C:\windows\System32\WindowsPowerShell\v1.0\'
    'C:\Program Files\Git\cmd'
    'C:\Program Files\jdk-21\bin'
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools"
) -join ';'

$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $root 'gradlew.bat') @GradleArgs
exit $LASTEXITCODE
