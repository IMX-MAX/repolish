# Repolish

Fabric client mod for Minecraft Java Edition **1.21.11**. Repolish records first-person gameplay takes (such as a TAS or speedrun) with zero capture lag, then recreates the footage inside a live **first-person Take Editor** so you can refine camera smoothness, FOV, HUD, and shaders on screen before exporting a clean video.

## How It Works

1. **Record a First-Person Take (`F8`)** — raw or polished:
   - While you play or run a TAS, Repolish records high-frequency first-person player state (position, eye height, raw yaw/pitch, walk bobbing, arm swings, sneaking/sprinting, hotbar slot, and markers) into a `.rtake` file alongside optional speaker audio (`.wav`).
   - Because no GPU framebuffer readback happens while you play, your game runs at full native FPS.
   - With **Record raw (no live polish)** enabled, gameplay stays completely untouched while recording: no live camera smoothing and no custom-FOV override, so even laggy, shaky, or shader-heavy sessions are captured exactly as played — the performance-friendly way to record. All the polish (smoothing, responsiveness, custom FOV, HUD, shader pack, resolution, FPS) is applied afterwards, when the take is recreated and exported.
2. **Recreate & Edit Live in the First-Person Take Editor**:
   - Stopping a recording immediately opens the take in the **Repolish Take Editor** (you can also re-open any saved `.rtake` later from **Recordings & Takes**).
   - Repolish reconstructs a continuous 240 Hz mouse trajectory across the whole take using a **velocity-adaptive zero-phase filter**:
     - 20 TPS staircase turns and micro-jitter are fused into smooth, natural OBS-style mouse arcs **centered on the exact tick of the turn** (never trailing behind the action like Minecraft's spectator camera).
     - Fast intentional flicks automatically tighten their filter window based on **Responsiveness**, landing crisply on target without floatiness.
   - The recreation is **world-accurate like Flashback**: the world's day time and game time are pinned to the take's playhead (reconstructed from the clock captured at record start), so sky, lighting, and time-of-day match the original footage exactly.
   - Everything in the editor updates **live on your screen**:
     - Scrub the timeline bar, play/pause (`Space`), step single frames (`<` / `>` or `Left` / `Right`), change playback speed (`0.25x` – `2.0x`), or set **In (`[`)** and **Out (`]`)** trim points.
     - Toggle **Look: Smooth / Raw TAS (`R`)** at any moment to compare the recreated mouse movement against the raw TAS angles live.
     - Drag **Smoothing**, **Responsiveness**, **Custom FOV**, toggle **HUD**, or switch **Iris Shader Packs** and watch the 3D first-person viewport update immediately.
3. **Deterministic Video Export**:
   - Click **Export video** in the editor to step through `[In .. Out]` deterministically frame-by-frame at `30`, `60`, or `120` fps.
   - Export settings are **frozen at the moment you click Export**: the video is rendered with the smoothing, responsiveness, FOV, HUD, audio, shader pack, resolution, and FPS you configured — not whatever the live preview happens to show later. The chosen shader pack is applied for the render and your previous pack is restored afterwards, and the exported look is always the smoothed one (never raw), so laggy/shaky raw footage comes out smooth.
   - Frames stream **directly into ffmpeg while rendering** (no giant intermediate file), so the encode finishes moments after the last frame. Output is `.mp4` (`libx264 -crf 14` with trimmed speaker audio), scaled with lanczos to your **exact chosen resolution** (even above window size), or high-quality `.avi` when ffmpeg is unavailable.
   - The recreation renders with the **FOV the take was recorded with**, and the replayed player is velocity-zeroed and server-synced every frame so the footage never rubber-bands or bounces.
   - Exported frames use **perfect deterministic timing** (Flashback-style): the client delta tracker is pinned to the exact `1 / fps` frame time, so even with heavy shaders enabled every frame is rendered at its exact timestamp with no wall-clock jitter.

## Controls

These are in Options → Controls → Repolish, and each one can be rebound.

| Default | Action |
| --- | --- |
| F6 | Toggle live camera polish preview during normal play |
| F7 | Open Repolish settings |
| F8 | Start or stop recording a take |
| F9 | Drop a timestamp marker |
| F10 | Toggle HUD in the recreated footage |

### Editor Shortcuts

| Key | Action |
| --- | --- |
| `Space` | Play / Pause take |
| `Left` / `Right` | Step backward / forward 1 frame |
| `[` / `]` | Set In / Out trim points at playhead |
| `R` | Toggle Smooth vs Raw TAS camera comparison |
| `M` | Place a marker at the playhead |
| `H` | Show / hide the right-side settings panel |

## Toolchain

| Component | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| Java | 25 to run Gradle, bytecode target 21 |
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.18-SNAPSHOT (`net.fabricmc.fabric-loom-remap`) |
| Fabric API | 0.141.6+1.21.11 |
| Mappings | Official Mojang mappings |
| Gradle | 9.7.1 (wrapper) |

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
./gradlew test build
./gradlew runClient
```
