package dev.twentytps.client;

import dev.twentytps.client.osu.Lazer;
import dev.twentytps.client.osu.Skin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** the settings, split into tabs. everything saves when you close the screen. */
public class SettingsScreen extends Screen {
	private static final int W = 150;
	private static final int H = 20;
	private static final int GAP = 4;

	private enum Tab {GAMEPLAY("Gameplay"), AUDIO("Audio"), VISUAL("Visual"), LIBRARY("Library");
		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private final Screen parent;
	private Tab tab = Tab.GAMEPLAY;
	private final List<String> hints = new ArrayList<>();
	private int slot;
	private int top;
	private boolean libraryChanged;

	public SettingsScreen(Screen parent) {
		super(Component.literal("20tps Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		skinDropdown = null;
		hints.clear();
		slot = 0;

		// tab bar
		Tab[] tabs = Tab.values();
		int tabW = 80;
		int tabX = width / 2 - (tabs.length * (tabW + GAP) - GAP) / 2;
		for (Tab t : tabs) {
			Button b = addRenderableWidget(Button.builder(Component.literal(t.label), btn -> {
				tab = t;
				rebuildWidgets();
			}).bounds(tabX, 32, tabW, H).build());
			b.active = t != tab;
			tabX += tabW + GAP;
		}
		top = 32 + H + 12;

		switch (tab) {
			case GAMEPLAY -> {
				slider("Audio offset", Settings.audioOffsetMs, -300, 300, 5,
						v -> (v > 0 ? "+" : "") + (int) v + " ms", v -> Settings.audioOffsetMs = (int) v);
				slider("Approach speed", Settings.approachSpeed, 0.5, 2.0, 0.05, v -> String.format("%.2fx", v), v -> Settings.approachSpeed = v);
				slider("Target size", Settings.targetSize, 0.6, 1.5, 0.05, v -> String.format("%.2fx", v), v -> Settings.targetSize = v);
				slider("Song speed", Settings.songSpeed, 0.5, 2.0, 0.05, v -> String.format("%.2fx", v), v -> Settings.songSpeed = v);
				slider("Timing leniency", Settings.timingLeniency, 0.5, 2.0, 0.05, v -> String.format("%.2fx", v), v -> Settings.timingLeniency = v);
				toggle("No fail", Settings.noFail, v -> Settings.noFail = v);
				toggle("Mouse buttons tap", Settings.mouseTaps, v -> Settings.mouseTaps = v);
				toggle("Fill screen", Settings.fillScreen, v -> Settings.fillScreen = v);
				toggle("Map cursor to playfield", Settings.mapToPlayfield, v -> Settings.mapToPlayfield = v);
				slider("Cursor sensitivity", Settings.cursorSensitivity, 0.1, 6.0, 0.05,
						v -> String.format("%.2fx", v), v -> Settings.cursorSensitivity = v);
				toggle("Autoplay", Settings.autoplay, v -> Settings.autoplay = v);
				button("Tap keys...", () -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)));
				hints.add("Raise audio offset if you keep hitting late, lower it if early.");
				hints.add("Song speed, leniency above 1x, and no fail don't count for personal bests.");
				hints.add("Tap keys (Z and X by default) are under Controls > 20tps.");
				hints.add("Fill screen spreads targets over the whole window; off keeps a 4:3 box.");
				hints.add("Tablets: the pen tip and barrel buttons tap. Map cursor to playfield gives the box your whole area.");
				hints.add("Sensitivity scales cursor movement. Keep 1x for a tablet in absolute mode.");
			}
			case AUDIO -> {
				slider("Music volume", Settings.musicVolume, 0, 1, 0.05, SettingsScreen::percent, v -> Settings.musicVolume = v);
				slider("Hit volume", Settings.hitVolume, 0, 1, 0.05, SettingsScreen::percent, v -> Settings.hitVolume = v);
				slider("Click track", Settings.metronomeVolume, 0, 1, 0.05,
						v -> v <= 0 ? "Off" : percent(v), v -> Settings.metronomeVolume = v);
				hints.add("The click track plays on built-in songs; imported ones have their own music.");
			}
			case VISUAL -> {
				List<String> skins = new ArrayList<>();
				skins.add("");
				skins.addAll(Skin.available());
				String current = skins.contains(Settings.skin) ? Settings.skin : "";
				// skin names get long, so the dropdown gets a whole row.
				if (slot % 2 == 1) slot++;
				int[] pos = next();
				slot++;
				skinDropdown = new Dropdown<>("Skin", skins, current, Skin::displayName, v -> Settings.skin = v,
						pos[0], pos[1], 2 * W + GAP, H);
				addRenderableWidget(skinDropdown.button());
				int[] look = next();
				addRenderableWidget(CycleButton.booleanBuilder(Component.literal("Minecraft"), Component.literal("Classic"), Settings.minecraftLook)
						.create(look[0], look[1], W, H, Component.literal("Look"), (b, v) -> Settings.minecraftLook = v));
				button("Open skins folder", () -> openFolder(Skin.folder()));
				slider("Background dim", Settings.backgroundDim, 0, 1, 0.05, SettingsScreen::percent, v -> Settings.backgroundDim = v);
				slider("Cursor size", Settings.cursorSize, 0.25, 3.0, 0.05, v -> String.format("%.2fx", v), v -> Settings.cursorSize = v);
				slider("Cursor trail", Settings.cursorTrailMs, 0, 600, 10,
						v -> v <= 0 ? "Off" : (int) v + " ms", v -> Settings.cursorTrailMs = v);
				toggle("Judgements", Settings.showJudgements, v -> Settings.showJudgements = v);
				int[] style = next();
				addRenderableWidget(CycleButton.builder((String v) -> Component.literal(switch (v) {
							case "words" -> "Words";
							case "skin" -> "Skin images";
							default -> "300 / 100 / 50";
						}), Settings.judgementStyle)
						.withValues(List.of("numbers", "words", "skin"))
						.create(style[0], style[1], W, H, Component.literal("Judgement text"), (b, v) -> Settings.judgementStyle = v));
				toggle("Hit error bar", Settings.hitErrorBar, v -> Settings.hitErrorBar = v);
				toggle("Beatmap colours", Settings.beatmapColours, v -> Settings.beatmapColours = v);
				toggle("Note names", Settings.showNoteNames, v -> Settings.showNoteNames = v);
				hints.add(skins.size() - 1 + " skins installed. Skins with a cursor or cursortrail image use it.");
				hints.add("Beatmap colours: use a map's own combo colours instead of the skin's.");
				hints.add("Look changes 20tps's own graphics; a skin's images still take priority.");
			}
			case LIBRARY -> {
				toggle("Use osu! folder", Settings.useOsuFolder, v -> {
					Settings.useOsuFolder = v;
					libraryChanged = true;
				});
				toggle("Use osu!lazer", Settings.useLazer, v -> {
					Settings.useLazer = v;
					libraryChanged = true;
				});
				button("Open charts folder", () -> openFolder(Chart.userChartDir()));
				toggle("Start in 20tps", Settings.replaceTitleScreen, v -> Settings.replaceTitleScreen = v);
				button("Rescan library", () -> {
					Library.reload();
					libraryChanged = false;
				});

				int y = top + ((slot + 1) / 2) * (H + GAP) + 6;
				EditBox path = new EditBox(font, width / 2 - W - GAP / 2, y + 10, 2 * W + GAP, H, Component.literal("osu! folder"));
				path.setMaxLength(400);
				path.setValue(Settings.osuFolder);
				path.setResponder(v -> {
					Settings.osuFolder = v.strip();
					libraryChanged = true;
				});
				addRenderableWidget(path);
				osuPathY = y;
				hints.add(" ");
				hints.add(" ");
				hints.add(" ");
				hints.add(osuStatus());
				hints.add(Settings.useLazer ? (Lazer.status().isEmpty() ? (Lazer.available() ? "osu!lazer found; it's read during the library scan." : "osu!lazer data not found.") : Lazer.status()) : "osu!lazer: off");
				hints.add("Beatmaps and skins are read in place from osu! and osu!lazer, without copying.");
				hints.add("Reading osu!lazer needs the .NET runtime (8 or newer).");
			}
		}

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(width / 2 - 100, height - 28, 200, H).build());
	}

