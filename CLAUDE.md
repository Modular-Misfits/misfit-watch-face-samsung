# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy

This is an Android/Wear OS project built with Gradle. There is no CLI test suite — all validation requires deploying to a physical Samsung Watch6 or emulator.

```bash
# Gradle build (from repo root)
./gradlew assembleDebug

# Install to connected watch (wireless debugging must be active)
./gradlew installDebug
```

Open in **Android Studio** for the full workflow: Gradle sync → select `app` run config → connect Watch6 via wireless debugging → Run.

## Architecture

The entire watch face lives in one file:

**`app/src/main/java/com/modularmisfits/watchface/MisfitWatchFaceService.kt`**

- `MisfitWatchFaceService` — thin `WatchFaceService` subclass; returns an empty style schema and no complication slots, then wires up `MisfitRenderer`.
- `MisfitRenderer` — extends `CanvasRenderer` and implements `WatchFace.TapListener`. Owns all drawing logic.

### Render flow

`render()` dispatches to one of two views based on tap state and ambient mode:
- **Clock view** (`drawClockView`) — radial gradient background, scaled logo bitmap, tick marks, date bubble at 3 o'clock position, analog hands.
- **Details view** (`drawDetailsView`) — shown for 8 seconds after a tap (`detailsUntilMillis`); displays placeholder weather and Samsung Health stats over the logo.

Ambient mode suppresses the seconds hand, dims the logo (`ambientLogo`), uses a minimal ring background instead of the radial gradient, and mutes all colors to grays.

### Assets

- `misfit_logo_clean.png` — active-mode full-color logo (text cropped off bottom)
- `misfit_logo_ambient.png` — dimmed/grayscale version for ambient mode

Both are in `app/src/main/res/drawable-nodpi/`.

## Key constraints

- `compileSdk 35`, `minSdk 30` (Wear OS 3+), targets Samsung Watch6.
- Uses `androidx.wear.watchface:watchface:1.2.1` — the Jetpack WatchFace API, **not** the older `WatchFaceService` from `android.support.wearable`.
- Complication slots are intentionally empty (`emptyList()`). Weather and health values in the detail view are hardcoded placeholders — live data requires adding complication slots backed by a Wear OS data provider.
- The `CanvasRenderer` runs at 16 ms frame interval (≈60 fps) in interactive mode.
- `paint` is a shared mutable `Paint` object reused across all draw calls — set all style properties before each use; don't assume state carries over.
