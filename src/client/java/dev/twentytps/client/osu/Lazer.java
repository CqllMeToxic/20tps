package dev.twentytps.client.osu;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.twentytps.client.Chart;
import dev.twentytps.client.Settings;
import dev.twentytps.client.TwentyTpsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * reads beatmaps and skins from osu!lazer. lazer stores every file under files/ named by its
 * hash, and only its realm database (client.realm) knows which file is which. so a tiny .NET
 * helper bundled with the mod reads a copy of that database (using the realm library from the
 * lazer install) and writes out a json index of file names to hashes. the files themselves are
 * read in place, nothing gets copied.
 */
public final class Lazer {
	private static final Gson GSON = new Gson();
	private static final String[] HELPER_FILES = {"LazerIndex.dll", "LazerIndex.runtimeconfig.json", "LazerIndex.deps.json"};

	/** what the helper's json output looks like. */
	static final class Index {
		List<Entry> beatmapSets = new ArrayList<>();
		List<Entry> skins = new ArrayList<>();
	}

	static final class Entry {
		String id;
		String name;
		Map<String, String> files = new LinkedHashMap<>();
	}

	private static Index index;
	private static String status = "";

	private Lazer() {}

	/** a readable summary of the last refresh, shown in settings. */
	public static String status() {
		return status;
	}

