package dev.twentytps.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/**
 * 20tps's front page. replaces Minecraft's title screen (unless disabled in settings) and is
 * also what the 20tps key opens in-game.
 */
public class MainMenuScreen extends Screen {
	private static final double MENU_BPM = 100;
	private static final int[] PALETTE = {0xFFFF6FAE, 0xFF6FD0FF, 0xFFB48CFF, 0xFFFFB35C};

	/** the one vanilla title screen the player actually asked for. the mixin lets this one through. */
	public static TitleScreen allowedTitle;

	private static final String[] SPLASHES = {
			"Now with 20 ticks per second!", "Not tick-locked!", "Click to the beat!", "Note blocks included!",
			"Also try Minecraft!", "Hit the target!", "100% rhythm!", "Sliders sold separately!",
			"Press Space to skip!", "Hearts are health!", "Combo = XP!", "Bullseye!", "Played with a mouse!",
			"Clickity click!", "20TPS is a rhythm game!", "Press Z or X to tap!", "CqllMeToxic was here lol", "Playable with a tablet!",
			"W circleslop", "PP farming update coming soon!"
	};

	private final long openedAt = System.nanoTime();
	private final String splash = SPLASHES[(int) (Math.random() * SPLASHES.length)];

	public MainMenuScreen() {
		super(Component.literal("20tps"));
	}

	private boolean inWorld() {
		return minecraft.level != null;
	}

	@Override
	protected void init() {
		// start scanning songs now so the list is ready by the time you hit Play.
		Library.ensureStarted();
		int w = 200;
		int x = width / 2 - w / 2;
		int y = height / 2 - 10;

		addRenderableWidget(Button.builder(Component.literal("Play"), b -> minecraft.setScreen(new SongSelectScreen(this)))
				.bounds(x, y, w, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.literal("Settings"), b -> minecraft.setScreen(new SettingsScreen(this)))
				.bounds(x, y, w, 20).build());
		y += 24;

		if (inWorld()) {
			addRenderableWidget(Button.builder(Component.literal("Back to Game"), b -> onClose())
					.bounds(x, y, w, 20).build());
		} else {
			int half = (w - 4) / 2;
			addRenderableWidget(Button.builder(Component.literal("Minecraft"), b -> {
				allowedTitle = new TitleScreen();
				minecraft.setScreen(allowedTitle);
			}).bounds(x, y, half, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Quit Game"), b -> minecraft.stop())
					.bounds(x + half + 4, y, half, 20).build());
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return inWorld();
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (Theme.minecraft()) {
			super.renderBackground(g, mouseX, mouseY, partialTick);
			return;
		}
		g.fillGradient(0, 0, width, height, 0xFF101020, 0xFF201030);

		// rings ripple outward from the logo on the beat.
		double beats = (System.nanoTime() - openedAt) / 1_000_000_000.0 * MENU_BPM / 60.0;
		float cx = width / 2f;
		float cy = height / 2f - 50;
		float maxR = Math.max(width, height) * 0.6f;
		for (int i = 0; i < 4; i++) {
			double age = beats - Math.floor(beats) + i;
			float k = (float) (age / 4);
			int color = PALETTE[Math.floorMod((int) Math.floor(beats) - i, PALETTE.length)];
			Shapes.ring(g, cx, cy, 20 + k * maxR, 2, Shapes.fade(color, 0.35f * (1 - k)));
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);

		double beats = (System.nanoTime() - openedAt) / 1_000_000_000.0 * MENU_BPM / 60.0;
		float beatPhase = (float) (beats - Math.floor(beats));
		float bump = 1 + 0.08f * (1 - beatPhase) * (1 - beatPhase);

		Matrix3x2fStack pose = g.pose();
		if (Theme.minecraft()) {
			renderMinecraftLogo(g);
			return;
		}
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f - 50);
		pose.scale(4f * bump, 4f * bump);
		g.drawString(font, "20TPS", -font.width("20TPS") / 2, -font.lineHeight / 2 + 1, 0xFFFF6FAE, true);
		pose.popMatrix();

		g.drawCenteredString(font, "click on the beat · play the melody", width / 2, height / 2 - 30, 0xFFAAAACC);
	}

	private void renderMinecraftLogo(GuiGraphics g) {
		Matrix3x2fStack pose = g.pose();
		String title = "20TPS";
		float scale = 6f;
		float cx = width / 2f;
		float cy = height / 2f - 80;
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		int x = -font.width(title) / 2;
		// a darker copy nudged down and right gives the logo a bit of depth.
		g.drawString(font, title, x + 1, 1, 0xFF3A3A3A, false);
		g.drawString(font, title, x, 0, 0xFFD0D0D0, false);
		pose.popMatrix();

		g.drawCenteredString(font, "C L I C K T O T H E R H Y T H M", width / 2, Math.round(cy + font.lineHeight * scale + 2), 0xFFFFFFFF);

		float t = (System.nanoTime() - openedAt) / 1_000_000_000f;
		float pulse = 1.8f - Math.abs((float) Math.sin(t * Math.PI * 2 / 1.0)) * 0.1f;
		float splashScale = pulse * 100f / (font.width(splash) + 32);
		pose.pushMatrix();
		pose.translate(cx + font.width(title) * scale / 2f + 4, cy + 8 * scale);
		pose.rotate((float) Math.toRadians(-20));
		pose.scale(splashScale, splashScale);
		g.drawCenteredString(font, splash, 0, -8, Theme.MC_YELLOW);
		pose.popMatrix();
	}
}
