package dev.imxmax.repolish.client.screen;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.CaptureController;
import dev.imxmax.repolish.client.FfmpegInstaller;
import dev.imxmax.repolish.client.ReplayController;
import dev.imxmax.repolish.take.TakeData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RecordingsScreen extends Screen {
	private final Screen parent;
	private List<Path> files = List.of();
	private int scroll;

	public RecordingsScreen(Screen parent) {
		super(Component.translatable("repolish.screen.recordings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.files = listRecordings();
		int rows = Math.max(1, (this.height - 90) / 24);
		int maxScroll = Math.max(0, this.files.size() - rows);
		if (this.scroll > maxScroll) {
			this.scroll = maxScroll;
		}
		int y = 36;
		int shown = 0;
		boolean inWorld = this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null;
		for (int index = this.scroll; index < this.files.size() && shown < rows; index++) {
			Path file = this.files.get(index);
			boolean isTake = isTakeFile(file);
			String name = file.getFileName().toString();
			String prefix = isTake ? "[Take] " : "[Video] ";
			String label = prefix + name;
			if (label.length() > 38) {
				label = label.substring(0, 35) + "...";
			}
			Component mainLabel = isTake
				? Component.literal(label).withStyle(ChatFormatting.AQUA)
				: Component.literal(label);

			this.addRenderableWidget(Button.builder(mainLabel, button -> {
				if (isTake && inWorld) {
					ReplayController.get().openTakeFile(this.minecraft, file);
				} else {
					reveal(file);
				}
			}).bounds(this.width / 2 - 160, y, 200, 20).build());

			if (isTake && inWorld) {
				this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.edit_take"), button -> {
					ReplayController.get().openTakeFile(this.minecraft, file);
				}).bounds(this.width / 2 + 44, y, 48, 20).build());
			} else {
				this.addRenderableWidget(Button.builder(Component.literal("Folder"), button -> reveal(file))
					.bounds(this.width / 2 + 44, y, 48, 20)
					.build());
			}

			this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.delete"), button -> this.confirmDelete(file))
				.bounds(this.width / 2 + 96, y, 64, 20)
				.build());
			y += 24;
			shown++;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.open_folder"), button -> reveal(directory()))
			.bounds(this.width / 2 - 160, this.height - 28, 154, 20)
			.build());
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.done"), button -> this.onClose())
			.bounds(this.width / 2 + 6, this.height - 28, 154, 20)
			.build());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int rows = Math.max(1, (this.height - 90) / 24);
		int maxScroll = Math.max(0, this.files.size() - rows);
		int next = this.scroll + (scrollY > 0 ? -1 : 1);
		next = Math.max(0, Math.min(maxScroll, next));
		if (next != this.scroll) {
			this.scroll = next;
			this.clearWidgets();
			this.init();
		}
		return true;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		if (this.files.isEmpty()) {
			graphics.drawWordWrap(this.font, Component.translatable("repolish.recordings.empty"), this.width / 2 - 160, 48, 320, 0xFFCCCCCC);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}

	private void confirmDelete(Path file) {
		if (this.minecraft == null) {
			return;
		}
		this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				delete(file);
			}
			this.minecraft.setScreen(this);
			this.clearWidgets();
			this.init();
		}, Component.translatable("repolish.recordings.delete"), Component.literal(file.getFileName().toString()),
			Component.translatable("repolish.button.delete"), Component.translatable("repolish.button.cancel")));
	}

	private Path directory() {
		Path directory = CaptureController.recordingsDirectory(this.minecraft);
		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not create the recordings folder", exception);
		}
		return directory;
	}

	private List<Path> listRecordings() {
		Path directory = this.directory();
		List<Path> found = new ArrayList<>();
		try (var stream = Files.list(directory)) {
			stream.filter(RecordingsScreen::isRecordingOrTake).forEach(found::add);
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not list recordings", exception);
		}
		found.sort(Comparator.comparing(RecordingsScreen::modified).reversed());
		return found;
	}

	private static boolean isTakeFile(Path path) {
		return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rtake");
	}

	private static boolean isRecordingOrTake(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".rtake") || name.endsWith(".avi") || name.endsWith(".mp4") || name.endsWith(".mkv");
	}

	private static java.nio.file.attribute.FileTime modified(Path path) {
		try {
			return Files.getLastModifiedTime(path);
		} catch (IOException exception) {
			return java.nio.file.attribute.FileTime.fromMillis(0L);
		}
	}

	private static void delete(Path file) {
		try {
			if (isTakeFile(file)) {
				try {
					TakeData take = TakeData.load(file);
					if (take.audioFileName() != null && !take.audioFileName().isBlank() && file.getParent() != null) {
						Files.deleteIfExists(file.getParent().resolve(take.audioFileName()));
					}
				} catch (IOException ignored) {
				}
			}
			Files.deleteIfExists(file);
			Files.deleteIfExists(Path.of(file.toString() + ".markers.txt"));
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not delete {}", file, exception);
		}
	}

	private static void reveal(Path path) {
		try {
			String absolute = path.toAbsolutePath().toString();
			if (FfmpegInstaller.isWindows()) {
				new ProcessBuilder("explorer.exe", Files.isDirectory(path) ? absolute : "/select," + absolute).start();
				return;
			}
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.contains("mac")) {
				if (Files.isDirectory(path)) {
					new ProcessBuilder("open", absolute).start();
				} else {
					new ProcessBuilder("open", "-R", absolute).start();
				}
				return;
			}
			new ProcessBuilder("xdg-open", Files.isDirectory(path) ? absolute : path.getParent().toAbsolutePath().toString()).start();
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not open {}", path, exception);
		}
	}
}
