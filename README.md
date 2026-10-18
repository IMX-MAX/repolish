# Repolish

Fabric client mod for Minecraft Java Edition **1.21.11**. Repolish records a TAS or a normal session from inside the game, instead of through OBS, and steadies the camera while it does it.

The recording is the game's own image. Open inventories, mod screens, the HUD, and the shader pack that is currently enabled are in the footage. Game audio is taken from the speakers (the PulseAudio or PipeWire monitor), not from a microphone. Set the export options, start the recording, play the TAS, then stop.

Smoothing is aimed at TAS look jitter. A large flick still lands on the same tick instead of trailing the way Minecraft's spectator camera does. Turn responsiveness down only if you want a heavier camera.

## Controls

These are in Options → Controls → Repolish, and each one can be rebound.

| Default | Action |
| --- | --- |
| F6 | Toggle the camera polish preview |
| F7 | Open export settings |
| F8 | Start or stop recording |
| F9 | Drop a timestamp marker |
| F10 | Include or hide the HUD for the recording |

The pause screen also has a Repolish button.

## Export

The export screen sets:

- clip name, resolution, and framerate
- game FOV or a custom FOV
- smoothing and responsiveness
- HUD, game audio, and a shader pack when Iris is installed

Videos are written to `repolish/` in the game directory. ffmpeg produces an mp4. Without ffmpeg, Repolish writes a PNG sequence instead. Markers are saved next to the file as `.markers.txt`.

Changing FOV or shaders applies to the next recording. Run the TAS again to export another take.

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
