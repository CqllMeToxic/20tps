package dev.twentytps.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * the two built-in looks: "Minecraft" (pixel-art targets, hearts, XP bar, vanilla menus) and
 * "Classic" (smooth shapes and pink accents). skins still replace the target and cursor
 * graphics either way. the look only changes what 20tps draws itself.
 */
public final class Theme {
	// minecraft's chat colours
	public static final int MC_GREEN = 0xFF55FF55;
	public static final int MC_AQUA = 0xFF55FFFF;
	/** a lighter blue than minecraft's own §9, which is hard to read on dark backgrounds. */
	public static final int MC_BLUE = 0xFF5FA8FF;
	public static final int MC_YELLOW = 0xFFFFFF55;
	public static final int MC_GOLD = 0xFFFFAA00;
	public static final int MC_RED = 0xFFFF5555;
	public static final int MC_PINK = 0xFFFF55FF;
	public static final int MC_GRAY = 0xFFAAAAAA;
	/** the target block's colours. */
	public static final int TARGET_RED = 0xFFC8342B;
	public static final int TARGET_WHITE = 0xFFEFE6D2;

	private Theme() {}

	public static boolean minecraft() {
		return Settings.minecraftLook;
	}

	/** highlight colour for titles, selections and checkmarks. */
	public static int accent() {
		return minecraft() ? MC_YELLOW : 0xFFFF6FAE;
	}

	public static int subtle() {
		return minecraft() ? MC_GRAY : 0xFFAAAACC;
	}

	/** text with a 1-pixel black outline, like the XP level number. (x, y) is the top-left. */
	public static void outlined(GuiGraphics g, Font font, String text, int x, int y, int color) {
		int outline = color & 0xFF000000;
		g.drawString(font, text, x + 1, y, outline, false);
		g.drawString(font, text, x - 1, y, outline, false);
		g.drawString(font, text, x, y + 1, outline, false);
		g.drawString(font, text, x, y - 1, outline, false);
		g.drawString(font, text, x, y, color, false);
	}
}