	private int osuPathY = -1;
	private Dropdown<String> skinDropdown;

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (skinDropdown != null && skinDropdown.mouseClicked(event)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (skinDropdown != null && skinDropdown.mouseScrolled(x, y, vertical)) return true;
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (skinDropdown != null && skinDropdown.isOpen() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			skinDropdown.close();
			return true;
		}
		return super.keyPressed(event);
	}

	private static String osuStatus() {
		if (Settings.osuFolder.isBlank()) return "No osu! folder set.";
		Path songs = Path.of(Settings.osuFolder, "Songs");
		if (!Files.isDirectory(songs)) return "No Songs folder found there.";
		return "Found " + Chart.folderEntries(songs).size() + " beatmap folders.";
	}

	/** the next free spot in the two-column grid. */
	private int[] next() {
		int col = slot % 2;
		int row = slot / 2;
		slot++;
		return new int[]{col == 0 ? width / 2 - W - GAP / 2 : width / 2 + GAP / 2, top + row * (H + GAP)};
	}

	private void slider(String label, double current, double min, double max, double step,
			DoubleFunction<String> format, DoubleConsumer apply) {
		int[] pos = next();
		addRenderableWidget(new Slider(pos[0], pos[1], label, current, min, max, step, format, apply));
	}

	private void toggle(String label, boolean current, Consumer<Boolean> apply) {
		int[] pos = next();
		addRenderableWidget(CycleButton.onOffBuilder(current).create(pos[0], pos[1], W, H, Component.literal(label), (b, v) -> apply.accept(v)));
	}

