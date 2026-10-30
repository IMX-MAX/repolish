package dev.imxmax.repolish.client.screen;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public final class LabeledSlider extends AbstractSliderButton {
	private final String label;
	private final int min;
	private final int max;
	private final IntConsumer onChange;
	private boolean ready;

	public LabeledSlider(int x, int y, int width, int height, String label, int min, int max, int current, IntConsumer onChange) {
		super(x, y, width, height, Component.literal(label), (current - min) / (double) (max - min));
		this.label = label;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		this.ready = true;
		this.updateMessage();
	}

	public int selected() {
		return this.min + (int) Math.round(this.value * (this.max - this.min));
	}

	@Override
	protected void updateMessage() {
		if (!this.ready) {
			return;
		}
		this.setMessage(Component.literal(this.label + ": " + this.selected()));
	}

	@Override
	protected void applyValue() {
		if (this.ready) {
			this.onChange.accept(this.selected());
		}
	}
}
