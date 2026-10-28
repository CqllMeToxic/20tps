package dev.twentytps.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * a button that pops open a scrollable list on top of everything else. the screen using it
 * has to pass clicks and scrolls to {@link #mouseClicked} and {@link #mouseScrolled} first,
 * and call {@link #render} last.
 */
public final class Dropdown<T> {
	private static final int ROW_H = 14;
	private static final int MAX_WIDTH = 280;

	private final String label;
	private final List<T> values;
	private final Function<T, String> names;
	private final Consumer<T> onPick;
	private final Button button;
	private T selected;
	private boolean open;
	private double scroll;

	// where the list panel goes. worked out when it opens
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	public Dropdown(String label, List<T> values, T selected, Function<T, String> names, Consumer<T> onPick,
			int x, int y, int width, int height) {
		this.label = label;
		this.values = values;
		this.selected = selected;
		this.names = names;
		this.onPick = onPick;
		this.button = Button.builder(Component.empty(), b -> toggle()).bounds(x, y, width, height).build();
		updateMessage();
	}

	public AbstractWidget button() {
		return button;
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
	}

	private void updateMessage() {
		Font font = Minecraft.getInstance().font;
		String prefix = label + ": ";
		String suffix = " ▾";
		int room = button.getWidth() - 12 - font.width(prefix + suffix);
		button.setMessage(Component.literal(prefix + ellipsize(font, names.apply(selected), room) + suffix));
	}

	private void toggle() {
		if (open) {
			open = false;
			return;
		}
		Font font = Minecraft.getInstance().font;
		Minecraft mc = Minecraft.getInstance();
		int screenH = mc.getWindow().getGuiScaledHeight();
		int screenW = mc.getWindow().getGuiScaledWidth();

		int widest = 0;
		for (T value : values) widest = Math.max(widest, font.width(names.apply(value)));
		panelW = Math.max(button.getWidth(), Math.min(MAX_WIDTH, widest + 20));
		panelX = Math.min(button.getX(), screenW - panelW - 4);

		// open downward if a few rows fit, otherwise flip it upward.
		int below = screenH - (button.getY() + button.getHeight()) - 6;
		int above = button.getY() - 6;
		boolean down = below >= Math.min(values.size(), 6) * ROW_H || below >= above;
		int space = down ? below : above;
		int rows = Math.max(1, Math.min(values.size(), space / ROW_H));
		panelH = rows * ROW_H + 2;
		panelY = down ? button.getY() + button.getHeight() + 1 : button.getY() - panelH - 1;

		// start scrolled to the current pick so you can see it.
		int index = Math.max(0, values.indexOf(selected));
		scroll = Math.max(0, Math.min(maxScroll(), index * ROW_H - (panelH - ROW_H) / 2.0));
		open = true;
	}

	private int maxScroll() {
		return Math.max(0, values.size() * ROW_H - (panelH - 2));
	}

	private boolean inPanel(double x, double y) {
		return x >= panelX && x < panelX + panelW && y >= panelY && y < panelY + panelH;
	}

	/** true if we handled the click (which is any click while it's open). */
	public boolean mouseClicked(MouseButtonEvent event) {
		if (!open) return false;
		if (inPanel(event.x(), event.y())) {
			int index = (int) ((event.y() - panelY - 1 + scroll) / ROW_H);
			if (index >= 0 && index < values.size()) {
				selected = values.get(index);
				onPick.accept(selected);
				updateMessage();
				Button.playButtonClickSound(Minecraft.getInstance().getSoundManager());
			}
			open = false;
			return true;
		}
		// clicking the button again closes it. clicking anywhere else just dismisses the list.
		open = false;
		return true;
	}

	public boolean mouseScrolled(double x, double y, double vertical) {
		if (!open) return false;
		if (inPanel(x, y)) {
			scroll = Math.max(0, Math.min(maxScroll(), scroll - vertical * ROW_H * 2));
		}
		return true;
	}

	public void render(GuiGraphics g, int mouseX, int mouseY) {
		if (!open) return;
		Font font = Minecraft.getInstance().font;
		g.nextStratum(); // so it draws over the other widgets, text and all

		g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFFAAAACC);
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101020);
		g.enableScissor(panelX, panelY + 1, panelX + panelW, panelY + panelH - 1);

		boolean scrollable = maxScroll() > 0;
		int textRight = panelX + panelW - (scrollable ? 10 : 4);
		int first = (int) (scroll / ROW_H);
		for (int i = first; i < values.size(); i++) {
			int y = panelY + 1 + i * ROW_H - (int) Math.round(scroll);
			if (y > panelY + panelH) break;
			T value = values.get(i);
			boolean isSelected = value.equals(selected);
			boolean hovered = inPanel(mouseX, mouseY) && mouseY >= y && mouseY < y + ROW_H;
			if (hovered) g.fill(panelX, y, panelX + panelW, y + ROW_H, 0x40FFFFFF);
			else if (isSelected) g.fill(panelX, y, panelX + panelW, y + ROW_H, Shapes.fade(Theme.accent(), 0.3f));
			String text = ellipsize(font, names.apply(value), textRight - panelX - 16);
			if (isSelected) g.drawString(font, "✔", panelX + 4, y + 3, Theme.accent());
			g.drawString(font, text, panelX + 14, y + 3, isSelected ? 0xFFFFFFFF : 0xFFCCCCDD);
		}
		g.disableScissor();

		if (scrollable) {
			int trackX = panelX + panelW - 5;
			int thumb = Math.max(12, panelH * panelH / (values.size() * ROW_H));
			int thumbY = panelY + (int) Math.round((panelH - thumb) * (scroll / maxScroll()));
			g.fill(trackX, panelY + 1, trackX + 4, panelY + panelH - 1, 0x40000000);
			g.fill(trackX, thumbY, trackX + 4, thumbY + thumb, 0xFFAAAACC);
		}
	}

	private static String ellipsize(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}
}
