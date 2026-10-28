package dev.twentytps.client.osu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** splits the ini-ish text in .osu and skin.ini files into [Sections]. */
final class OsuText {
	private final Map<String, List<String>> sections = new HashMap<>();

	OsuText(String text) {
		if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
		List<String> current = null;
		for (String raw : text.split("\r?\n")) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("//")) continue;
			if (line.startsWith("[") && line.endsWith("]")) {
				current = sections.computeIfAbsent(line.substring(1, line.length() - 1).toLowerCase(Locale.ROOT), k -> new ArrayList<>());
			} else if (current != null) {
				current.add(line);
			}
		}
	}

	List<String> lines(String section) {
		return sections.getOrDefault(section.toLowerCase(Locale.ROOT), List.of());
	}

	/** the "Key: Value" pairs in a section, with keys lowercased. */
	Map<String, String> values(String section) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : lines(section)) {
			int colon = line.indexOf(':');
			if (colon > 0) out.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
		}
		return out;
	}

	static double number(Map<String, String> values, String key, double fallback) {
		try {
			String v = values.get(key);
			return v == null ? fallback : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** reads Combo1..Combo8 from a [Colours] section as opaque ARGB. empty if there aren't any. */
	static int[] comboColours(Map<String, String> colours) {
		List<Integer> out = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			String v = colours.get("combo" + i);
			if (v == null) continue;
			String[] rgb = v.split(",");
			try {
				int r = Integer.parseInt(rgb[0].strip());
				int g = Integer.parseInt(rgb[1].strip());
				int b = Integer.parseInt(rgb[2].strip());
				out.add(0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
			} catch (RuntimeException ignored) {
			}
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}
}