	private static Path workDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("20tps").resolve("lazer");
	}

	/** lazer's data folder. follows storage.ini if the player moved it somewhere else. */
	public static Path dataFolder() {
		if (Settings.lazerFolder.isBlank()) return null;
		Path base = Path.of(Settings.lazerFolder);
		Path ini = base.resolve("storage.ini");
		if (Files.isRegularFile(ini)) {
			try {
				for (String line : Files.readAllLines(ini)) {
					int eq = line.indexOf('=');
					if (eq > 0 && line.substring(0, eq).strip().equalsIgnoreCase("FullPath")) {
						Path moved = Path.of(line.substring(eq + 1).strip());
						if (Files.isDirectory(moved)) return moved;
					}
				}
			} catch (IOException | RuntimeException ignored) {
			}
		}
		return base;
	}

	/** where lazer itself is installed. we borrow its realm library from there. */
	private static Path installFolder() {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null) return null;
		Path current = Path.of(local, "osulazer", "current");
		return Files.isDirectory(current) ? current : null;
	}

	public static boolean available() {
		Path data = dataFolder();
		return Settings.useLazer && data != null && Files.isRegularFile(data.resolve("client.realm"));
	}

	/**
	 * re-reads lazer's database if it changed since we last indexed it. this is slow (it runs a
	 * whole process) so keep it off the render thread. if anything goes wrong we keep the old index.
	 */
	public static synchronized void refresh() {
		status = "";
		if (!available()) {
			index = null;
			status = Settings.useLazer ? "osu!lazer data not found." : "";
			return;
		}
		Path realm = dataFolder().resolve("client.realm");
		Path work = workDir();
		Path copy = work.resolve("client.realm");
		Path out = work.resolve("index.json");
		try {
			Files.createDirectories(work);
			FileTime changed = Files.getLastModifiedTime(realm);
			boolean stale = !Files.exists(out) || Files.getLastModifiedTime(out).compareTo(changed) < 0;
			if (stale) {
				Path install = installFolder();
				if (install == null) throw new IOException("osu!lazer install not found");
				// work on a copy so nothing but lazer ever opens lazer's actual database.
				Files.copy(realm, copy, StandardCopyOption.REPLACE_EXISTING);
				runHelper(install, copy, out);
			}
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't index osu!lazer: {}", e.getMessage());
			status = "Couldn't read osu!lazer: " + e.getMessage();
		}
		index = readIndex(out);
		if (index != null && status.isEmpty()) {
			status = "osu!lazer: " + index.beatmapSets.size() + " beatmap sets, " + skinEntries().size() + " skins.";
		}
	}

	private static void runHelper(Path install, Path realm, Path out) throws IOException {
		Path helper = workDir().resolve("helper");
		Files.createDirectories(helper);
		for (String name : HELPER_FILES) {
			try (InputStream in = Lazer.class.getResourceAsStream("/lazer-index/" + name)) {
				if (in == null) throw new IOException("helper missing from mod jar (" + name + ")");
				Files.copy(in, helper.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		ProcessBuilder pb = new ProcessBuilder("dotnet", helper.resolve("LazerIndex.dll").toString(),
				install.toString(), realm.toString(), out.toString());
		pb.redirectErrorStream(true);
		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			throw new IOException("needs the .NET runtime (8 or newer) installed");
		}
		try {
			byte[] output = process.getInputStream().readAllBytes();
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("index helper timed out");
			}
			String text = new String(output, StandardCharsets.UTF_8).strip();
			if (process.exitValue() != 0) {
				String last = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("exit code " + process.exitValue());
				throw new IOException(last);
			}
			TwentyTpsClient.LOGGER.info("osu!lazer index: {}", text);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	private static Index readIndex(Path out) {
		if (!Files.exists(out)) return null;
		try (Reader reader = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, Index.class);
		} catch (IOException | JsonParseException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't read osu!lazer index: {}", e.getMessage());
			return null;
		}
	}

	private static Index index() {
		if (index == null && available()) index = readIndex(workDir().resolve("index.json"));
		return index;
	}

	private static FileSource source(Entry entry, String label) {
		return new HashedSource(dataFolder().resolve("files"), entry.files, label);
	}

	/** every difficulty in lazer's library (metadata only). */
	public static List<Chart> charts() {
		Index idx = index();
		if (idx == null || !available()) return List.of();
		List<Chart> out = new ArrayList<>();
		for (Entry set : idx.beatmapSets) {
			FileSource source = source(set, "lazer:" + set.id);
			for (Map.Entry<String, String> file : set.files.entrySet()) {
				if (!file.getKey().toLowerCase(Locale.ROOT).endsWith(".osu")) continue;
				try {
					Chart chart = BeatmapImporter.loadOne(source, file.getKey(), "lazer:" + file.getValue());
					if (chart != null) out.add(chart);
				} catch (IOException | RuntimeException e) {
					TwentyTpsClient.LOGGER.warn("Skipping lazer beatmap {}: {}", file.getKey(), e.toString());
				}
			}
		}
		return out;
	}

	/** lazer skins that have files, one per name (lazer can end up with the same skin imported twice). */
	static List<Entry> skinEntries() {
		Index idx = index();
		if (idx == null) return List.of();
		Map<String, Entry> byName = new LinkedHashMap<>();
		for (Entry skin : idx.skins) {
			String key = skin.name == null ? skin.id : skin.name.strip();
			Entry existing = byName.get(key);
			if (existing == null || skin.files.size() > existing.files.size()) byName.put(key, skin);
		}
		return new ArrayList<>(byName.values());
	}

	static Entry skin(String id) {
		for (Entry skin : skinEntries()) if (skin.id.equals(id)) return skin;
		return null;
	}

	static FileSource skinSource(Entry skin) {
		return source(skin, "lazer skin " + skin.name);
	}

	/** files from lazer's hashed storage, looked up by their original names. */
	private static final class HashedSource extends FileSource {
		private final Path filesRoot;
		private final Map<String, String> byName = new LinkedHashMap<>();
		private final List<String> names;
		private final String label;

		HashedSource(Path filesRoot, Map<String, String> files, String label) {
			this.filesRoot = filesRoot;
			this.label = label;
			this.names = List.copyOf(files.keySet());
			for (Map.Entry<String, String> e : files.entrySet()) byName.put(normalize(e.getKey()), e.getValue());
		}

		@Override
		public List<String> list() {
			return names;
		}

		@Override
		public byte[] read(String name) throws IOException {
			String hash = byName.get(normalize(name));
			if (hash == null || hash.length() < 3) return null;
			Path file = filesRoot.resolve(hash.substring(0, 1)).resolve(hash.substring(0, 2)).resolve(hash);
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
