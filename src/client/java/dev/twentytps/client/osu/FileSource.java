package dev.twentytps.client.osu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * files inside an .osz/.osk (which are just zips) or an extracted folder. lookups ignore case
 * and slash direction, because beatmaps made on windows are pretty loose about file names.
 */
public abstract class FileSource {
	public abstract List<String> list() throws IOException;

	/** null if there's no such file. */
	public abstract byte[] read(String name) throws IOException;

	public String describe() {
		return toString();
	}

	static String normalize(String name) {
		return name.replace('\\', '/').replaceFirst("^\\./", "").toLowerCase(Locale.ROOT).trim();
	}

	public static FileSource of(Path path) {
		return Files.isDirectory(path) ? new Folder(path) : new Zip(path);
	}

	private static final class Zip extends FileSource {
		private final Path path;

		Zip(Path path) {
			this.path = path;
		}

		@Override
		public List<String> list() throws IOException {
			List<String> names = new ArrayList<>();
			try (ZipFile zip = new ZipFile(path.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (!entry.isDirectory()) names.add(entry.getName());
				}
			}
			return names;
		}

		@Override
		public byte[] read(String name) throws IOException {
			String wanted = normalize(name);
			try (ZipFile zip = new ZipFile(path.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (!entry.isDirectory() && normalize(entry.getName()).equals(wanted)) {
						try (InputStream in = zip.getInputStream(entry)) {
							return in.readAllBytes();
						}
					}
				}
			}
			return null;
		}

		@Override
		public String toString() {
			return path.getFileName().toString();
		}
	}

	private static final class Folder extends FileSource {
		private final Path root;
		private List<String> listing;

		Folder(Path root) {
			this.root = root;
		}

		@Override
		public synchronized List<String> list() throws IOException {
			if (listing == null) {
				try (Stream<Path> files = Files.walk(root, 4)) {
					listing = files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
				}
			}
			return listing;
		}

		@Override
		public byte[] read(String name) throws IOException {
			Path direct;
			try {
				direct = root.resolve(name.replace('\\', '/'));
			} catch (java.nio.file.InvalidPathException e) {
				direct = null;
			}
			if (direct != null && Files.isRegularFile(direct)) return Files.readAllBytes(direct);
			String wanted = normalize(name);
			for (String candidate : list()) {
				if (normalize(candidate).equals(wanted)) return Files.readAllBytes(root.resolve(candidate));
			}
			return null;
		}

		@Override
		public String toString() {
			return root.getFileName().toString();
		}
	}
}
