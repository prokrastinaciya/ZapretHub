# Generates ui\app.ico: a "no entry" sign whose own slash is snapped in half — a ban on bans.
param([string]$Out = (Join-Path $PSScriptRoot '..\ui\app.ico'), [string]$PngPreview = '')
Add-Type -AssemblyName System.Drawing

function P([double]$x, [double]$y) { New-Object System.Drawing.PointF ([single]$x), ([single]$y) }

function Draw-Icon([int]$s) {
    $b = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'

    # rounded glass tile
    $m = $s * 0.03; $w = $s - 2 * $m; $rad = $s * 0.27
    $tile = New-Object System.Drawing.Drawing2D.GraphicsPath
    $tile.AddArc($m, $m, $rad, $rad, 180, 90); $tile.AddArc($m + $w - $rad, $m, $rad, $rad, 270, 90)
    $tile.AddArc($m + $w - $rad, $m + $w - $rad, $rad, $rad, 0, 90); $tile.AddArc($m, $m + $w - $rad, $rad, $rad, 90, 90)
    $tile.CloseFigure()
    $rect = New-Object System.Drawing.RectangleF $m, $m, $w, $w
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(255, 132, 92, 255)), ([System.Drawing.Color]::FromArgb(255, 18, 196, 206)), 50
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend 3
    $blend.Colors = @([System.Drawing.Color]::FromArgb(255, 140, 90, 255), [System.Drawing.Color]::FromArgb(255, 92, 110, 255), [System.Drawing.Color]::FromArgb(255, 20, 200, 205))
    $blend.Positions = @([single]0, [single]0.5, [single]1)
    $bg.InterpolationColors = $blend
    $g.FillPath($bg, $tile)

    # glossy highlight on the upper half
    $g.SetClip($tile)
    $glossRect = New-Object System.Drawing.RectangleF ($s * -0.2), ($s * -0.55), ($s * 1.4), ($s * 1.05)
    $gloss = New-Object System.Drawing.Drawing2D.LinearGradientBrush $glossRect, ([System.Drawing.Color]::FromArgb(85, 255, 255, 255)), ([System.Drawing.Color]::FromArgb(0, 255, 255, 255)), 90
    $g.FillEllipse($gloss, $glossRect)
    $g.ResetClip()
    if ($s -ge 32) {
        $edge = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(70, 255, 255, 255)), ([single]([Math]::Max(1, $s / 128)))
        $g.DrawPath($edge, $tile)
    }

    $c = $s / 2; $r = $s * 0.285
    $stroke = $s * ($(if ($s -le 24) { 0.11 } else { 0.088 }))
    $k = 0.7071 * $r

    # the slash, snapped: two halves pushed apart across the break
    $gap = $s * 0.055; $shift = $s * ($(if ($s -le 24) { 0.03 } else { 0.042 }))
    $ux = 0.7071; $uy = 0.7071   # along the slash
    $px = -0.7071; $py = 0.7071  # perpendicular
    $a1 = P ($c - $k) ($c - $k); $a2 = P ($c - $ux * $gap + $px * $shift) ($c - $uy * $gap + $py * $shift)
    $b1 = P ($c + $ux * $gap - $px * $shift) ($c + $uy * $gap - $py * $shift); $b2 = P ($c + $k) ($c + $k)

    # soft shadow
    $sh = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(70, 20, 10, 60)), ([single]$stroke)
    $sh.StartCap = 'Round'; $sh.EndCap = 'Round'
    $off = $s * 0.022
    $g.TranslateTransform([single]0, [single]$off)
    $g.DrawEllipse($sh, [single]($c - $r), [single]($c - $r), [single](2 * $r), [single](2 * $r))
    $g.DrawLine($sh, $a1, $a2); $g.DrawLine($sh, $b1, $b2)
    $g.ResetTransform()

    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ([single]$stroke)
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawEllipse($pen, [single]($c - $r), [single]($c - $r), [single](2 * $r), [single](2 * $r))
    $g.DrawLine($pen, $a1, $a2); $g.DrawLine($pen, $b1, $b2)

    # spark at the break
    if ($s -ge 32) {
        $sp = $s * 0.085; $sx = $c + $s * 0.075; $sy = $c - $s * 0.075
        $star = New-Object System.Drawing.Drawing2D.GraphicsPath
        $pts = @()
        for ($i = 0; $i -lt 8; $i++) {
            $ang = [Math]::PI / 4 * $i - [Math]::PI / 2
            $len = $(if ($i % 2 -eq 0) { $sp } else { $sp * 0.28 })
            $pts += P ($sx + [Math]::Cos($ang) * $len) ($sy + [Math]::Sin($ang) * $len)
        }
        $star.AddPolygon($pts)
        $glowR = $sp * 1.5
        $glow = New-Object System.Drawing.Drawing2D.GraphicsPath
        $glow.AddEllipse([single]($sx - $glowR), [single]($sy - $glowR), [single](2 * $glowR), [single](2 * $glowR))
        $pgb = New-Object System.Drawing.Drawing2D.PathGradientBrush $glow
        $pgb.CenterColor = [System.Drawing.Color]::FromArgb(150, 255, 214, 102)
        $pgb.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 255, 214, 102))
        $g.FillPath($pgb, $glow)
        $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 222, 120))), $star)
    }
    $g.Dispose()
    return $b
}

