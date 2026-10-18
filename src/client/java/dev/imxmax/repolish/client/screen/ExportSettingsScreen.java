package dev.imxmax.repolish.client.screen;

import dev.imxmax.repolish.client.CaptureController;
import dev.imxmax.repolish.client.ExportSettings;
import dev.imxmax.repolish.client.ShaderPacks;
import net.minecraft.ChatFormatting;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ExportSettingsScreen extends Screen {
	private final Screen parent;
	private EditBox nameBox;
	private LabeledSlider fovSlider;

	public ExportSettingsScreen(Screen parent) {
		super(Component.translatable("repolish.screen.export"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ExportSettings settings = ExportSettings.get();
		boolean wide = this.width >= 480;
		int columnWidth = wide ? 210 : Math.min(320, this.width - 16);
		int left = wide ? this.width / 2 - columnWidth - 6 : (this.width - columnWidth) / 2;
		int right = wide ? this.width / 2 + 6 : left;
		int y = 32;
		int y2 = 32;

		this.nameBox = new EditBox(this.font, left, y, wide ? columnWidth * 2 + 12 : columnWidth, 20, Component.translatable("repolish.setting.name"));
		this.nameBox.setMaxLength(64);
		this.nameBox.setValue(settings.clipName);
		this.nameBox.setResponder(value -> settings.clipName = value);
		this.nameBox.setTooltip(Tooltip.create(Component.translatable("repolish.setting.name.tooltip")));
		this.addRenderableWidget(this.nameBox);
		y += 26;
		y2 = y;

		this.addRenderableWidget(this.cycle(left, y, columnWidth, Component.translatable("repolish.setting.resolution"),
			(ExportSettings.Resolution value) -> Component.literal(value.label()), settings.resolution, java.util.Arrays.asList(ExportSettings.Resolution.values()),
			value -> settings.resolution = value));
		y += 24;

		this.addRenderableWidget(this.cycle(left, y, columnWidth, Component.translatable("repolish.setting.fps"),
			(Integer fps) -> Component.literal(fps + " fps"), settings.fps, List.of(30, 60, 120),
			value -> settings.fps = value));
		y += 24;

		this.addRenderableWidget(CycleButton.onOffBuilder(!settings.matchFov)
			.create(left, y, columnWidth, 20, Component.translatable("repolish.setting.custom_fov"), (button, value) -> {
				settings.matchFov = !value;
				if (this.fovSlider != null) {
					this.fovSlider.active = value;
				}
			}));
		y += 24;

		this.fovSlider = new LabeledSlider(left, y, columnWidth, 20, "FOV", 30, 140, settings.fov, value -> settings.fov = value);
		this.fovSlider.active = !settings.matchFov;
		this.fovSlider.setTooltip(Tooltip.create(Component.translatable("repolish.setting.fov.tooltip")));
		this.addRenderableWidget(this.fovSlider);

		int sideX = wide ? right : left;
		int sideY = wide ? y2 : y + 24;
		LabeledSlider smoothing = new LabeledSlider(sideX, sideY, columnWidth, 20, "Smoothing", 0, 100, settings.smoothing, value -> settings.smoothing = value);
		smoothing.setTooltip(Tooltip.create(Component.translatable("repolish.setting.smoothing.tooltip")));
		this.addRenderableWidget(smoothing);
		sideY += 24;

		LabeledSlider responsiveness = new LabeledSlider(sideX, sideY, columnWidth, 20, "Responsiveness", 0, 100, settings.responsiveness, value -> settings.responsiveness = value);
		responsiveness.setTooltip(Tooltip.create(Component.translatable("repolish.setting.responsiveness.tooltip")));
		this.addRenderableWidget(responsiveness);
		sideY += 24;

		this.addRenderableWidget(CycleButton.onOffBuilder(settings.includeHud)
			.create(sideX, sideY, columnWidth, 20, Component.translatable("repolish.setting.hud"), (button, value) -> settings.includeHud = value));
		sideY += 24;

		this.addRenderableWidget(CycleButton.onOffBuilder(settings.includeAudio)
			.create(sideX, sideY, columnWidth, 20, Component.translatable("repolish.setting.audio"), (button, value) -> settings.includeAudio = value));
		sideY += 24;

		List<String> shaders = ShaderPacks.choices(this.minecraft);
		if (!shaders.contains(settings.shader)) {
			settings.shader = ShaderPacks.CURRENT;
		}
		this.addRenderableWidget(this.cycle(sideX, sideY, columnWidth, Component.translatable("repolish.setting.shader"),
			(String choice) -> Component.literal(ShaderPacks.label(choice)), settings.shader, shaders,
			value -> settings.shader = value));
		sideY += 28;

		int buttonsX = wide ? right : left;
		int buttonsY = sideY;
		boolean recording = CaptureController.isRecording();
		Component recordLabel = recording
			? Component.translatable("repolish.button.stop").withStyle(ChatFormatting.RED)
			: Component.translatable("repolish.button.start");
		this.addRenderableWidget(Button.builder(recordLabel, button -> {
			if (CaptureController.isRecording()) {
				this.stopRecording();
			} else {
				this.startRecording();
			}
		}).bounds(buttonsX, buttonsY, columnWidth, 20).build());
		buttonsY += 24;
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.recordings"), button -> {
			ExportSettings.get().save();
			if (this.minecraft != null) {
				this.minecraft.setScreen(new RecordingsScreen(this));
			}
		}).bounds(buttonsX, buttonsY, columnWidth, 20).build());
		buttonsY += 24;
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.done"), button -> this.onClose())
			.bounds(buttonsX, buttonsY, columnWidth, 20)
			.build());
	}

	private <T> CycleButton<T> cycle(int x, int y, int width, Component name, java.util.function.Function<T, Component> label, T initial, java.util.Collection<T> values, java.util.function.Consumer<T> onChange) {
		return CycleButton.builder(label, initial)
			.withValues(values.stream().toList())
			.create(x, y, width, 20, name, (button, value) -> onChange.accept(value));
	}

	private void stopRecording() {
		if (this.minecraft != null) {
			CaptureController.toggle(this.minecraft);
			this.minecraft.setScreen(null);
		}
	}

	private void startRecording() {
		ExportSettings.get().save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
			CaptureController.startRecording(this.minecraft);
		}
	}

	@Override
	public void onClose() {
		ExportSettings.get().save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
		int hintY = this.height - 34;
		if (hintY > 280) {
			graphics.drawWordWrap(this.font, Component.translatable("repolish.hint"), 12, hintY, this.width - 24, 0xFFCCCCCC);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
