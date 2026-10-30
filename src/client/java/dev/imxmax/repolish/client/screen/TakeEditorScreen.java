package dev.imxmax.repolish.client.screen;

import dev.imxmax.repolish.client.ExportSettings;
import dev.imxmax.repolish.client.ReplayController;
import dev.imxmax.repolish.client.ShaderPacks;
import dev.imxmax.repolish.take.TakeData;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * First-person live editor for a recorded take.
 *
 * Recreates the take live in the background viewport while allowing the user to
 * scrub the timeline, trim In/Out points, compare raw vs smoothed camera motion,
 * tweak smoothing/responsiveness/FOV/shaders live, and export deterministic
 * high-framerate video.
 */
public final class TakeEditorScreen extends Screen {
	private static final List<Float> SPEEDS = List.of(0.25F, 0.5F, 1.0F, 2.0F);

	private final Screen parent;
	private boolean hideInspector = false;
	private boolean scrubbingTimeline = false;

	private Button playPauseButton;
	private Button compareButton;
	private Button exportButton;
	private LabeledSlider fovSlider;

	public TakeEditorScreen(Screen parent) {
		super(Component.translatable("repolish.screen.editor"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ExportSettings settings = ExportSettings.get();
		ReplayController replay = ReplayController.get();

		// Top-left bar: Recordings & Hide UI toggle
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.recordings"), button -> {
			settings.save();
			if (this.minecraft != null) {
				this.minecraft.setScreen(new RecordingsScreen(this));
			}
		}).bounds(8, 8, 76, 18).build());

