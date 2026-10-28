package dev.twentytps.client.osu;

import dev.twentytps.client.Settings;
import dev.twentytps.client.Textures;
import dev.twentytps.client.Textures.Tex;
import dev.twentytps.client.TwentyTpsClient;
import dev.twentytps.client.audio.AlClip;
import dev.twentytps.client.audio.AudioDecoder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * graphics and hit sound from an .osk skin (or an extracted skin folder). anything a skin
 * doesn't have falls back to what 20tps draws itself.
 */
public final class Skin {
	public final String name;
	public Tex hitCircle;
	public Tex hitCircleOverlay;
	public Tex approachCircle;
	public Tex cursor;
	public Tex cursorTrail;
	public final Tex[] digits = new Tex[10];
	/** judgement images, in PERFECT, GOOD, OK, MISS order. */
	public final Tex[] judgements = new Tex[4];
	public AlClip hitSound;
	public int[] comboColors = new int[0];
	public int digitOverlap = -2;

	private static final String OSU_PREFIX = "osu:";
	private static final String LAZER_PREFIX = "lazer:";
	private static Skin loaded;

	private Skin(String name) {
		this.name = name;
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("20tps").resolve("skins");
	}

	/** osu!'s Skins folder, if reading it is turned on and it actually exists. */
	public static Path osuFolder() {
		if (!Settings.useOsuFolder || Settings.osuFolder.isBlank()) return null;
		Path dir = Path.of(Settings.osuFolder, "Skins");
		return Files.isDirectory(dir) ? dir : null;
	}

	/**
	 * ids of installed skins: names from config/20tps/skins (minus the .osk), then "osu:" ones
	 * from the osu! install, then "lazer:" ones from osu!lazer.
	 */
	public static List<String> available() {
		List<String> names = new ArrayList<>();
		try {
			Files.createDirectories(folder());
			list(folder(), "", names);
			if (osuFolder() != null) list(osuFolder(), OSU_PREFIX, names);
			for (Lazer.Entry skin : Lazer.skinEntries()) names.add(LAZER_PREFIX + skin.id);
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't list skins: {}", e.getMessage());
		}
		return names;
	}

	private static void list(Path dir, String prefix, List<String> out) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			for (Path p : files.sorted().toList()) {
				String file = p.getFileName().toString();
				if (Files.isDirectory(p)) out.add(prefix + file);
				else if (file.toLowerCase(Locale.ROOT).endsWith(".osk")) out.add(prefix + file.substring(0, file.length() - 4));
			}
		}
	}

	/** a skin id the way the player sees it. */
	public static String displayName(String id) {
		if (id.isEmpty()) return "Default";
		if (id.startsWith(LAZER_PREFIX)) {
			Lazer.Entry skin = Lazer.skin(id.substring(LAZER_PREFIX.length()));
			return (skin == null || skin.name == null ? "Unknown" : skin.name.strip()) + " (lazer)";
		}
		return id.startsWith(OSU_PREFIX) ? id.substring(OSU_PREFIX.length()).strip() + " (osu!)" : id.strip();
	}

	/** the selected skin, loading it if it isn't already. null means the built-in look. render thread only. */
	public static Skin current() {
		String wanted = Settings.skin;
		if (loaded != null && loaded.name.equals(wanted)) return loaded;
		if (loaded != null) {
			loaded.release();
			loaded = null;
		}
		if (wanted.isEmpty()) return null;
		if (wanted.startsWith(LAZER_PREFIX)) {
			Lazer.Entry entry = Lazer.skin(wanted.substring(LAZER_PREFIX.length()));
			if (entry == null) return null;
			try {
				loaded = load(wanted, Lazer.skinSource(entry));
			} catch (IOException | RuntimeException e) {
				TwentyTpsClient.LOGGER.warn("Couldn't load lazer skin {}: {}", entry.name, e.toString());
			}
			return loaded;
		}

		Path base = folder();
		String name = wanted;
		if (wanted.startsWith(OSU_PREFIX)) {
			base = osuFolder();
			name = wanted.substring(OSU_PREFIX.length());
			if (base == null) return null;
		}
		Path dir = base.resolve(name);
		Path osk = base.resolve(name + ".osk");
		Path path = Files.isDirectory(dir) ? dir : osk;
		if (!Files.exists(path) && !wanted.startsWith(OSU_PREFIX) && osuFolder() != null && Files.isDirectory(osuFolder().resolve(name))) {
			// a skin that's gone from config/20tps/skins but the osu! install still has.
			path = osuFolder().resolve(name);
		}
		if (!Files.exists(path)) {
			TwentyTpsClient.LOGGER.warn("Skin {} not found", wanted);
			return null;
		}
		try {
			loaded = load(wanted, FileSource.of(path));
		} catch (IOException | RuntimeException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't load skin {}: {}", wanted, e.toString());
		}
		return loaded;
	}

	private static Skin load(String name, FileSource files) throws IOException {
		Skin skin = new Skin(name);
		String digitPrefix = "default";

		byte[] ini = files.read("skin.ini");
		if (ini != null) {
			OsuText text = new OsuText(new String(ini, StandardCharsets.UTF_8));
			skin.comboColors = OsuText.comboColours(text.values("Colours"));
			Map<String, String> fonts = text.values("Fonts");
			digitPrefix = fonts.getOrDefault("hitcircleprefix", digitPrefix);
			skin.digitOverlap = (int) OsuText.number(fonts, "hitcircleoverlap", skin.digitOverlap);
		}

		skin.hitCircle = image(files, "hitcircle");
		skin.hitCircleOverlay = image(files, "hitcircleoverlay");
		skin.approachCircle = image(files, "approachcircle");
		skin.cursor = image(files, "cursor");
		skin.cursorTrail = image(files, "cursortrail");
		for (int i = 0; i < 10; i++) skin.digits[i] = image(files, digitPrefix + "-" + i);
		String[] judgementNames = {"hit300", "hit100", "hit50", "hit0"};
		for (int i = 0; i < 4; i++) skin.judgements[i] = image(files, judgementNames[i]);

		for (String sound : new String[]{"normal-hitnormal.wav", "normal-hitnormal.ogg", "normal-hitnormal.mp3"}) {
			byte[] bytes = files.read(sound);
			if (bytes == null || bytes.length < 64) continue; // some skins mute a sound by shipping an empty file
			try {
				skin.hitSound = new AlClip(AudioDecoder.decode(bytes));
				break;
			} catch (IOException e) {
				TwentyTpsClient.LOGGER.warn("Skin {}: couldn't decode {}: {}", name, sound, e.getMessage());
			}
		}
		return skin;
	}

	/** tries name@2x.png, then name.png, then the first animation frame (name-0). */
	private static Tex image(FileSource files, String name) throws IOException {
		for (String base : new String[]{name, name + "-0"}) {
			byte[] hd = files.read(base + "@2x.png");
			if (hd != null) {
				Tex tex = Textures.upload(Textures.decode(hd), 2);
				if (tex != null) return tex;
			}
			byte[] sd = files.read(base + ".png");
			if (sd != null) {
				Tex tex = Textures.upload(Textures.decode(sd), 1);
				if (tex != null) return tex;
			}
		}
		return null;
	}

	private void release() {
		for (Tex tex : new Tex[]{hitCircle, hitCircleOverlay, approachCircle, cursor, cursorTrail}) if (tex != null) tex.release();
		for (Tex tex : digits) if (tex != null) tex.release();
		for (Tex tex : judgements) if (tex != null) tex.release();
		if (hitSound != null) hitSound.close();
	}
}
