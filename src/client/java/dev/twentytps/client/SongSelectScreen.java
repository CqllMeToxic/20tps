package dev.twentytps.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * the scrollable, searchable list of every song in the {@link Library}. each song is one row.
 * click it to see its difficulties, then click a difficulty to play it.
 */
public class SongSelectScreen extends Screen {
	private static final int SET_H = 24;
	private static final int DIFF_H = 18;
	private static final int SCROLLBAR_W = 6;
	private static final String[] GRADE_ORDER = {"SS", "S", "A", "B", "C", "D"};

	private sealed interface Row permits SetRow, DiffRow {
		int height();
	}

	private record SetRow(Library.BeatmapSet set, String bestGrade) implements Row {
		public int height() {
			return SET_H;
		}
	}

	private record DiffRow(Chart chart, BestScores.Best best) implements Row {
		public int height() {
			return DIFF_H;
		}
	}

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private final Random random = new Random();
	private EditBox search;
	private List<Library.BeatmapSet> shownFrom;
	private String expandedKey;
	private String query = "";
	private String error;
	private int matchCount;
	private double scroll;
	private boolean draggingScrollbar;

	// where the list sits on screen. set in init()
	private int listX;
	private int listW;
	private int listTop;
	private int listBottom;

	public SongSelectScreen(Screen parent) {
		super(Component.literal("20tps"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Library.ensureStarted();
		listW = Math.min(440, width - 24);
		listX = (width - listW) / 2;
		listTop = 58;
		listBottom = height - 34;

		search = new EditBox(font, listX, 26, listW, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search title, artist, difficulty, mapper..."));
		search.setMaxLength(100);
		search.setValue(query);
		search.setResponder(value -> {
			query = value.strip();
			scroll = 0;
			rebuildRows();
		});
		addRenderableWidget(search);
		setInitialFocus(search);

		int bw = (listW - 16) / 5;
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Random"), b -> pickRandom()).bounds(listX, by, bw, 20).build());
		addRenderableWidget(CycleButton.onOffBuilder(Settings.autoplay)
				.create(listX + (bw + 4), by, bw, 20, Component.literal("Auto"), (b, v) -> {
					Settings.autoplay = v;
					Settings.save();
				}));
		addRenderableWidget(Button.builder(Component.literal("Folder"), b -> openChartFolder()).bounds(listX + 2 * (bw + 4), by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reload"), b -> {
			Library.reload();
			rebuildRows();
		}).bounds(listX + 3 * (bw + 4), by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(listX + 4 * (bw + 4), by, bw, 20).build());

		rebuildRows();
	}

	private void rebuildRows() {
		shownFrom = Library.sets();
		rows.clear();
		matchCount = 0;
		for (Library.BeatmapSet set : shownFrom) {
			if (!set.matches(query)) continue;
			matchCount++;
			String bestGrade = null;
			for (Chart chart : set.charts()) {
				BestScores.Best best = BestScores.get(chart.id);
				if (best != null && (bestGrade == null || rank(best.grade()) < rank(bestGrade))) bestGrade = best.grade();
			}
			rows.add(new SetRow(set, bestGrade));
			if (set.key().equals(expandedKey)) {
				for (Chart chart : set.charts()) rows.add(new DiffRow(chart, BestScores.get(chart.id)));
			}
		}
		clampScroll();
	}

	private static int rank(String grade) {
		for (int i = 0; i < GRADE_ORDER.length; i++) if (GRADE_ORDER[i].equals(grade)) return i;
		return GRADE_ORDER.length;
	}

	private int contentHeight() {
		int h = 0;
		for (Row row : rows) h += row.height();
		return h;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - (listBottom - listTop));
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
	}

	private void toggle(Library.BeatmapSet set) {
		if (set.charts().size() == 1 && set.charts().getFirst().source == null) {
			play(set.charts().getFirst());
			return;
		}
		expandedKey = set.key().equals(expandedKey) ? null : set.key();
		rebuildRows();
		scrollIntoView(set.key());
	}

	/** scrolls so an opened song and as many of its difficulties as fit are on screen. */
	private void scrollIntoView(String key) {
		int y = 0;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i) instanceof SetRow s && s.set().key().equals(key)) {
				int block = SET_H + (key.equals(expandedKey) ? s.set().charts().size() * DIFF_H : 0);
				int view = listBottom - listTop;
				if (y < scroll) scroll = y;
				else if (y + Math.min(block, view) > scroll + view) scroll = y + Math.min(block, view) - view;
				clampScroll();
				return;
			}
			y += rows.get(i).height();
		}
	}

	private void pickRandom() {
		List<Library.BeatmapSet> matching = rows.stream()
				.filter(r -> r instanceof SetRow).map(r -> ((SetRow) r).set()).toList();
		if (matching.isEmpty()) return;
		Library.BeatmapSet set = matching.get(random.nextInt(matching.size()));
		expandedKey = set.key();
		rebuildRows();
		scrollIntoView(set.key());
	}

	private void play(Chart chart) {
		try {
			chart.ensureNotes();
		} catch (IOException | RuntimeException e) {
			error = "Couldn't load " + chart.title + ": " + e.getMessage();
			TwentyTpsClient.LOGGER.warn("Couldn't load chart {}", chart.id, e);
			return;
		}
		error = null;
		minecraft.setScreen(new PlayScreen(this, chart));
	}

	private void openChartFolder() {
		try {
			Files.createDirectories(Chart.userChartDir());
			Util.getPlatform().openPath(Chart.userChartDir());
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't open chart folder: {}", e.getMessage());
		}
	}

	// ---- input ----

	private boolean inList(double x, double y) {
		return x >= listX && x < listX + listW && y >= listTop && y < listBottom;
	}

	private int scrollbarX() {
		return listX + listW - SCROLLBAR_W;
	}

	private Row rowAt(double x, double y) {
		if (!inList(x, y) || x >= scrollbarX()) return null;
		double cursor = listTop - scroll;
		for (Row row : rows) {
			if (y >= cursor && y < cursor + row.height()) return row;
			cursor += row.height();
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		if (inList(event.x(), event.y()) && event.x() >= scrollbarX() && maxScroll() > 0) {
			draggingScrollbar = true;
			dragScrollbarTo(event.y());
			return true;
		}
		Row row = rowAt(event.x(), event.y());
		if (row instanceof SetRow s) {
			toggle(s.set());
			return true;
		}
		if (row instanceof DiffRow d) {
			play(d.chart());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingScrollbar) {
			dragScrollbarTo(event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	private void dragScrollbarTo(double y) {
		double view = listBottom - listTop;
		double thumb = Math.max(20, view * view / Math.max(view, contentHeight()));
		double k = (y - listTop - thumb / 2) / (view - thumb);
		scroll = k * maxScroll();
		clampScroll();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll -= vertical * 36;
		clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				scroll += listBottom - listTop - SET_H;
				clampScroll();
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				scroll -= listBottom - listTop - SET_H;
				clampScroll();
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				// enter opens the top result, or plays its easiest difficulty if it's already open.
				for (Row row : rows) {
					if (row instanceof SetRow s) {
						if (s.set().key().equals(expandedKey)) play(s.set().charts().getFirst());
						else toggle(s.set());
						return true;
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_F2 -> {
				pickRandom();
				return true;
			}
			default -> {
				return super.keyPressed(event);
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	// ---- rendering ----

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (shownFrom != Library.sets()) rebuildRows();
		super.render(g, mouseX, mouseY, partialTick);

		g.pose().pushMatrix();
		g.pose().translate(listX, 9);
		g.pose().scale(1.6f, 1.6f);
		g.drawString(font, "20TPS", 0, 0, Theme.minecraft() ? 0xFFFFFFFF : 0xFFFF6FAE);
		g.pose().popMatrix();

		String status;
		if (Library.scanning() && !Library.phase().isEmpty()) status = Library.phase();
		else if (Library.scanning()) status = "Scanning library... " + Library.scanned() + " / " + Library.toScan();
		else if (query.isEmpty()) status = Library.sets().size() + " songs · " + Library.chartCount() + " charts";
		else status = matchCount + " of " + Library.sets().size() + " songs match";
		g.drawString(font, status, listX + listW - font.width(status), 12, Theme.subtle());

		if (Theme.minecraft()) {
			renderMenuBackground(g, listX, listTop, listW, listBottom - listTop);
		} else {
			g.fill(listX, listTop, listX + listW, listBottom, 0x60000000);
		}
		g.enableScissor(listX, listTop, listX + listW, listBottom);
		int y = listTop - (int) Math.round(scroll);
		Row hovered = rowAt(mouseX, mouseY);
		for (Row row : rows) {
			int h = row.height();
			if (y + h >= listTop && y <= listBottom) {
				if (row instanceof SetRow s) renderSet(g, s, y, row == hovered);
				else if (row instanceof DiffRow d) renderDiff(g, d, y, row == hovered);
			}
			y += h;
		}
		g.disableScissor();
		if (Theme.minecraft()) {
			boolean inWorld = minecraft.level != null;
			g.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_HEADER_SEPARATOR : HEADER_SEPARATOR, listX, listTop - 2, 0, 0, listW, 2, 32, 2);
			g.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR, listX, listBottom, 0, 0, listW, 2, 32, 2);
		}

		if (rows.isEmpty()) {
			String msg = Library.scanning() ? "Loading songs..." : query.isEmpty() ? "No songs found. Add some with Charts folder." : "No songs match \"" + query + "\".";
			g.drawCenteredString(font, msg, width / 2, (listTop + listBottom) / 2, 0xFF8888AA);
		}

		// scrollbar
		int max = maxScroll();
		if (max > 0) {
			int view = listBottom - listTop;
			int thumb = Math.max(20, view * view / contentHeight());
			int thumbY = listTop + (int) Math.round((view - thumb) * (scroll / max));
			g.fill(scrollbarX(), listTop, listX + listW, listBottom, 0x40000000);
			g.fill(scrollbarX() + 1, thumbY, listX + listW - 1, thumbY + thumb, draggingScrollbar ? 0xFFFFFFFF : 0xFFAAAACC);
		}

		if (error != null) g.drawCenteredString(font, error, width / 2, listBottom + 1, 0xFFFF5C5C);
	}

	private void renderSet(GuiGraphics g, SetRow row, int y, boolean hovered) {
		boolean expanded = row.set().key().equals(expandedKey);
		int right = scrollbarX() - 6;
		if (Theme.minecraft()) {
			// vanilla selection lists: a black box with a white outline (grey when you're just hovering).
			if (expanded || hovered) {
				g.fill(listX + 1, y + 1, scrollbarX() - 1, y + SET_H - 1, 0xFF000000);
				g.renderOutline(listX + 1, y + 1, scrollbarX() - listX - 2, SET_H - 2, expanded ? 0xFFFFFFFF : 0xFF808080);
			}
		} else {
			if (hovered || expanded) g.fill(listX, y, scrollbarX(), y + SET_H, expanded ? 0x50FF6FAE : 0x30FFFFFF);
			g.fill(listX, y + SET_H - 1, scrollbarX(), y + SET_H, 0x20FFFFFF);
		}

		String side = row.set().charts().size() == 1 ? "1 chart" : row.set().charts().size() + " charts";
		int sideW = font.width(side);
		int room = right - listX - 10 - sideW - 26;
		g.drawString(font, ellipsize(row.set().title(), room), listX + 6, y + 3, 0xFFFFFFFF);
		g.drawString(font, ellipsize(row.set().artist(), room), listX + 6, y + 13, Theme.minecraft() ? Theme.MC_GRAY : 0xFF9999BB);
		g.drawString(font, side, right - sideW, y + 8, Theme.minecraft() ? Theme.MC_GRAY : 0xFF9999BB);
		if (row.bestGrade() != null) {
			g.drawString(font, row.bestGrade(), right - sideW - 8 - font.width(row.bestGrade()), y + 8, 0xFFFFD36B);
		}
	}

	private void renderDiff(GuiGraphics g, DiffRow row, int y, boolean hovered) {
		Chart chart = row.chart();
		int right = scrollbarX() - 6;
		if (Theme.minecraft()) {
			g.fill(listX + 8, y, scrollbarX() - 1, y + DIFF_H, hovered ? 0xC0000000 : 0x80000000);
			if (hovered) g.renderOutline(listX + 8, y, scrollbarX() - listX - 9, DIFF_H, 0xFFFFFFFF);
		} else {
			g.fill(listX, y, scrollbarX(), y + DIFF_H, hovered ? 0x40FFFFFF : 0x18FFFFFF);
		}

		String stars = "★".repeat(chart.difficulty) + "☆".repeat(Math.max(0, 5 - chart.difficulty));
		int x = listX + 16;
		g.drawString(font, stars, x, y + 5, 0xFFFFD36B);
		x += font.width(stars) + 6;

		long seconds = Math.round(chart.lengthMs / 1000);
		String info = String.format("%d:%02d · %d", seconds / 60, seconds % 60, chart.noteCount);
		if (row.best() != null) info = row.best().grade() + "  " + String.format("%,d", row.best().score()) + "   " + info;
		int infoW = font.width(info);
		String name = chart.version != null ? chart.version : chart.title;
		if (chart.creator != null && !chart.creator.isEmpty()) name += "  (" + chart.creator + ")";
		g.drawString(font, ellipsize(name, right - x - infoW - 8), x, y + 5, 0xFFFFFFFF);
		g.drawString(font, info, right - infoW, y + 5, row.best() != null ? 0xFFFFD36B : Theme.minecraft() ? Theme.MC_GRAY : 0xFF9999BB);
	}

	private String ellipsize(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}
}