	private void button(String label, Runnable action) {
		int[] pos = next();
		addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(pos[0], pos[1], W, H).build());
	}

	private static String percent(double v) {
		return Math.round(v * 100) + "%";
	}

	private void openFolder(Path dir) {
		try {
			Files.createDirectories(dir);
			Util.getPlatform().openPath(dir);
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't open {}: {}", dir, e.getMessage());
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
		if (osuPathY >= 0 && tab == Tab.LIBRARY) {
			g.drawString(font, "osu! install folder", width / 2 - W - GAP / 2, osuPathY, 0xFFAAAACC);
		}
		int y = top + ((slot + 1) / 2) * (H + GAP) + 6;
		for (String hint : hints) {
			g.drawCenteredString(font, hint, width / 2, y, 0xFF8888AA);
			y += 11;
		}
		if (skinDropdown != null) skinDropdown.render(g, mouseX, mouseY);
	}

	@Override
	public void onClose() {
		Settings.save();
		if (libraryChanged) Library.reload();
		minecraft.setScreen(parent);
	}

	/** a slider from min to max that snaps to `step`. */
	private static final class Slider extends AbstractSliderButton {
		private final String label;
		private final double min;
		private final double max;
		private final double step;
		private final DoubleFunction<String> format;
		private final DoubleConsumer apply;

		Slider(int x, int y, String label, double current, double min, double max, double step,
				DoubleFunction<String> format, DoubleConsumer apply) {
			super(x, y, W, H, Component.empty(), (current - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
			this.apply = apply;
			updateMessage();
		}

		private double actual() {
			double v = min + value * (max - min);
			// round twice so 1.0 comes out as 1.0 and not 1.0000000000000002.
			return Math.round(Math.round(v / step) * step * 10000) / 10000.0;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + format.apply(actual())));
		}

		@Override
		protected void applyValue() {
			apply.accept(actual());
		}
	}
}
