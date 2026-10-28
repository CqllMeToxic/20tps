package dev.twentytps.client.osu;

import dev.twentytps.client.Chart;
import dev.twentytps.client.TwentyTpsClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * turns .osz archives, beatmap folders (like the ones in osu!'s Songs folder) and loose .osu
 * files into charts. only standard mode maps get imported. 20tps only has tap targets, so
 * sliders become a single target at their start and spinners get skipped.
 *
 * library scans only read the metadata. the actual targets get parsed when you play the chart
 * (see {@link Chart#ensureNotes()}).
 */
public final class BeatmapImporter {
	private BeatmapImporter() {}

	/** whether we can load this: .osz archives, loose .osu files, and beatmap folders. */
	public static boolean handles(Path path) {
		if (Files.isDirectory(path)) return true;
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".osz") || name.endsWith(".osu");
	}

	/** metadata for every difficulty in a beatmap archive, folder or .osu file. */
	public static List<Chart> load(Path path) throws IOException {
		String fileName = path.getFileName().toString();
		List<Chart> charts = new ArrayList<>();

		if (fileName.toLowerCase(Locale.ROOT).endsWith(".osu")) {
			add(charts, Files.readAllBytes(path), FileSource.of(path.getParent()), fileName, "osu:" + path);
			return charts;
		}

		FileSource source = FileSource.of(path);
		if (Files.isDirectory(path)) {
			// read .osu files directly instead of going through the source, so we don't walk huge folders.
			try (Stream<Path> files = Files.list(path)) {
				for (Path file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".osu")).toList()) {
					add(charts, Files.readAllBytes(file), source, file.getFileName().toString(), "dir:" + file);
				}
			}
		} else {
			for (String entry : source.list()) {
				if (!entry.toLowerCase(Locale.ROOT).endsWith(".osu")) continue;
				byte[] bytes = source.read(entry);
				if (bytes != null) add(charts, bytes, source, entry, "osz:" + fileName + "/" + entry);
			}
		}
		charts.sort(Comparator.comparingInt((Chart c) -> c.difficulty)
				.thenComparing(Comparator.comparingDouble((Chart c) -> c.approachMs).reversed())
				.thenComparingInt(c -> c.noteCount));
		return charts;
	}

	/** metadata for one .osu file in a source. null if it's not a playable standard map. */
	public static Chart loadOne(FileSource source, String entry, String id) throws IOException {
		byte[] bytes = source.read(entry);
		if (bytes == null) return null;
		Chart chart = parse(new String(bytes, StandardCharsets.UTF_8), source, id, false);
		if (chart != null) chart.osuEntry = entry;
		return chart;
	}

	private static void add(List<Chart> out, byte[] bytes, FileSource source, String entry, String id) {
		try {
			Chart chart = parse(new String(bytes, StandardCharsets.UTF_8), source, id, false);
			if (chart != null) {
				chart.osuEntry = entry;
				out.add(chart);
			}
		} catch (RuntimeException e) {
			TwentyTpsClient.LOGGER.warn("Skipping {} in {}: {}", entry, source, e.toString());
		}
	}

	/** parses the targets of a chart we already scanned. */
	public static List<Chart.Note> loadNotes(Chart chart) throws IOException {
		byte[] bytes = chart.source.read(chart.osuEntry);
		if (bytes == null) throw new IOException("Missing " + chart.osuEntry);
		Chart full = parse(new String(bytes, StandardCharsets.UTF_8), chart.source, chart.id, true);
		if (full == null) throw new IOException("No playable objects in " + chart.osuEntry);
		return full.notes;
	}

	/** returns null for other game modes or maps with nothing to hit. */
	static Chart parse(String text, FileSource source, String id, boolean withNotes) {
		OsuText osu = new OsuText(text);
		Map<String, String> general = osu.values("General");
		if ((int) OsuText.number(general, "mode", 0) != 0) return null;

		Map<String, String> meta = osu.values("Metadata");
		Map<String, String> diff = osu.values("Difficulty");
		double od = OsuText.number(diff, "overalldifficulty", 5);
		double ar = OsuText.number(diff, "approachrate", od); // really old maps don't have AR. back then it was just OD
		double cs = OsuText.number(diff, "circlesize", 4);

		Chart chart = new Chart();
		chart.id = id;
		chart.source = source;
		chart.setTitle = meta.getOrDefault("title", "Untitled");
		chart.version = meta.getOrDefault("version", "?");
		chart.creator = meta.getOrDefault("creator", "");
		chart.tags = meta.getOrDefault("tags", "");
		chart.title = chart.setTitle + " [" + chart.version + "]";
		chart.artist = meta.getOrDefault("artist", "Unknown");
		// grouped by song instead of by file, so copies from osu! and lazer end up as one entry.
		chart.setKey = ("set:" + chart.setTitle + "|" + chart.artist).toLowerCase(Locale.ROOT);
		chart.musicFile = general.get("audiofilename");
		chart.approachMs = ar < 5 ? 1200 + 600 * (5 - ar) / 5 : 1200 - 750 * (ar - 5) / 5;
		chart.radius = 54.4 - 4.48 * cs;
		chart.perfectMs = 80 - 6 * od;
		chart.goodMs = 140 - 8 * od;
		chart.okMs = 200 - 10 * od;
		chart.comboNumbers = true;
		chart.comboColors = OsuText.comboColours(osu.values("Colours"));
		chart.bpm = firstBpm(osu.lines("TimingPoints"));
		chart.backgroundFile = background(osu.lines("Events"));

		int colour = -1;
		int combo = 0;
		int count = 0;
		double first = Double.NaN;
		double last = 0;
		for (String line : osu.lines("HitObjects")) {
			String[] parts = line.split(",", 6);
			if (parts.length < 4) continue;
			double x;
			double y;
			double time;
			int type;
			try {
				x = Double.parseDouble(parts[0]);
				y = Double.parseDouble(parts[1]);
				time = Double.parseDouble(parts[2]);
				type = Integer.parseInt(parts[3].strip());
			} catch (NumberFormatException e) {
				continue;
			}
			boolean newCombo = (type & 4) != 0 || colour < 0;
			if (newCombo) {
				colour += 1 + ((type >> 4) & 7);
				combo = 0;
			}
			if ((type & 8) != 0) continue; // spinners don't translate, skip them

			count++;
			if (Double.isNaN(first)) first = time;
			last = time;
			if (!withNotes) continue;

			Chart.Note note = new Chart.Note();
			note.x = x;
			note.y = y;
			note.ms = time;
			note.n = -1; // no melody note here, play the hit sound instead
			note.combo = ++combo;
			note.colorIndex = colour;
			chart.notes.add(note);
		}
		if (count == 0) return null;
		chart.noteCount = count;
		chart.lengthMs = last - first;
		if (withNotes) chart.finishLoading();

		double seconds = Math.max(1, chart.lengthMs / 1000.0);
		double perSecond = count / seconds;
		chart.difficulty = (int) Math.max(1, Math.min(5, Math.round(perSecond / 1.4 + (ar - 5) / 3)));
		return chart;
	}

	private static double firstBpm(List<String> timingPoints) {
		for (String line : timingPoints) {
			String[] parts = line.split(",");
			if (parts.length < 2) continue;
			boolean uninherited = parts.length < 7 || parts[6].strip().equals("1");
			try {
				double beatLength = Double.parseDouble(parts[1]);
				if (uninherited && beatLength > 0) return 60000.0 / beatLength;
			} catch (NumberFormatException ignored) {
			}
		}
		return 120;
	}

	private static String background(List<String> events) {
		for (String line : events) {
			String[] parts = line.split(",");
			if (parts.length >= 3 && (parts[0].equals("0") || parts[0].equalsIgnoreCase("Background"))) {
				return parts[2].strip().replace("\"", "");
			}
		}
		return null;
	}
}
