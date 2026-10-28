package dev.twentytps.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** personal bests per chart. they live in config/20tps/best.properties as "score;accuracy;grade". */
public final class BestScores {
	public record Best(long score, double accuracy, String grade) {}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("20tps").resolve("best.properties");
	private static Properties props;

	private BestScores() {}

	private static Properties props() {
		if (props == null) {
			props = new Properties();
			if (Files.exists(FILE)) {
				try (Reader reader = Files.newBufferedReader(FILE)) {
					props.load(reader);
				} catch (IOException e) {
					TwentyTpsClient.LOGGER.warn("Couldn't read best scores: {}", e.getMessage());
				}
			}
		}
		return props;
	}

	public static Best get(String chartId) {
		String raw = props().getProperty(chartId);
		if (raw == null) return null;
		String[] parts = raw.split(";");
		try {
			return new Best(Long.parseLong(parts[0]), Double.parseDouble(parts[1]), parts[2]);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** saves the result if it's a new best, and returns true when it was. */
	public static boolean submit(String chartId, long score, double accuracy, String grade) {
		Best old = get(chartId);
		if (old != null && old.score() >= score) return false;
		props().setProperty(chartId, score + ";" + accuracy + ";" + grade);
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				props().store(writer, "20tps personal bests");
			}
		} catch (IOException e) {
			TwentyTpsClient.LOGGER.warn("Couldn't save best scores: {}", e.getMessage());
		}
		return true;
	}
}
