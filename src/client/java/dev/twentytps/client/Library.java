package dev.twentytps.client;

import dev.twentytps.client.osu.Lazer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * every playable chart: built-ins, config/20tps/charts, and optionally your osu! Songs folder
 * and osu!lazer. scanning hundreds of maps takes a few seconds, so it happens on a background
 * thread and the song list fills in as it goes.
 */
public final class Library {
	/** all the difficulties of one song. shows up as one row in the song list. */
	public record BeatmapSet(String key, String title, String artist, List<Chart> charts) {
		public boolean matches(String query) {
			if (query.isEmpty()) return true;
			String haystack = (title + " " + artist + " " + String.join(" ", charts.stream()
					.map(c -> c.version + " " + c.creator + " " + c.tags).toList())).toLowerCase(Locale.ROOT);
			for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
				if (!haystack.contains(word)) return false;
			}
			return true;
		}
	}

	private static volatile List<BeatmapSet> sets = List.of();
	private static volatile boolean scanning;
	private static volatile int scanned;
	private static volatile int toScan;
	private static volatile String phase = "";
	private static int generation;
	private static boolean started;

	private Library() {}

	public static List<BeatmapSet> sets() {
		return sets;
	}

	public static boolean scanning() {
		return scanning;
	}

	public static int scanned() {
		return scanned;
	}

	public static int toScan() {
		return toScan;
	}

	/** what the scan is up to right now, for status text. */
	public static String phase() {
		return phase;
	}

	public static int chartCount() {
		int n = 0;
		for (BeatmapSet set : sets) n += set.charts().size();
		return n;
	}

	public static Path osuSongsFolder() {
		return Settings.osuFolder.isBlank() ? null : Path.of(Settings.osuFolder, "Songs");
	}

	/** kicks off the first scan if it hasn't happened yet. render thread only. */
	public static void ensureStarted() {
		if (!started) reload();
	}

	/** rescans everything. render thread only. */
	public static void reload() {
		started = true;
		int gen = ++generation;
		List<Chart> builtins = Chart.loadBuiltins();

		// order matters for duplicates. whichever copy of a difficulty shows up first wins.
		List<Path> userEntries = Chart.folderEntries(Chart.userChartDir());
		List<Path> stableEntries = new ArrayList<>();
		Path songs = osuSongsFolder();
		if (Settings.useOsuFolder && songs != null && Files.isDirectory(songs)) stableEntries.addAll(Chart.folderEntries(songs));
		List<Path> entries = new ArrayList<>(userEntries);
		entries.addAll(stableEntries);

		sets = group(builtins, List.of());
		scanned = 0;
		toScan = entries.size();
		scanning = true;

		Thread thread = new Thread(() -> {
			List<Chart> imported = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			long lastPublish = System.nanoTime();
			int index = 0;
			for (Path entry : entries) {
				if (gen != generation) return; // a newer scan took over, bail
				if (index++ == userEntries.size()) {
					// lazer goes after the charts folder but before osu! stable.
					phase = "Reading osu!lazer...";
					Lazer.refresh();
					addNew(imported, seen, Lazer.charts());
					phase = "";
				}
				addNew(imported, seen, Chart.loadEntry(entry));
				scanned++;
				if (System.nanoTime() - lastPublish > 250_000_000L) {
					sets = group(builtins, imported);
					lastPublish = System.nanoTime();
				}
			}
			if (gen != generation) return;
			if (index <= userEntries.size()) {
				phase = "Reading osu!lazer...";
				Lazer.refresh();
				addNew(imported, seen, Lazer.charts());
				phase = "";
			}
			sets = group(builtins, imported);
			scanning = false;
			TwentyTpsClient.LOGGER.info("20tps library: {} songs, {} charts", sets.size(), chartCount());
		}, "20tps library scan");
		thread.setDaemon(true);
		thread.start();
	}

	/** adds charts we haven't seen yet. the same difficulty is often in both osu! and lazer. */
	private static void addNew(List<Chart> into, Set<String> seen, List<Chart> charts) {
		for (Chart chart : charts) {
			String key = (chart.setTitle() + "|" + chart.artist + "|" + chart.version + "|" + chart.creator + "|" + chart.noteCount)
					.toLowerCase(Locale.ROOT);
			if (seen.add(key)) into.add(chart);
		}
	}

	private static List<BeatmapSet> group(List<Chart> builtins, List<Chart> imported) {
		List<BeatmapSet> out = new ArrayList<>(groupInOrder(builtins));
		List<BeatmapSet> rest = groupInOrder(new ArrayList<>(imported));
		rest.sort(Comparator.comparing((BeatmapSet s) -> s.title().toLowerCase(Locale.ROOT)).thenComparing(s -> s.artist().toLowerCase(Locale.ROOT)));
		out.addAll(rest);
		return List.copyOf(out);
	}

	private static List<BeatmapSet> groupInOrder(List<Chart> charts) {
		Map<String, List<Chart>> byKey = new LinkedHashMap<>();
		for (Chart chart : charts) byKey.computeIfAbsent(chart.setKey(), k -> new ArrayList<>()).add(chart);
		List<BeatmapSet> out = new ArrayList<>();
		for (Map.Entry<String, List<Chart>> e : byKey.entrySet()) {
			Chart first = e.getValue().getFirst();
			out.add(new BeatmapSet(e.getKey(), first.setTitle(), first.artist, List.copyOf(e.getValue())));
		}
		return out;
	}
}
