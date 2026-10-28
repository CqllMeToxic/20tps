package dev.twentytps.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** player preferences. saved in config/20tps/settings.properties. */
public final class Settings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("20tps").resolve("settings.properties");

	// gameplay
	/** added to every target's hit time. raise it if you keep hitting late. */
	public static int audioOffsetMs = 0;
	/** how fast the rings close. 2.0 means half the approach time. */
	public static double approachSpeed = 1.0;
	public static double targetSize = 1.0;
	/** plays the whole song faster or slower (the music's pitch changes too). */
	public static double songSpeed = 1.0;
	/** scales all the hit windows. above 1 is more forgiving. */
	public static double timingLeniency = 1.0;
	public static boolean noFail = false;
	/** whether mouse buttons hit targets too, on top of the tap keys. */
	public static boolean mouseTaps = true;
	/**
	 * stretch the whole window's cursor range over the playfield, so your entire tablet area (or
	 * mouse range) covers just the targets. handy when the playfield is a box in the middle.
	 */
	public static boolean mapToPlayfield = false;
	/**
	 * multiplies cursor movement while playing. at exactly 1 we just use the system cursor as-is
	 * (which is what absolute tablets need). anything else hides the pointer and moves our own
	 * cursor by the scaled movement instead.
	 */
	public static double cursorSensitivity = 1.0;
	/** a bot plays: the cursor glides between targets and hits every one exactly on time. */
	public static boolean autoplay = false;
	/** spread targets across the whole screen instead of a 4:3 box in the middle. */
	public static boolean fillScreen = true;

	// audio
	public static double musicVolume = 0.8;
	public static double hitVolume = 1.0;
	public static double metronomeVolume = 0.6;

	// visual
	/** the built-in look: minecraft-styled (the default) or the original "classic" one. */
	public static boolean minecraftLook = true;
	/** the selected skin. empty means the built-in look. see {@link dev.twentytps.client.osu.Skin}. */
	public static String skin = "";
	public static double backgroundDim = 0.7;
	public static double cursorSize = 1.0;
	/** how long the cursor trail sticks around, in ms. 0 turns it off. */
	public static double cursorTrailMs = 160;
	public static boolean showNoteNames = true;
	public static boolean showJudgements = true;
	/**
	 * how hits get labelled: "numbers" (300 / 100 / 50 / ✕), "words" (PERFECT / GOOD / OK / MISS),
	 * or "skin" to use the skin's hit images when it has them.
	 */
	public static String judgementStyle = "numbers";
	public static boolean hitErrorBar = true;
	/** use a beatmap's own combo colours over the skin's. */
	public static boolean beatmapColours = true;

	// library
	/** also read beatmaps and skins straight from an osu! install. */
	public static boolean useOsuFolder = true;
	public static String osuFolder = defaultOsuFolder();
	/** also read beatmaps and skins from osu!lazer's data folder. */
	public static boolean useLazer = true;
	public static String lazerFolder = defaultLazerFolder();
	/** show the 20tps menu instead of minecraft's title screen. */
	public static boolean replaceTitleScreen = true;

	private Settings() {}

	private static String defaultOsuFolder() {
		String local = System.getenv("LOCALAPPDATA");
		return local == null ? "" : Path.of(local, "osu!").toString();
	}

	private static String defaultLazerFolder() {
		String roaming = System.getenv("APPDATA");
		return roaming == null ? "" : Path.of(roaming, "osu").toString();
	}

	/** scores only count toward personal bests when nothing's making the song easier. */
	public static boolean ranked() {
		return !noFail && !autoplay && songSpeed == 1.0 && timingLeniency <= 1.0;
	}

	public static void load() {
		if (!Files.exists(FILE)) return;
		Properties p = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			p.load(reader);
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't read settings: {}", e.getMessage());
			return;
		}
		audioOffsetMs = (int) number(p, "audioOffsetMs", audioOffsetMs, -300, 300);
		approachSpeed = number(p, "approachSpeed", approachSpeed, 0.5, 2.0);
		targetSize = number(p, "targetSize", targetSize, 0.6, 1.5);
		songSpeed = number(p, "songSpeed", songSpeed, 0.5, 2.0);
		timingLeniency = number(p, "timingLeniency", timingLeniency, 0.5, 2.0);
		noFail = bool(p, "noFail", noFail);
		mouseTaps = bool(p, "mouseTaps", mouseTaps);
		fillScreen = bool(p, "fillScreen", fillScreen);
		mapToPlayfield = bool(p, "mapToPlayfield", mapToPlayfield);
		cursorSensitivity = number(p, "cursorSensitivity", cursorSensitivity, 0.1, 6.0);
		autoplay = bool(p, "autoplay", autoplay);
		musicVolume = number(p, "musicVolume", musicVolume, 0, 1);
		hitVolume = number(p, "hitVolume", hitVolume, 0, 1);
		metronomeVolume = number(p, "metronomeVolume", metronomeVolume, 0, 1);
		skin = p.getProperty("skin", skin);
		minecraftLook = bool(p, "minecraftLook", minecraftLook);
		backgroundDim = number(p, "backgroundDim", backgroundDim, 0, 1);
		cursorSize = number(p, "cursorSize", cursorSize, 0.25, 3.0);
		cursorTrailMs = number(p, "cursorTrailMs", cursorTrailMs, 0, 600);
		showNoteNames = bool(p, "showNoteNames", showNoteNames);
		showJudgements = bool(p, "showJudgements", showJudgements);
		judgementStyle = p.getProperty("judgementStyle", judgementStyle);
		if (!judgementStyle.equals("words") && !judgementStyle.equals("skin")) judgementStyle = "numbers";
		hitErrorBar = bool(p, "hitErrorBar", hitErrorBar);
		beatmapColours = bool(p, "beatmapColours", beatmapColours);
		useOsuFolder = bool(p, "useOsuFolder", useOsuFolder);
		osuFolder = p.getProperty("osuFolder", osuFolder);
		useLazer = bool(p, "useLazer", useLazer);
		lazerFolder = p.getProperty("lazerFolder", lazerFolder);
		replaceTitleScreen = bool(p, "replaceTitleScreen", replaceTitleScreen);
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("audioOffsetMs", String.valueOf(audioOffsetMs));
		p.setProperty("approachSpeed", String.valueOf(approachSpeed));
		p.setProperty("targetSize", String.valueOf(targetSize));
		p.setProperty("songSpeed", String.valueOf(songSpeed));
		p.setProperty("timingLeniency", String.valueOf(timingLeniency));
		p.setProperty("noFail", String.valueOf(noFail));
		p.setProperty("mouseTaps", String.valueOf(mouseTaps));
		p.setProperty("fillScreen", String.valueOf(fillScreen));
		p.setProperty("mapToPlayfield", String.valueOf(mapToPlayfield));
		p.setProperty("cursorSensitivity", String.valueOf(cursorSensitivity));
		p.setProperty("autoplay", String.valueOf(autoplay));
		p.setProperty("musicVolume", String.valueOf(musicVolume));
		p.setProperty("hitVolume", String.valueOf(hitVolume));
		p.setProperty("metronomeVolume", String.valueOf(metronomeVolume));
		p.setProperty("skin", skin);
		p.setProperty("minecraftLook", String.valueOf(minecraftLook));
		p.setProperty("backgroundDim", String.valueOf(backgroundDim));
		p.setProperty("cursorSize", String.valueOf(cursorSize));
		p.setProperty("cursorTrailMs", String.valueOf(cursorTrailMs));
		p.setProperty("showNoteNames", String.valueOf(showNoteNames));
		p.setProperty("showJudgements", String.valueOf(showJudgements));
		p.setProperty("judgementStyle", judgementStyle);
		p.setProperty("hitErrorBar", String.valueOf(hitErrorBar));
		p.setProperty("beatmapColours", String.valueOf(beatmapColours));
		p.setProperty("useOsuFolder", String.valueOf(useOsuFolder));
		p.setProperty("osuFolder", osuFolder);
		p.setProperty("useLazer", String.valueOf(useLazer));
		p.setProperty("lazerFolder", lazerFolder);
		p.setProperty("replaceTitleScreen", String.valueOf(replaceTitleScreen));
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				p.store(writer, "20tps settings");
			}
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't save settings: {}", e.getMessage());
		}
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		return Boolean.parseBoolean(p.getProperty(key, String.valueOf(fallback)));
	}

	private static double number(Properties p, String key, double fallback, double min, double max) {
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(p.getProperty(key, String.valueOf(fallback)))));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
