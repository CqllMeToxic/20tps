package dev.twentytps.client;

import net.minecraft.client.gui.GuiGraphics;

/** filled discs and rings, drawn as horizontal strips. coordinates are in whatever units the pose uses. */
public final class Shapes {
	private Shapes() {}

	public static void disc(GuiGraphics g, float cx, float cy, float r, int color) {
		int top = Math.round(cy - r);
		int bottom = Math.round(cy + r);
		for (int y = top; y < bottom; y++) {
			float dy = y + 0.5f - cy;
			float half = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
			int x0 = Math.round(cx - half);
			int x1 = Math.round(cx + half);
			if (x1 > x0) g.fill(x0, y, x1, y + 1, color);
		}
	}

	public static void ring(GuiGraphics g, float cx, float cy, float r, float thickness, int color) {
		float inner = Math.max(0, r - thickness);
		int top = Math.round(cy - r);
		int bottom = Math.round(cy + r);
		for (int y = top; y < bottom; y++) {
			float dy = y + 0.5f - cy;
			float outerHalf = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
			int ox0 = Math.round(cx - outerHalf);
			int ox1 = Math.round(cx + outerHalf);
			if (Math.abs(dy) >= inner) {
				if (ox1 > ox0) g.fill(ox0, y, ox1, y + 1, color);
				continue;
			}
			float innerHalf = (float) Math.sqrt(inner * inner - dy * dy);
			int ix0 = Math.round(cx - innerHalf);
			int ix1 = Math.round(cx + innerHalf);
			if (ix0 > ox0) g.fill(ox0, y, ix0, y + 1, color);
			if (ox1 > ix1) g.fill(ix1, y, ox1, y + 1, color);
		}
	}

	/**
	 * a pixel-art bullseye like minecraft's target block: red and white bands on a `cells` x
	 * `cells` grid of chunky pixels, edged in `edge`. each pixel gets shaded a tiny bit, the way
	 * block textures are.
	 */
	public static void pixelBullseye(GuiGraphics g, float cx, float cy, float r, int cells, int edge, float alpha) {
		float p = 2 * r / cells;
		for (int i = 0; i < cells; i++) {
			for (int j = 0; j < cells; j++) {
				float dx = (i + 0.5f) * p - r;
				float dy = (j + 0.5f) * p - r;
				float d = (float) Math.sqrt(dx * dx + dy * dy) / r;
				if (d > 1) continue;
				int color = d > 0.84f ? edge : d > 0.62f ? Theme.TARGET_WHITE : d > 0.40f ? Theme.TARGET_RED
						: d > 0.18f ? Theme.TARGET_WHITE : Theme.TARGET_RED;
				color = shade(color, 1 - ((i * 7 + j * 13 + i * j) % 4) * 0.035f);
				g.fill(Math.round(cx - r + i * p), Math.round(cy - r + j * p),
						Math.round(cx - r + (i + 1) * p), Math.round(cy - r + (j + 1) * p), fade(color, alpha));
			}
		}
	}

	/** a ring of chunky `p`-sized pixels, one pixel thick, on a grid centred on (cx, cy). */
	public static void pixelRing(GuiGraphics g, float cx, float cy, float radius, float p, int color) {
		int n = (int) Math.ceil(radius / p) + 1;
		float inner = radius - p;
		for (int i = -n; i < n; i++) {
			for (int j = -n; j < n; j++) {
				float dx = (i + 0.5f) * p;
				float dy = (j + 0.5f) * p;
				float d = (float) Math.sqrt(dx * dx + dy * dy);
				if (d > radius || d < inner) continue;
				g.fill(Math.round(cx + i * p), Math.round(cy + j * p), Math.round(cx + (i + 1) * p), Math.round(cy + (j + 1) * p), color);
			}
		}
	}

	/** a square, positioned by its centre. */
	public static void square(GuiGraphics g, float cx, float cy, float size, int color) {
		float h = size / 2;
		g.fill(Math.round(cx - h), Math.round(cy - h), Math.round(cx + h), Math.round(cy + h), color);
	}

	/** makes an ARGB colour brighter or darker. */
	public static int shade(int argb, float k) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * k));
		int gr = Math.min(255, Math.round(((argb >> 8) & 0xFF) * k));
		int b = Math.min(255, Math.round((argb & 0xFF) * k));
		return (argb & 0xFF000000) | r << 16 | gr << 8 | b;
	}

	/** multiplies the alpha of an ARGB colour. */
	public static int fade(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
