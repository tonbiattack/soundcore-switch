# アプリアイコンを生成する。
#
# デザイン: 「2台の接続先の間でイヤホンが切り替わる」ことを表す。
# - 背景: アプリのテーマ色（深いティール）
# - 左右の丸: 接続先の端末
# - 中央: イヤホン（左右の丸より大きく、白）
# - 左右の丸と中央を結ぶ線: 接続
#
# 小さいサイズ（16px）でも潰れないよう、要素を絞り、形を太くしている。
param(
    [string]$OutputDir = "app/src/main/res/drawable-nodpi"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$size = 512
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

$bgColor = [System.Drawing.Color]::FromArgb(255, 47, 93, 98)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 247, 250, 250))
$accent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 182, 217, 220))

# 背景
$bg = New-Object System.Drawing.SolidBrush($bgColor)
$g.FillRectangle($bg, 0, 0, $size, $size)

# 中央を結ぶ線（左右の丸と中央をつなぐ）。丸の下に描く。
$linkPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 182, 217, 220), 26)
$linkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$linkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawLine($linkPen, 130, 256, 382, 256)

# 左右の接続先（明るいティールの円）
$g.FillEllipse($accent, 56, 196, 120, 120)
$g.FillEllipse($accent, 336, 196, 120, 120)

# 中央のイヤホン（白い円。左右より大きくして主役にする）
$g.FillEllipse($white, 186, 176, 140, 160)

# イヤホンのノズル（上に伸びる小さな突起）
$g.FillEllipse($white, 232, 148, 48, 56)

# 中央の円の内側に、切り替えを表す左右の矢じりを描く
$arrowPen = New-Object System.Drawing.Pen($bgColor, 18)
$arrowPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$arrowPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

# 左向き矢印
$g.DrawLine($arrowPen, 276, 232, 232, 232)
$g.FillPolygon((New-Object System.Drawing.SolidBrush($bgColor)), [System.Drawing.Point[]]@(
    (New-Object System.Drawing.Point(222, 232)),
    (New-Object System.Drawing.Point(244, 220)),
    (New-Object System.Drawing.Point(244, 244))
))

# 右向き矢印
$g.DrawLine($arrowPen, 236, 280, 280, 280)
$g.FillPolygon((New-Object System.Drawing.SolidBrush($bgColor)), [System.Drawing.Point[]]@(
    (New-Object System.Drawing.Point(290, 280)),
    (New-Object System.Drawing.Point(268, 268)),
    (New-Object System.Drawing.Point(268, 292))
))

$g.Dispose()

# 出力先を作る
$root = Split-Path -Parent $PSScriptRoot

# Android 用（各解像度）
#
# Android 8.0 以降はアダプティブアイコン（mipmap-anydpi-v26）が使われるため、
# こちらの PNG は 8.0 未満のフォールバックになる。
# 旧来のランチャーはアイコンをそのまま表示するので、角丸にして余白を残す。
$androidSizes = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}
foreach ($kv in $androidSizes.GetEnumerator()) {
    $dir = Join-Path $root "app/src/main/res/$($kv.Key)"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $full = $kv.Value
    $resized = New-Object System.Drawing.Bitmap($full, $full)
    $rg = [System.Drawing.Graphics]::FromImage($resized)
    $rg.Clear([System.Drawing.Color]::Transparent)
    $rg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    # 角丸の四角で切り抜く（余白を 1 割ほど残す）
    $margin = [int]($full * 0.08)
    $inner = $full - ($margin * 2)
    $radius = [int]($inner * 0.22)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc($margin, $margin, $d, $d, 180, 90)
    $path.AddArc($margin + $inner - $d, $margin, $d, $d, 270, 90)
    $path.AddArc($margin + $inner - $d, $margin + $inner - $d, $d, $d, 0, 90)
    $path.AddArc($margin, $margin + $inner - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $rg.SetClip($path)
    $rg.DrawImage($bmp, $margin, $margin, $inner, $inner)
    $rg.ResetClip()
    $rg.Dispose()
    $path.Dispose()

    $resized.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $resized.Dispose()
    Write-Host "  $($kv.Key)/ic_launcher.png ($($kv.Value)px)"
}

# アダプティブアイコンの前景（背景を透過させ、中央 66% に収める）。
# Android は前景を 108dp のうち中央 72dp に切り抜くため、余白を確保する。
foreach ($kv in $androidSizes.GetEnumerator()) {
    $dir = Join-Path $root "app/src/main/res/$($kv.Key)"
    $full = $kv.Value
    $fg = New-Object System.Drawing.Bitmap($full, $full)
    $rg = [System.Drawing.Graphics]::FromImage($fg)
    $rg.Clear([System.Drawing.Color]::Transparent)
    $rg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    # 中央 72/108 = 66.7% の領域に描く
    $inner = [int]($full * 0.667)
    $offset = [int](($full - $inner) / 2)
    $rg.DrawImage($bmp, $offset, $offset, $inner, $inner)
    $rg.Dispose()
    $fg.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()
}
Write-Host "  ic_launcher_foreground.png (各解像度)"

# Windows 用（複数サイズを含む ICO）
$desktopRes = Join-Path $root 'desktop/src/main/resources'
if (-not (Test-Path $desktopRes)) { New-Item -ItemType Directory -Path $desktopRes -Force | Out-Null }

$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @()
foreach ($s in $icoSizes) {
    $resized = New-Object System.Drawing.Bitmap($s, $s)
    $rg = [System.Drawing.Graphics]::FromImage($resized)
    $rg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $rg.DrawImage($bmp, 0, 0, $s, $s)
    $rg.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $resized.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , @{ Size = $s; Data = $ms.ToArray() }
    $ms.Dispose()
    $resized.Dispose()
}

# ICO コンテナを組み立てる
$icoPath = Join-Path $desktopRes 'icon.ico'
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([UInt16]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]$pngs.Count)

$offset = 6 + (16 * $pngs.Count)
foreach ($p in $pngs) {
    $s = $p.Size
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]$p.Data.Length)
    $bw.Write([UInt32]$offset)
    $offset += $p.Data.Length
}
foreach ($p in $pngs) { $bw.Write($p.Data) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()
Write-Host "  desktop/src/main/resources/icon.ico ($($icoSizes -join ', ')px)"

# プレビュー用の PNG も保存
$bmp.Save((Join-Path $root 'icon-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "  icon-preview.png (512px)"
Write-Host "完了"
