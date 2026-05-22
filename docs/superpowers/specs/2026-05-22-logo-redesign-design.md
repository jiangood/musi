# Literal Musi Logo Redesign

## Overview

Redesign the Literal Musi app icon/logo from the existing ring-shaped music note to a minimalist golden sine wave on a black background.

## Final Design

- **Background:** Black (#000000)
- **Foreground:** Gold (#FFCE00)
- **Element:** Three-layer sine wave (sinusoidal audio waveform)
  - Main wave: 6px stroke, full opacity
  - Secondary wave (below): 4px stroke, 50% opacity
  - Tertiary wave (above): 3px stroke, 30% opacity
- **Style:** Minimalist, waveform represents audio/music
- **Format:** Android adaptive icon (vector XML drawables)

## Files to Create/Modify

### 1. Vector Drawables

Replace `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` with the new sine wave design.

**ic_launcher_foreground.xml** — gold waveform on transparent background.
**ic_launcher_monochrome.xml** — same waveform in black for monochrome contexts.

Viewport: 220×220 (standard adaptive icon viewport)

Path data for the sine wave (converted to 220×220 viewport scale, centered vertically):

```
Main wave:   M22,110 Q55,28 88,110 T154,110 T198,110
Second wave: M22,130 Q55,172 88,130 T154,130 T198,130
Third wave:  M22,90  Q55,48  88,90  T154,90  T198,90
```

### 2. Background Color

`colors.xml` — keep `ic_launcher_background` as `#000000` (already set).

### 3. Play Store Icon

Update `ic_launcher-playstore.png` with the new design rendered at 512×512px.

## Implementation Notes

- Use `fillType="evenOdd"` for any overlapping wave paths to maintain clean rendering
- Use `android:fillColor="#00000000"` with `android:strokeColor` and `android:strokeWidth` for all paths (they are strokes, not fills)
- The monochrome variant uses `#000000` stroke color (for light backgrounds); the foreground variant uses `#FFCE00`
- All three waves share the same center point and phase alignment — they are parallel sinusoids with different amplitudes and opacities

## Files

| File | Purpose |
|------|---------|
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Gold foreground drawable |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Black monochrome drawable |
| `app/src/main/res/values/colors.xml` | Background color (keep #000000) |
| `app/src/main/ic_launcher-playstore.png` | Play Store listing icon |
