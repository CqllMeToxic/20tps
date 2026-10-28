package dev.twentytps.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.twentytps.client.osu.BeatmapImporter;
import dev.twentytps.client.osu.FileSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** a playable song: some info about it plus a list of timed targets. loaded from json. */
public final class Chart {
	private static final Gson GSON = new Gson();

	// these come straight from the json
	public String title = "Untitled";
	public String artist = "Unknown";
	public double bpm = 120;
	public double offsetMs = 0;
	public double approachMs = 1000;
	public int difficulty = 1;
	public String instrument = "pling";
	/** target radius in playfield units (the field is 512x384). */
	public double radius = 30;
	/** hit windows: how far off (in ms) a tap can be and still get each judgement. */
	public double perfectMs = 40;
	public double goodMs = 90;
	public double okMs = 140;
	public List<Note> notes = new ArrayList<>();

	// the beatmap importer fills these in. they're never read from json.
	/** where the music and background live. null for built-in charts. */
	public transient FileSource source;
	public transient String musicFile;
	public transient String backgroundFile;
	/** combo colours from the beatmap. empty means use the skin's or the default ones. */
	public transient int[] comboColors = new int[0];
	/** show combo numbers on targets instead of note names. */
	public transient boolean comboNumbers;
	/** which .osu file inside `source` this difficulty came from. */
	public transient String osuEntry;
	public transient String setTitle;
	public transient String setKey;
	public transient String version;
	public transient String creator = "";
	public transient String tags = "";
	/** we know these from the library scan, before the targets are actually loaded. */
	public transient int noteCount;
	public transient double lengthMs;

	/** set after loading, like "builtin:first_light" or "file:my_song". */
	public transient String id;

	public static final class Note {
		public double beat;
		public double x;
		public double y;
		/** exact hit time in ms. if it's set, `beat` is ignored. */
		public Double ms;
		/** note block pitch, 0 (F#3) to 24 (F#5). -1 means play the hit sound instead. */
		public int n = 12;

		public transient int combo;
		public transient int colorIndex;
	}

	public double beatMs() {
		return 60000.0 / bpm;
	}

	public double timeOf(Note note) {
		return note.ms != null ? note.ms : offsetMs + note.beat * beatMs();
	}

	public boolean hasMusic() {
		return source != null && musicFile != null && !musicFile.isBlank();
	}

	/** sorts the notes and makes up combo info for charts that don't have any. */
	public void finishLoading() {
		notes.sort(Comparator.comparingDouble(this::timeOf));
		if (comboNumbers) return;
		for (int i = 0; i < notes.size(); i++) {
			notes.get(i).colorIndex = i / 4;
			notes.get(i).combo = i % 4 + 1;
		}
	}

	public Holder<SoundEvent> sound() {
		return switch (instrument) {
			case "bell" -> SoundEvents.NOTE_BLOCK_BELL;
			case "chime" -> SoundEvents.NOTE_BLOCK_CHIME;
			case "harp" -> SoundEvents.NOTE_BLOCK_HARP;
			case "flute" -> SoundEvents.NOTE_BLOCK_FLUTE;
			case "xylophone" -> SoundEvents.NOTE_BLOCK_XYLOPHONE;
			case "bit" -> SoundEvents.NOTE_BLOCK_BIT;
			case "guitar" -> SoundEvents.NOTE_BLOCK_GUITAR;
			default -> SoundEvents.NOTE_BLOCK_PLING;
		};
	}

	public static Path userChartDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("20tps").resolve("charts");
	}

	/** the built-in charts in assets/<ns>/charts/*.json. render thread only, since it uses the resource manager. */
	public static List<Chart> loadBuiltins() {
		List<Chart> charts = new ArrayList<>();

		Map<Identifier, Resource> builtins = Minecraft.getInstance().getResourceManager()
				.listResources("charts", id -> id.getPath().endsWith(".json"));
		for (Map.Entry<Identifier, Resource> entry : builtins.entrySet()) {
			try (Reader reader = entry.getValue().openAsReader()) {
				String name = entry.getKey().getPath().replaceFirst("^charts/", "").replaceFirst("\\.json$", "");
				charts.add(parse(reader, "builtin:" + name));
			} catch (IOException | JsonParseException | IllegalArgumentException e) {
				TwentyTpsClient.LOGGER.warn("Couldn't load chart {}: {}", entry.getKey(), e.getMessage());
			}
		}
		charts.sort(Comparator.comparingInt((Chart c) -> c.difficulty).thenComparing(c -> c.title));
		return charts;
	}

	/** everything in a charts folder we know how to load (.json, .osz, .osu, beatmap folders), sorted by name. */
	public static List<Path> folderEntries(Path dir) {
		if (!Files.isDirectory(dir)) return List.of();
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> BeatmapImporter.handles(p) || p.toString().endsWith(".json")).sorted().toList();
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't read chart folder {}: {}", dir, e.getMessage());
			return List.of();
		}
	}

	/** loads one thing from a charts folder. if it breaks we log it and return nothing. fine off the render thread. */
	public static List<Chart> loadEntry(Path file) {
		try {
			if (BeatmapImporter.handles(file)) return BeatmapImporter.load(file);
			try (Reader reader = Files.newBufferedReader(file)) {
				String name = file.getFileName().toString().replaceFirst("\\.json$", "");
				return List.of(parse(reader, "file:" + name));
			}
		} catch (IOException | JsonParseException | IllegalArgumentException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't load chart {}: {}", file, e.getMessage());
			return List.of();
		}
	}

	/** imported charts get scanned without their targets to save time. this loads them right before you play. */
	public void ensureNotes() throws IOException {
		if (notes.isEmpty() && source != null && osuEntry != null) {
			notes = BeatmapImporter.loadNotes(this);
		}
	}

	/** what groups the difficulties of one song together in the song list. */
	public String setKey() {
		return setKey != null ? setKey : id;
	}

	public String setTitle() {
		return setTitle != null ? setTitle : title;
	}

	private static Chart parse(Reader reader, String id) {
		Chart chart = GSON.fromJson(reader, Chart.class);
		if (chart == null || chart.notes == null || chart.notes.isEmpty()) {
			throw new IllegalArgumentException("chart has no notes");
		}
		if (chart.bpm <= 0) {
			throw new IllegalArgumentException("bpm must be positive");
		}
		chart.approachMs = Math.max(300, chart.approachMs);
		chart.id = id;
		chart.finishLoading();
		chart.noteCount = chart.notes.size();
		chart.lengthMs = chart.timeOf(chart.notes.getLast()) - chart.timeOf(chart.notes.getFirst());
		return chart;
	}
}