		this.addRenderableWidget(Button.builder(
			Component.literal(this.hideInspector ? "Show Panel (H)" : "Hide Panel (H)"),
			button -> {
				this.hideInspector = !this.hideInspector;
				this.clearWidgets();
				this.init();
			}
		).bounds(88, 8, 92, 18).build());

		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.done"), button -> this.onClose())
			.bounds(184, 8, 56, 18)
			.build());

		// Bottom timeline transport controls
		int bottomY = this.height - 24;
		int bx = 8;

		this.playPauseButton = this.addRenderableWidget(Button.builder(this.playLabel(), button -> {
			replay.togglePlaying();
			button.setMessage(this.playLabel());
		}).bounds(bx, bottomY, 54, 18).build());
		bx += 56;

		this.addRenderableWidget(Button.builder(Component.literal("< 1f"), button -> {
			replay.stepFrames(-1);
			this.refreshTransportLabels();
		}).bounds(bx, bottomY, 32, 18).build());
		bx += 34;

		this.addRenderableWidget(Button.builder(Component.literal("1f >"), button -> {
			replay.stepFrames(1);
			this.refreshTransportLabels();
		}).bounds(bx, bottomY, 32, 18).build());
		bx += 36;

		this.addRenderableWidget(Button.builder(Component.literal("Set In ["), button -> replay.setInTime(replay.playhead()))
			.bounds(bx, bottomY, 48, 18)
			.build());
		bx += 50;

		this.addRenderableWidget(Button.builder(Component.literal("Set Out ]"), button -> replay.setOutTime(replay.playhead()))
			.bounds(bx, bottomY, 54, 18)
			.build());
		bx += 56;

		this.addRenderableWidget(this.cycle(bx, bottomY, 72, 18, Component.literal("Speed"),
			(Float speed) -> Component.literal(String.format(Locale.ROOT, "%.2fx", speed).replace("0x", "x")),
			replay.playbackSpeed(), SPEEDS, replay::setPlaybackSpeed));
		bx += 74;

		this.compareButton = this.addRenderableWidget(Button.builder(this.compareLabel(), button -> {
			replay.setCompareRaw(!replay.isCompareRaw());
			button.setMessage(this.compareLabel());
		}).bounds(bx, bottomY, 88, 18).build());
		bx += 90;

		if (bx + 52 <= this.width - 8) {
			this.addRenderableWidget(Button.builder(Component.literal("+Mark"), button -> replay.addMarkerAtPlayhead())
				.bounds(bx, bottomY, 44, 18)
				.build());
			bx += 46;
		}
		if (bx + 48 <= this.width - 8) {
			this.addRenderableWidget(Button.builder(Component.literal("Next>"), button -> replay.jumpToMarker(true))
				.bounds(bx, bottomY, 44, 18)
				.build());
		}

		if (this.hideInspector) {
			return;
		}

		// Right-side compact inspector panel for live camera & export settings
		int panelW = 176;
		int panelX = this.width - panelW - 8;
		int py = 8;

		EditBox nameBox = new EditBox(this.font, panelX, py, panelW, 18, Component.translatable("repolish.setting.name"));
		nameBox.setMaxLength(64);
		nameBox.setValue(settings.clipName);
		nameBox.setResponder(value -> {
			settings.clipName = value;
			if (replay.take() != null) {
				replay.take().setName(value);
			}
		});
		this.addRenderableWidget(nameBox);
		py += 20;

		LabeledSlider smoothing = new LabeledSlider(panelX, py, panelW, 18, "Smoothing", 0, 100, settings.smoothing, value -> {
			settings.smoothing = value;
			replay.evaluateSample();
		});
		smoothing.setTooltip(Tooltip.create(Component.translatable("repolish.setting.smoothing.tooltip")));
		this.addRenderableWidget(smoothing);
		py += 20;

		LabeledSlider responsiveness = new LabeledSlider(panelX, py, panelW, 18, "Responsiveness", 0, 100, settings.responsiveness, value -> {
			settings.responsiveness = value;
			replay.evaluateSample();
		});
		responsiveness.setTooltip(Tooltip.create(Component.translatable("repolish.setting.responsiveness.tooltip")));
		this.addRenderableWidget(responsiveness);
		py += 20;

		this.addRenderableWidget(CycleButton.onOffBuilder(!settings.matchFov)
			.create(panelX, py, panelW, 18, Component.translatable("repolish.setting.custom_fov"), (button, value) -> {
				settings.matchFov = !value;
				if (this.fovSlider != null) {
					this.fovSlider.active = value;
				}
			}));
		py += 20;

		this.fovSlider = new LabeledSlider(panelX, py, panelW, 18, "FOV", 30, 140, settings.fov, value -> settings.fov = value);
		this.fovSlider.active = !settings.matchFov;
		this.addRenderableWidget(this.fovSlider);
		py += 20;

		this.addRenderableWidget(CycleButton.onOffBuilder(settings.includeHud)
			.create(panelX, py, panelW / 2 - 1, 18, Component.literal("HUD"), (button, value) -> {
				settings.includeHud = value;
				if (this.minecraft != null) {
					this.minecraft.options.hideGui = !value;
				}
			}));

		this.addRenderableWidget(CycleButton.onOffBuilder(settings.includeAudio)
			.create(panelX + panelW / 2 + 1, py, panelW / 2 - 1, 18, Component.literal("Audio"), (button, value) -> settings.includeAudio = value));
		py += 20;

		CycleButton<Boolean> recordRaw = this.addRenderableWidget(CycleButton.onOffBuilder(settings.recordRaw)
			.create(panelX, py, panelW, 18, Component.translatable("repolish.setting.record_raw"), (button, value) -> settings.recordRaw = value));
		recordRaw.setTooltip(Tooltip.create(Component.translatable("repolish.setting.record_raw.tooltip")));
		py += 20;

		this.addRenderableWidget(this.cycle(panelX, py, panelW / 2 - 1, 18, Component.literal("Res"),
			(ExportSettings.Resolution value) -> Component.literal(value.label()), settings.resolution,
			Arrays.asList(ExportSettings.Resolution.values()), value -> settings.resolution = value));

		this.addRenderableWidget(this.cycle(panelX + panelW / 2 + 1, py, panelW / 2 - 1, 18, Component.literal("FPS"),
			(Integer fps) -> Component.literal(fps + "fps"), settings.fps,
			List.of(30, 60, 120), value -> settings.fps = value));
		py += 20;

		List<String> shaders = ShaderPacks.choices(this.minecraft);
		if (!shaders.contains(settings.shader)) {
			settings.shader = ShaderPacks.CURRENT;
		}
		this.addRenderableWidget(this.cycle(panelX, py, panelW, 18, Component.translatable("repolish.setting.shader"),
			(String choice) -> Component.literal(ShaderPacks.label(choice)), settings.shader, shaders,
			value -> {
				settings.shader = value;
				ShaderPacks.apply(value);
			}));
		py += 22;

		this.exportButton = this.addRenderableWidget(Button.builder(
			Component.translatable("repolish.button.export_video").withStyle(ChatFormatting.GOLD),
			button -> {
				if (this.minecraft != null) {
					if (ReplayController.isExporting()) {
						replay.cancelExport(this.minecraft);
					} else {
						replay.startExport(this.minecraft);
					}
				}
			}
		).bounds(panelX, py, panelW, 20).build());
	}

	private Component playLabel() {
		return Component.literal(ReplayController.get().isPlaying() ? "Pause" : "Play");
	}

	private Component compareLabel() {
		return Component.literal(ReplayController.get().isCompareRaw() ? "Look: Raw TAS" : "Look: Smooth");
	}

	private void refreshTransportLabels() {
		if (this.playPauseButton != null) {
			this.playPauseButton.setMessage(this.playLabel());
		}
		if (this.compareButton != null) {
			this.compareButton.setMessage(this.compareLabel());
		}
	}

	private <T> CycleButton<T> cycle(int x, int y, int width, int height, Component name, Function<T, Component> label, T initial, Collection<T> values, Consumer<T> onChange) {
		return CycleButton.builder(label, initial)
			.withValues(values.stream().toList())
			.create(x, y, width, height, name, (button, value) -> onChange.accept(value));
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Keep the live 3D recreated first-person world completely unblurred behind the editor UI.
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// While capturing frames for video export, do not draw any editor UI into the framebuffer.
		if (ReplayController.isExporting()) {
			return;
		}

		ReplayController replay = ReplayController.get();
		this.refreshTransportLabels();

		if (!this.hideInspector) {
			int panelW = 176;
			int panelX = this.width - panelW - 8;
			graphics.fill(panelX - 4, 4, this.width - 4, 220, 0x90101216);
		}

		// Bottom timeline bar backdrop
		int barTop = this.height - 54;
		graphics.fill(4, barTop, this.width - 4, this.height - 4, 0xA8101216);

		this.renderTimelineTrack(graphics, replay);

		super.render(graphics, mouseX, mouseY, partialTick);

		// Status readout above timeline
		String timeInfo = String.format(
			Locale.ROOT,
			"%s / %s   [In: %.2fs  Out: %.2fs]",
			formatSeconds(replay.playhead()),
			formatSeconds(replay.duration()),
			replay.inTime(),
			replay.outTime()
		);
		graphics.drawString(this.font, timeInfo, 10, barTop + 3, 0xFFE8ECF2);

		TakeData.Sample sample = ReplayController.currentSample();
		if (sample != null) {
			String lookMode = replay.isCompareRaw() ? "RAW TAS" : "SMOOTHED";
			String cameraInfo = String.format(
				Locale.ROOT,
				"%s  Yaw: %.1f°  Pitch: %.1f°",
				lookMode,
				sample.yaw(),
				sample.pitch()
			);
			int infoW = this.font.width(cameraInfo);
			graphics.drawString(this.font, cameraInfo, this.width - infoW - 10, barTop + 3, replay.isCompareRaw() ? 0xFFFF8866 : 0xFF7CE38B);
		}

		if (ReplayController.isEncoding()) {
			String enc = "Encoding video: " + ReplayController.progressPercent() + "%";
			graphics.drawString(this.font, enc, 248, 13, 0xFFE7C36A);
		}
	}

	private void renderTimelineTrack(GuiGraphics graphics, ReplayController replay) {
		int trackX = 10;
		int trackW = Math.max(40, this.width - 20);
		int trackY = this.height - 40;
		int trackH = 12;

		// Full track background
		graphics.fill(trackX, trackY, trackX + trackW, trackY + trackH, 0xFF22262E);

		double duration = Math.max(0.05, replay.duration());
		int inX = trackX + (int) Math.round((replay.inTime() / duration) * trackW);
		int outX = trackX + (int) Math.round((replay.outTime() / duration) * trackW);
		inX = Math.max(trackX, Math.min(trackX + trackW, inX));
		outX = Math.max(inX + 1, Math.min(trackX + trackW, outX));

		// Active [In .. Out] region highlight
		graphics.fill(inX, trackY, outX, trackY + trackH, 0xFF2E4F3E);
		// In / Out bracket handles
		graphics.fill(inX, trackY - 1, inX + 2, trackY + trackH + 1, 0xFF63D471);
		graphics.fill(outX - 2, trackY - 1, outX, trackY + trackH + 1, 0xFF63D471);

		// Markers
		TakeData take = replay.take();
		if (take != null) {
			for (double marker : take.markers()) {
				int mx = trackX + (int) Math.round((marker / duration) * trackW);
				if (mx >= trackX && mx <= trackX + trackW) {
					graphics.fill(mx, trackY, mx + 2, trackY + trackH, 0xFFE7C36A);
				}
			}
		}

		// Playhead cursor
		int headX = trackX + (int) Math.round((replay.playhead() / duration) * trackW);
		headX = Math.max(trackX, Math.min(trackX + trackW - 2, headX));
		graphics.fill(headX - 1, trackY - 2, headX + 2, trackY + trackH + 2, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (this.isInsideTimeline(event.x(), event.y())) {
			this.scrubbingTimeline = true;
			ReplayController.get().setPlaying(false);
			this.scrubToMouse(event.x());
			this.refreshTransportLabels();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.scrubbingTimeline) {
			this.scrubToMouse(event.x());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.scrubbingTimeline) {
			this.scrubbingTimeline = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		ReplayController replay = ReplayController.get();
		if (event.isEscape() && ReplayController.isExporting()) {
			if (this.minecraft != null) {
				replay.cancelExport(this.minecraft);
			}
			return true;
		}
		if (!(this.getFocused() instanceof EditBox)) {
			int key = event.key();
			if (key == GLFW.GLFW_KEY_SPACE) {
				replay.togglePlaying();
				this.refreshTransportLabels();
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT) {
				replay.stepFrames(-1);
				this.refreshTransportLabels();
				return true;
			}
			if (key == GLFW.GLFW_KEY_RIGHT) {
				replay.stepFrames(1);
				this.refreshTransportLabels();
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_BRACKET) {
				replay.setInTime(replay.playhead());
				return true;
			}
			if (key == GLFW.GLFW_KEY_RIGHT_BRACKET) {
				replay.setOutTime(replay.playhead());
				return true;
			}
			if (key == GLFW.GLFW_KEY_R) {
				replay.setCompareRaw(!replay.isCompareRaw());
				this.refreshTransportLabels();
				return true;
			}
			if (key == GLFW.GLFW_KEY_H) {
				this.hideInspector = !this.hideInspector;
				this.clearWidgets();
				this.init();
				return true;
			}
			if (key == GLFW.GLFW_KEY_M) {
				replay.addMarkerAtPlayhead();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private boolean isInsideTimeline(double mouseX, double mouseY) {
		int trackX = 8;
		int trackW = Math.max(40, this.width - 16);
		int trackY = this.height - 43;
		int trackH = 18;
		return mouseX >= trackX && mouseX <= trackX + trackW && mouseY >= trackY && mouseY <= trackY + trackH;
	}

	private void scrubToMouse(double mouseX) {
		int trackX = 10;
		int trackW = Math.max(40, this.width - 20);
		double fraction = (mouseX - trackX) / (double) trackW;
		fraction = Math.max(0.0, Math.min(1.0, fraction));
		ReplayController replay = ReplayController.get();
		replay.setPlayhead(fraction * replay.duration());
	}

	private static String formatSeconds(double seconds) {
		int whole = Math.max(0, (int) seconds);
		int mins = whole / 60;
		double rem = Math.max(0.0, seconds - mins * 60);
		return String.format(Locale.ROOT, "%d:%05.2f", mins, rem);
	}

	@Override
	public void onClose() {
		ExportSettings.get().save();
		if (this.minecraft != null) {
			ReplayController.get().closeReplay(this.minecraft);
			this.minecraft.setScreen(this.parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
