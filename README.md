# Repolish

Fabric mod for Minecraft Java Edition **1.21.11**. Repolish is for TAS creators who want to record a run inside the game instead of with OBS, then export footage that still looks like a normal capture.

The export UI is meant to follow the same kind of controls as Flashback: field of view, shaders, and the other render settings you would pick before encoding. Captured output should include game audio (not the microphone), the GUI, and screens that mods open. Camera cleanup should take the shake out of a TAS replay without turning it into Minecraft's spectator camera, which eases too hard and lags behind the inputs.

## Toolchain

Versions match the [Fabric example mod](https://github.com/FabricMC/fabric-example-mod/tree/1.21.11) for 1.21.11:

| Component | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| Java | 25 to run Gradle, bytecode target 21 |
| Fabric Loader | 0.19.5 |
| Fabric Loom | 1.18-SNAPSHOT (`net.fabricmc.fabric-loom-remap`) |
| Fabric API | 0.141.6+1.21.11 |
| Mappings | Official Mojang mappings |
| Gradle | 9.7.1 (wrapper) |

## Develop

Gradle must run on JDK 25. The mod still targets Java 21.

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
./gradlew build
./gradlew runClient
```

`build` compiles the client mod and writes `build/libs/repolish-1.0.0.jar`. `runClient` launches a development client with the mod loaded.