$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$pngs = @()
foreach ($s in $sizes) {
    $bmp = Draw-Icon $s
    if ($PngPreview -and $s -eq 256) { $bmp.Save($PngPreview, [System.Drawing.Imaging.ImageFormat]::Png) }
    if ($s -ge 256) {
        $ms = [System.IO.MemoryStream]::new()
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
        $pngs += , $ms.ToArray()
    }
    else {
        # classic 32bpp DIB entry: System.Drawing.Icon (tray, window) can't decode small PNG entries
        $data = $bmp.LockBits((New-Object System.Drawing.Rectangle 0, 0, $s, $s), 'ReadOnly', 'Format32bppArgb')
        $raw = New-Object byte[] ($s * $s * 4)
        [Runtime.InteropServices.Marshal]::Copy($data.Scan0, $raw, 0, $raw.Length)
        $bmp.UnlockBits($data)
        $maskRow = [int]([Math]::Ceiling($s / 32.0)) * 4
        $ms = [System.IO.MemoryStream]::new()
        $bw = [System.IO.BinaryWriter]::new($ms)
        $bw.Write([UInt32]40); $bw.Write([Int32]$s); $bw.Write([Int32]($s * 2)); $bw.Write([UInt16]1); $bw.Write([UInt16]32)
        $bw.Write([UInt32]0); $bw.Write([UInt32]($raw.Length + $maskRow * $s)); $bw.Write([Int32]0); $bw.Write([Int32]0); $bw.Write([UInt32]0); $bw.Write([UInt32]0)
        for ($y = $s - 1; $y -ge 0; $y--) { $bw.Write($raw, $y * $s * 4, $s * 4) }   # bottom-up rows
        $bw.Write((New-Object byte[] ($maskRow * $s)))                               # AND mask (alpha is used instead)
        $bw.Flush()
        $pngs += , $ms.ToArray()
    }
    $bmp.Dispose()
}

$buf = [System.IO.MemoryStream]::new()
$w = [System.IO.BinaryWriter]::new($buf)
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $d = $pngs[$i]
    $w.Write([byte]($s % 256)); $w.Write([byte]($s % 256)); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([UInt16]1); $w.Write([UInt16]32); $w.Write([UInt32]$d.Length); $w.Write([UInt32]$offset)
    $offset += $d.Length
}
foreach ($d in $pngs) { $w.Write($d) }
[IO.File]::WriteAllBytes((Resolve-Path (Split-Path $Out)).Path + '\' + (Split-Path $Out -Leaf), $buf.ToArray())
"saved $Out"
