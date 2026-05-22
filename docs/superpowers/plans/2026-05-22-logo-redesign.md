# Logo Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Literal Musi app icon with gold sine wave on black background

**Architecture:** Update three files — two vector XML drawables (foreground + monochrome) and the Play Store PNG. The adaptive icon XML (`mipmap-anydpi/ic_launcher.xml`) and background color (`colors.xml`) remain unchanged.

**Tech Stack:** Android Vector Drawable (SVG-compatible), XML, PNG

---

### Task 1: Update foreground drawable

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`

- [ ] **Replace foreground with gold sine wave**

Replace entire file content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="220"
    android:viewportHeight="220">

    <path
        android:pathData="M22,110 Q55,28 88,110 T154,110 T198,110"
        android:strokeColor="#FFCE00"
        android:strokeWidth="6"
        android:fillColor="#00000000"
        android:strokeLineCap="round"/>

    <path
        android:pathData="M22,131 Q55,172 88,131 T154,131 T198,131"
        android:strokeColor="#FFCE00"
        android:strokeWidth="4"
        android:fillColor="#00000000"
        android:strokeLineCap="round"
        android:alpha="0.5"/>

    <path
        android:pathData="M22,89 Q55,48 88,89 T154,89 T198,89"
        android:strokeColor="#FFCE00"
        android:strokeWidth="3"
        android:fillColor="#00000000"
        android:strokeLineCap="round"
        android:alpha="0.3"/>

</vector>
```

- [ ] **Verify the file**

Run: `ls -la app/src/main/res/drawable/ic_launcher_foreground.xml`
Expected: file exists, valid XML

---

### Task 2: Update monochrome drawable

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_monochrome.xml`

- [ ] **Replace monochrome with black sine wave**

Replace entire file with same paths but `#000000` stroke color:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="220"
    android:viewportHeight="220">

    <path
        android:pathData="M22,110 Q55,28 88,110 T154,110 T198,110"
        android:strokeColor="#000000"
        android:strokeWidth="6"
        android:fillColor="#00000000"
        android:strokeLineCap="round"/>

    <path
        android:pathData="M22,131 Q55,172 88,131 T154,131 T198,131"
        android:strokeColor="#000000"
        android:strokeWidth="4"
        android:fillColor="#00000000"
        android:strokeLineCap="round"
        android:alpha="0.5"/>

    <path
        android:pathData="M22,89 Q55,48 88,89 T154,89 T198,89"
        android:strokeColor="#000000"
        android:strokeWidth="3"
        android:fillColor="#00000000"
        android:strokeLineCap="round"
        android:alpha="0.3"/>

</vector>
```

- [ ] **Verify the file**

Run: `ls -la app/src/main/res/drawable/ic_launcher_monochrome.xml`
Expected: file exists, valid XML

---

### Task 3: Update Play Store icon

**Files:**
- Modify: `app/src/main/ic_launcher-playstore.png`

- [ ] **Generate 512×512 PNG of the sine wave design**

Use Python with Pillow to render the gold sine wave on black and save as PNG:

```bash
python3 -c "
from PIL import Image, ImageDraw
img = Image.new('RGB', (512, 512), (0, 0, 0))
draw = ImageDraw.Draw(img)
# Coordinates scaled from 220×220 viewport to 512×512
# Scale: 512/220 ≈ 2.327
s = 512/220
def draw_wave(points, width, color, alpha):
    c = tuple(int(color[i:i+2], 16) for i in (1,3,5))
    for i in range(len(points)-1):
        x1,y1,x2,y2 = *points[i], *points[i+1]
        draw.line([x1*s, y1*s, x2*s, y2*s], fill=c+(int(alpha*255),), width=max(1,int(width*s)))
draw_wave([(22,110),(55,28),(88,110),(110,110),(132,110),(154,110),(176,110),(198,110)], 6, '#FFCE00', 1.0)
img.save('app/src/main/ic_launcher-playstore.png')
"
```

Actually — the above is an approximation. The sine wave uses quadratic bezier curves (`Q` and `T` commands). A proper rendering needs to convert SVG path data. The quickest approach is to render the SVG via an external tool. Let me use a different approach:

- [ ] **Render SVG to PNG using cairosvg or rsvg-convert**

```bash
# Check if rsvg-convert is available
which rsvg-convert 2>/dev/null && echo "available" || echo "not available"
# If not available, try: apt-get install -y librsvg2-bin
```

If rsvg-convert is available:

```bash
# Create temporary SVG
cat > /tmp/sine_wave.svg << 'EOF'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 220 220" width="512" height="512">
  <rect width="220" height="220" fill="#000000"/>
  <path d="M22,110 Q55,28 88,110 T154,110 T198,110" stroke="#FFCE00" stroke-width="6" fill="none" stroke-linecap="round"/>
  <path d="M22,131 Q55,172 88,131 T154,131 T198,131" stroke="#FFCE00" stroke-width="4" fill="none" stroke-linecap="round" opacity="0.5"/>
  <path d="M22,89 Q55,48 88,89 T154,89 T198,89" stroke="#FFCE00" stroke-width="3" fill="none" stroke-linecap="round" opacity="0.3"/>
</svg>
EOF

rsvg-convert -w 512 -h 512 /tmp/sine_wave.svg -o app/src/main/ic_launcher-playstore.png
```

- [ ] **Verify the PNG**

Run: `file app/src/main/ic_launcher-playstore.png`
Expected: `PNG image data, 512 x 512`

---

### Task 4: Commit

- [ ] **Commit all changes**

```bash
git add app/src/main/res/drawable/ic_launcher_foreground.xml \
       app/src/main/res/drawable/ic_launcher_monochrome.xml \
       app/src/main/ic_launcher-playstore.png
git commit -m "feat: redesign app icon with gold sine wave logo"
```
