# Generates the Android TV launcher banner (320x180 PNG) for Epigeia TV.
# ASCII-only on purpose: Greek text is built from char codes (PS 5.1 parser safety).
Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap(320,180)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.TextRenderingHint = 'AntiAlias'
$g.Clear([System.Drawing.Color]::FromArgb(255,13,27,42))

# antenna
$gray = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,176,190,197)), 4
$g.DrawLine($gray, 60, 58, 40, 28)
$g.DrawLine($gray, 60, 58, 80, 28)
$dot = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,176,190,197))
$g.FillEllipse($dot, 34, 22, 12, 12)
$g.FillEllipse($dot, 74, 22, 12, 12)

# TV body + green screen
$body = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,55,71,79))
$g.FillRectangle($body, 20, 58, 80, 66)
$green = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,0,200,83))
$g.FillRectangle($green, 26, 64, 68, 54)
$shine = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(50,255,255,255))
$pts = @(
    (New-Object System.Drawing.Point(26,64)),
    (New-Object System.Drawing.Point(52,64)),
    (New-Object System.Drawing.Point(34,118)),
    (New-Object System.Drawing.Point(26,118))
)
$g.FillPolygon($shine, $pts)

# "Epigeia TV" in Greek, from char codes
$txt = -join ([char]0x0395,[char]0x03C0,[char]0x03AF,[char]0x03B3,[char]0x03B5,[char]0x03B9,[char]0x03B1)
$font1 = New-Object System.Drawing.Font('Segoe UI', 30, [System.Drawing.FontStyle]::Bold)
$font2 = New-Object System.Drawing.Font('Segoe UI', 22, [System.Drawing.FontStyle]::Regular)
$g.DrawString($txt, $font1, [System.Drawing.Brushes]::White, 112, 52)
$g.DrawString('TV', $font2, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,0,200,83))), 116, 102)

$g.Dispose()
$out = 'C:\Claude\dvb_android\app\src\main\res\drawable\banner.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "banner saved: $out"
