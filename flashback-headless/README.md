# Flashback Headless

A tiny Fabric addon for [Flashback](https://modrinth.com/mod/flashback) (Minecraft Java 1.21.11, Flashback 0.39.x) that disables Flashback's ImGui overlay entirely — initialization, GLFW/GL3 backend setup, and per-frame rendering — while leaving replay **recording** fully functional.

Use case: on arm64 (Apple Silicon) Macs, Flashback's ImGui native libraries run poorly under Rosetta/x86 translation or arm64 JVMs and cause heavy lag. Install this mod in your arm64 instance to record flashbacks without the ImGui overhead, then switch the instance to an x86_64 JVM, remove this mod, and open the replay in Flashback's normal editor to edit/export.

## How it works

A single client mixin into `com.moulberry.flashback.editor.ui.ReplayUI` cancels two methods at `HEAD`:

- `ReplayUI.init()` — skips `ImGui.createContext()`, GLFW callback installation, GL3 backend init, and font atlas building. Because the ImGui context is never created, no ImGui native code is ever exercised.
- `ReplayUI.drawOverlay()` — the per-frame entry point invoked from `MixinMinecraft` after the main render target blit. Cancelling it skips the whole frame: `newFrame()`, window rendering, `ImGui.render()`, and `renderDrawData`.

Everything else in Flashback is untouched. In particular:

- `ReplayUI.isActive()` keeps returning `false` (its backing field is never set, since `drawOverlayInternal` never runs), so the mouse-grab, viewport, HUD-hiding, and debug-renderer hooks that check `isActive()` all behave exactly as in normal gameplay.
- Recording via `/flashback start`, `/flashback finish`, `/flashback pause|unpause|mark`, and the "automatically start recording" config option does not depend on ImGui and works unchanged.
- Opening a replay will not bring up the editor UI (that requires ImGui); use the x86 instance without this mod for editing.

## Build

Compile `src/main/java` against the Flashback 0.39.10 jar (MC 1.21.11), fabric-loader, and sponge-mixin on the classpath, e.g.:

```
javac -proc:none -source 21 -target 21 \
  -cp flashback.jar:fabric-loader.jar:sponge-mixin.jar \
  -d build/classes src/main/java/com/vibe/flashbackheadless/mixin/MixinReplayUI.java
```

Then package `build/classes` together with `src/main/resources/fabric.mod.json` and `src/main/resources/flashback-headless.mixins.json` into a jar.

## Install

Drop the jar into the `mods` folder of a Fabric 1.21.11 instance that also has Flashback 0.39.x and Fabric API. Record with `/flashback start` … `/flashback finish`. To edit the replay, remove this mod and launch the instance with an x86_64 Java runtime.
