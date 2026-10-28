package dev.twentytps.client;

import dev.twentytps.client.Textures.Tex;
import dev.twentytps.client.audio.AlClip;
import dev.twentytps.client.audio.AudioDecoder;
import dev.twentytps.client.osu.Skin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * the actual game. targets show up on a 512x384 playfield with a ring closing in on each one,
 * and you click (or press a tap key while hovering) right as the ring meets the target. on the
 * built-in charts every hit plays that target's note, so a clean run plays the whole melody.
 * imported beatmaps play their own music instead. timing runs off the system clock in ms and
 * stays synced to the music, so it's not stuck at minecraft's 20 ticks per second.
 */
public class PlayScreen extends Screen {
	enum Judgement {
		PERFECT("PERFECT", "300", 300, 0xFF6FB7FF, Theme.MC_BLUE, 0.04f),
		GOOD("GOOD", "100", 100, 0xFF7CFFCB, Theme.MC_GREEN, 0.02f),
		OK("OK", "50", 50, 0xFFFFD36B, Theme.MC_YELLOW, 0f),
		MISS("MISS", "✕", 0, 0xFFFF5C5C, Theme.MC_RED, -0.12f);

		final String word;
		final String number;
		final int value;
		final int classicColor;
		final int minecraftColor;
		final float health;

		Judgement(String word, String number, int value, int classicColor, int minecraftColor, float health) {
			this.word = word;
			this.number = number;
			this.value = value;
			this.classicColor = classicColor;
			this.minecraftColor = minecraftColor;
			this.health = health;
		}

		String label() {
			return Settings.judgementStyle.equals("words") ? word : number;
		}

		int color() {
			return Theme.minecraft() ? minecraftColor : classicColor;
		}
	}

	private static final double LEAD_IN_MS = 2000;
	private static final double RESULTS_DELAY_MS = 1200;
	/** if the clock and the music drift further apart than this, snap the clock back. */
	private static final double MAX_DRIFT_MS = 30;
	private static final float FIELD_W = 512;
	private static final float FIELD_H = 384;
	/** only offer a skip when the next target is at least this far past when its ring would show up. */
	private static final double SKIP_MIN_GAP_MS = 2500;
	/** a tap this early (but too early to score) is a miss. anything earlier just gets ignored. */
	private static final double EARLY_MISS_MS = 400;
	private static final double SHAKE_MS = 250;
	/** after skipping, leave this much time before the next target's ring shows up. */
	private static final double SKIP_LEAD_MS = 1000;
	private static final String[] NOTE_NAMES = {"F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E", "F"};
	private static final int[] PALETTE = {0xFFFF6FAE, 0xFF6FD0FF, 0xFFB48CFF, 0xFFFFB35C};

	private static final class Target {
		final Chart.Note note;
		/** the target shakes until this song time after a way-too-early tap. */
		double shakeUntil = Double.NEGATIVE_INFINITY;
		final double time;
		Judgement result;

		Target(Chart.Note note, double time) {
			this.note = note;
			this.time = time;
		}
	}

	private record Effect(float x, float y, Judgement judgement, double start, int color, int seed) {}

	private record TrailPoint(float x, float y, long nanos) {}

	private static final int MAX_TRAIL_POINTS = 160;
	/** how many pixels across a target is in the minecraft look. odd so there's a middle pixel. */
	private static final int TARGET_PIXELS = 13;
	private static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier XP_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
	private static final Identifier XP_PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");
	private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
	private static final int CURSOR_COLOR = 0xFFFF6FAE;

	private enum State {LOADING, PLAYING, PAUSED, FAILED, RESULTS}

	private final Screen parent;
	private final Chart chart;
	private final Skin skin;
	private final List<Target> targets = new ArrayList<>();
	private final List<Effect> effects = new ArrayList<>();
	private final Set<Integer> heldKeys = new HashSet<>();
	private final int[] counts = new int[Judgement.values().length];
	private final int[] comboColors;
	private final double lastNoteTime;
	private final double approachMs;
	private final float targetRadius;
	private final double leadInMs;
	/** song speed, as song ms per real ms. */
	private final double rate = Settings.songSpeed;
	private final double perfectMs;
	private final double goodMs;
	private final double okMs;
	private final boolean ranked = Settings.ranked();
	private final boolean auto = Settings.autoplay;
	/** where the cursor's aiming, in framebuffer pixels. from the mouse/pen (maybe remapped) or the bot. */
	private float aimX = Float.NaN;
	private float aimY = Float.NaN;
	private final double[] cursorX = new double[1];
	private final double[] cursorY = new double[1];
	/** recent hit offsets for the error bar, in ms. negative means early. */
	private final double[] recentOffsets = new double[24];
	private final Judgement[] recentJudgements = new Judgement[24];
	private int recentCount;

	private CompletableFuture<AudioDecoder.Pcm> musicLoad;
	private CompletableFuture<BufferedImage> backgroundLoad;
	private AlClip music;
	private AlClip.Track musicTrack;
	private boolean musicStarted;
	private Tex background;
	private String loadError;
	private boolean cursorHidden;
	/** use scaled movement instead of the raw system cursor (when sensitivity isn't 1). */
	private final boolean relative = Settings.cursorSensitivity != 1.0 && !Settings.autoplay;
	private final double sensitivity = Settings.cursorSensitivity;
	/** last system cursor position (screen coords) while hidden, so we can tell how far it moved. */
	private double lastRawX = Double.NaN;
	private double lastRawY = Double.NaN;
	/** recent cursor positions (gui units) for the trail, oldest first. */
	private final ArrayDeque<TrailPoint> trail = new ArrayDeque<>();

	private State state;
	private long startNanos;
	private long pausedAtNanos;
	private double frozenNow;
	private int firstOpen;
	private long score;
	private int combo;
	private int maxCombo;
	private float health = 1f;
	private long lastBeat = Long.MIN_VALUE;
	private double finishedAt = -1;
	private boolean newBest;
	private double mouseX;
	private double mouseY;

	// how the playfield maps onto real framebuffer pixels. redone every frame
	/** pixels per playfield unit, for target sizes. */
	private float scale;
	/** pixels per playfield unit, for positions. not the same as `scale` when filling the screen. */
	private float scaleX;
	private float scaleY;
	private float originX;
	private float originY;
	// where the skip button is, in gui units
	private int skipX0;
	private int skipY0;
	private int skipX1;
	private int skipY1;

	public PlayScreen(Screen parent, Chart chart) {
		super(Component.literal(chart.title));
		this.parent = parent;
		this.chart = chart;
		this.skin = Skin.current();
		for (Chart.Note note : chart.notes) {
			targets.add(new Target(note, chart.timeOf(note) + Settings.audioOffsetMs));
		}
		lastNoteTime = targets.getLast().time;
		approachMs = chart.approachMs / Settings.approachSpeed;
		leadInMs = Math.max(LEAD_IN_MS, Math.max(approachMs, approachMs - targets.getFirst().time) + 500);
		targetRadius = (float) (chart.radius * Settings.targetSize);
		perfectMs = chart.perfectMs * Settings.timingLeniency;
		goodMs = chart.goodMs * Settings.timingLeniency;
		okMs = chart.okMs * Settings.timingLeniency;

		if (chart.comboColors.length > 0 && (Settings.beatmapColours || skin == null || skin.comboColors.length == 0)) comboColors = chart.comboColors;
		else if (skin != null && skin.comboColors.length > 0) comboColors = skin.comboColors;
		else comboColors = PALETTE;

		if (chart.hasMusic()) {
			state = State.LOADING;
			musicLoad = CompletableFuture.supplyAsync(() -> {
				try {
					byte[] bytes = chart.source.read(chart.musicFile);
					if (bytes == null) throw new IOException("Missing " + chart.musicFile);
					return AudioDecoder.decode(bytes);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} else {
			state = State.PLAYING;
		}
		if (chart.source != null && chart.backgroundFile != null) {
			backgroundLoad = CompletableFuture.supplyAsync(() -> {
				try {
					return Textures.decode(chart.source.read(chart.backgroundFile));
				} catch (IOException e) {
					return null;
				}
			});
		}
		startNanos = System.nanoTime();
	}

	// ---- clock ----

	/** song time in ms. negative during the lead-in. */
	private double now() {
		if (state == State.LOADING) return -leadInMs;
		if (state != State.PLAYING) return frozenNow;
		return (System.nanoTime() - startNanos) / 1_000_000.0 * rate - leadInMs;
	}

	private void freeze(State next) {
		frozenNow = now();
		pausedAtNanos = System.nanoTime();
		state = next;
		if (musicTrack != null) {
			if (next == State.FAILED) musicTrack.stop();
			else if (next == State.PAUSED) musicTrack.pause();
		}
	}

	private void resume() {
		startNanos += System.nanoTime() - pausedAtNanos;
		state = State.PLAYING;
		if (musicTrack != null && musicStarted) musicTrack.resume();
	}

	private void restart() {
		minecraft.setScreen(new PlayScreen(parent, chart));
	}

	/** finishes up the background loading on the render thread. */
	private void pollLoading() {
		if (backgroundLoad != null && backgroundLoad.isDone()) {
			BufferedImage image = backgroundLoad.getNow(null);
			backgroundLoad = null;
			background = Textures.upload(image, 1);
		}
		if (state != State.LOADING || !musicLoad.isDone()) return;
		try {
			music = new AlClip(musicLoad.join());
			musicTrack = music.track();
		} catch (RuntimeException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			loadError = "No music: " + cause.getMessage();
			TwentyTpsClient.LOGGER.warn("Couldn't load music for {}: {}", chart.id, cause.toString());
		}
		startNanos = System.nanoTime();
		state = State.PLAYING;
	}

	/** starts the music once song time hits 0 and keeps the clock locked to it. */
	private void syncMusic(double now) {
		if (musicTrack == null || state != State.PLAYING) return;
		musicTrack.setVolume((float) Settings.musicVolume);
		minecraft.getMusicManager().stopPlaying();
		if (!musicStarted) {
			if (now >= 0) {
				musicTrack.setPitch((float) rate);
				musicTrack.playFrom(now / 1000.0);
				musicStarted = true;
			}
			return;
		}
		if (!musicTrack.isPlaying()) return; // song's over
		double drift = now - musicTrack.positionMs();
		if (Math.abs(drift) > MAX_DRIFT_MS) startNanos += (long) (drift / rate * 1_000_000);
	}

	// ---- skipping ----

	/** song time to jump to, or NaN if the next target's too close to bother. */
	private double skipTarget(double now) {
		if (state != State.PLAYING || firstOpen >= targets.size()) return Double.NaN;
		double next = targets.get(firstOpen).time;
		if (next - now < approachMs + SKIP_MIN_GAP_MS) return Double.NaN;
		return next - approachMs - SKIP_LEAD_MS;
	}

	/** jumps over an intro or a long break, keeping the music and click track in step. */
	private void skip() {
		double now = now();
		double target = skipTarget(now);
		if (Double.isNaN(target)) return;
		startNanos -= (long) ((target - now) / rate * 1_000_000);
		lastBeat = (long) Math.floor((target - chart.offsetMs) / chart.beatMs());
		if (musicTrack != null && target >= 0) {
			musicTrack.setPitch((float) rate);
			musicTrack.playFrom(target / 1000.0);
			musicStarted = true;
		}
		play(SoundEvents.UI_BUTTON_CLICK, 1.4f, 0.4f);
	}

	// ---- game logic ----

	private void update(double now) {
		if (state != State.PLAYING) return;
		if (auto) autoplayHits(now);

		if (!chart.hasMusic()) {
			long beat = (long) Math.floor((now - chart.offsetMs) / chart.beatMs());
			if (beat > lastBeat) {
				lastBeat = beat;
				if (now <= lastNoteTime + chart.beatMs() / 2 && Settings.metronomeVolume > 0) {
					boolean downbeat = Math.floorMod(beat, 4) == 0;
					play(downbeat ? SoundEvents.NOTE_BLOCK_BASEDRUM : SoundEvents.NOTE_BLOCK_HAT, 1f, (float) Settings.metronomeVolume * (downbeat ? 0.9f : 0.5f));
				}
			}
		}

		for (int i = firstOpen; i < targets.size(); i++) {
			Target t = targets.get(i);
			if (t.result != null) continue;
			if (now - t.time > okMs) {
				judge(t, Judgement.MISS, now);
				if (state != State.PLAYING) return;
			} else {
				break;
			}
		}
		advanceFirstOpen();

		if (firstOpen >= targets.size()) {
			if (finishedAt < 0) finishedAt = now;
			if (now - finishedAt >= RESULTS_DELAY_MS) finish();
		}
	}

	private void advanceFirstOpen() {
		while (firstOpen < targets.size() && targets.get(firstOpen).result != null) firstOpen++;
	}

	private void tap() {
		if (state != State.PLAYING || auto) return;
		double now = now();
		updateAim(now);
		float px = aimX;
		float py = aimY;
		float radius = targetRadius * scale;

		// the earliest visible target under the cursor gets the tap.
		for (int i = firstOpen; i < targets.size(); i++) {
			Target t = targets.get(i);
			if (t.time - now > approachMs) break;
			if (t.result != null) continue;
			float dx = px - screenX(t.note.x);
			float dy = py - screenY(t.note.y);
			if (dx * dx + dy * dy > radius * radius) continue;

			double offset = Math.abs(now - t.time);
			if (offset > okMs) {
				if (now - t.time >= -Math.max(EARLY_MISS_MS, okMs)) {
					// early enough that you clearly meant this target, but too early to score.
					judge(t, Judgement.MISS, now);
					advanceFirstOpen();
				} else {
					t.shakeUntil = now + SHAKE_MS;
				}
				return;
			}
			Judgement j = offset <= perfectMs ? Judgement.PERFECT : offset <= goodMs ? Judgement.GOOD : Judgement.OK;
			recentOffsets[recentCount % recentOffsets.length] = now - t.time;
			recentJudgements[recentCount % recentJudgements.length] = j;
			recentCount++;
			judge(t, j, now);
			advanceFirstOpen();
			return;
		}
	}

	private void judge(Target t, Judgement j, double now) {
		t.result = j;
		counts[j.ordinal()]++;
		effects.add(new Effect((float) t.note.x, (float) t.note.y, j, now, comboColor(t.note), targets.indexOf(t)));
		health = Math.max(0f, Math.min(1f, health + j.health));

		if (j == Judgement.MISS) {
			combo = 0;
			if (!chart.hasMusic()) play(SoundEvents.NOTE_BLOCK_BASS, 0.5f, 0.6f);
			if (health <= 0 && !Settings.noFail) {
				freeze(State.FAILED);
				if (Theme.minecraft()) play(SoundEvents.PLAYER_DEATH, 1f, 1f);
				else play(SoundEvents.NOTE_BLOCK_DIDGERIDOO, 0.5f, 1f);
			}
			return;
		}

		combo++;
		maxCombo = Math.max(maxCombo, combo);
		score += Math.round(j.value * (1 + Math.min(combo, 100) / 25.0));
		float volume = (float) Settings.hitVolume * (j == Judgement.PERFECT ? 1f : 0.75f);
		if (t.note.n >= 0) {
			play(chart.sound(), (float) Math.pow(2, (t.note.n - 12) / 12.0), volume);
		} else if (skin != null && skin.hitSound != null) {
			skin.hitSound.play(volume * 0.8f);
		} else {
			play(SoundEvents.NOTE_BLOCK_HAT, 1.4f, volume * 0.8f);
		}
	}

	private void finish() {
		freeze(State.RESULTS);
		newBest = ranked && BestScores.submit(chart.id, score, accuracy(), grade());
		play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f);
	}

	private int judged() {
		int total = 0;
		for (int c : counts) total += c;
		return total;
	}

	/** 0..100. points you got out of 300 per target, so a 100 counts as a third and a 50 as a sixth. */
	private double accuracy() {
		int judged = judged();
		if (judged == 0) return 100;
		double sum = 0;
		for (Judgement j : Judgement.values()) sum += counts[j.ordinal()] * j.value;
		return sum / (judged * 3.0);
	}

	private String grade() {
		double acc = accuracy();
		int misses = counts[Judgement.MISS.ordinal()];
		if (acc >= 99.5 && misses == 0) return "SS";
		if (acc >= 95 && misses == 0) return "S";
		if (acc >= 90) return "A";
		if (acc >= 80) return "B";
		if (acc >= 70) return "C";
		return "D";
	}

	private void play(Holder<SoundEvent> sound, float pitch, float volume) {
		play(sound.value(), pitch, volume);
	}

	private void play(SoundEvent sound, float pitch, float volume) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	// ---- input ----

	@Override
	public void mouseMoved(double x, double y) {
		if (relative && cursorHidden) return; // our own scaled cursor is in charge here
		mouseX = x;
		mouseY = y;
	}

	/**
	 * reads the cursor straight from GLFW at full precision. tablet pens in absolute mode report
	 * way more often and more finely than minecraft's per-event integers show, so this keeps aim
	 * exact even between input events.
	 */
	private void pollCursor() {
		var window = minecraft.getWindow();
		if (window.getScreenWidth() <= 0 || window.getScreenHeight() <= 0) return;
		GLFW.glfwGetCursorPos(window.handle(), cursorX, cursorY);
		if (relative && cursorHidden) {
			// move our cursor by however far the system cursor moved, times the sensitivity. works the
			// same for mice and tablets since it only ever compares one frame to the next.
			int sw = window.getScreenWidth();
			int sh = window.getScreenHeight();
			double rx = cursorX[0];
			double ry = cursorY[0];
			if (!Double.isNaN(lastRawX)) {
				double dx = rx - lastRawX;
				double dy = ry - lastRawY;
				// a jump this big between frames isn't your hand, it's a pen coming back into range
				// somewhere else. ignore it instead of flinging the cursor across the screen.
				if (Math.abs(dx) < sw * 0.45 && Math.abs(dy) < sh * 0.45) {
					mouseX = Math.max(0, Math.min(width, mouseX + dx * width / sw * sensitivity));
					mouseY = Math.max(0, Math.min(height, mouseY + dy * height / sh * sensitivity));
				}
			}
			// keep the hidden system cursor away from the window edges so it never gets stuck there
			// (that would cap your movement) or wanders off onto another monitor.
			if (rx < sw * 0.15 || rx > sw * 0.85 || ry < sh * 0.15 || ry > sh * 0.85) {
				rx = sw / 2.0;
				ry = sh / 2.0;
				GLFW.glfwSetCursorPos(window.handle(), rx, ry);
			}
			lastRawX = rx;
			lastRawY = ry;
			return;
		}
		mouseX = cursorX[0] * width / window.getScreenWidth();
		mouseY = cursorY[0] * height / window.getScreenHeight();
	}

	/** updates where we're aiming (mouse, or the bot during autoplay) and feeds the trail. */
	private void updateAim(double now) {
		int gs = guiScale();
		if (auto && state != State.RESULTS && state != State.FAILED) {
			float[] bot = autoplayAim(now);
			aimX = bot[0];
			aimY = bot[1];
		} else {
			pollCursor();
			if (Settings.mapToPlayfield) {
				// the whole window maps onto the playfield, edge to edge.
				aimX = originX + (float) (mouseX / width) * FIELD_W * scaleX;
				aimY = originY + (float) (mouseY / height) * FIELD_H * scaleY;
			} else {
				aimX = (float) (mouseX * gs);
				aimY = (float) (mouseY * gs);
			}
		}
		addTrail(aimX / gs, aimY / gs);
	}

	/** the bot's cursor. eases from one target to the next and lands exactly on time. */
	private float[] autoplayAim(double now) {
		int next = Math.max(0, firstOpen - 1);
		while (next < targets.size() && targets.get(next).time <= now) next++;
		Target to = next < targets.size() ? targets.get(next) : null;
		Target from = next > 0 ? targets.get(next - 1) : null;

		float fx = from != null ? screenX(from.note.x) : screenX(FIELD_W / 2);
		float fy = from != null ? screenY(from.note.y) : screenY(FIELD_H / 2);
		if (to == null) return new float[]{fx, fy};
		float tx = screenX(to.note.x);
		float ty = screenY(to.note.y);

		// start moving when the next target shows up (or right after the last hit, if that's sooner).
		double start = Math.max(from != null ? from.time : -leadInMs, to.time - approachMs);
		double k = Math.max(0, Math.min(1, (now - start) / Math.max(1, to.time - start)));
		float e = (float) (k * k * (3 - 2 * k)); // smoothstep
		return new float[]{fx + (tx - fx) * e, fy + (ty - fy) * e};
	}

	/** autoplay hits every target right as its time comes. */
	private void autoplayHits(double now) {
		for (int i = firstOpen; i < targets.size(); i++) {
			Target t = targets.get(i);
			if (t.time > now) break;
			if (t.result != null) continue;
			recentOffsets[recentCount % recentOffsets.length] = 0;
			recentJudgements[recentCount % recentJudgements.length] = Judgement.PERFECT;
			recentCount++;
			judge(t, Judgement.PERFECT, now);
		}
		advanceFirstOpen();
	}

	/** fills in points between the last position and this one so fast flicks still leave a solid trail. */
	private void addTrail(double x, double y) {
		if (Settings.cursorTrailMs <= 0) return;
		long now = System.nanoTime();
		TrailPoint last = trail.peekLast();
		if (last == null) {
			trail.addLast(new TrailPoint((float) x, (float) y, now));
			return;
		}
		double dx = x - last.x();
		double dy = y - last.y();
		double spacing = Math.max(1, 2.5 * Settings.cursorSize);
		if (skin != null && skin.cursorTrail != null) {
			// skin trail images are big and see-through. pack them too tight and you get a solid smear.
			spacing = Math.max(spacing, skin.cursorTrail.logicalWidth() * 0.75 * Settings.cursorSize * 0.25);
		}
		int steps = (int) Math.min(48, Math.ceil(Math.hypot(dx, dy) / spacing));
		for (int i = 1; i <= steps; i++) {
			double k = i / (double) steps;
			trail.addLast(new TrailPoint((float) (last.x() + dx * k), (float) (last.y() + dy * k),
					last.nanos() + (long) ((now - last.nanos()) * k)));
		}
		while (trail.size() > MAX_TRAIL_POINTS) trail.pollFirst();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!(relative && cursorHidden)) {
			mouseX = event.x();
			mouseY = event.y();
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !Double.isNaN(skipTarget(now()))
				&& overSkipButton(mouseX, mouseY)) {
			skip();
			return true;
		}
		// left is the pen tip on a tablet. right and middle are the barrel buttons.
		boolean mouseTap = Settings.mouseTaps && (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
				|| event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
		if (mouseTap || TwentyTpsClient.tapLeft.matchesMouse(event) || TwentyTpsClient.tapRight.matchesMouse(event)) {
			tap();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (!heldKeys.add(key)) return true; // holding a key down shouldn't keep tapping

		if (TwentyTpsClient.tapLeft.matches(event) || TwentyTpsClient.tapRight.matches(event)) {
			tap();
			return true;
		}
		switch (key) {
			case GLFW.GLFW_KEY_SPACE -> skip();
			case GLFW.GLFW_KEY_ESCAPE -> {
				switch (state) {
					case PLAYING -> freeze(State.PAUSED);
					case PAUSED -> resume();
					default -> onClose();
				}
			}
			case GLFW.GLFW_KEY_R -> {
				if (state != State.PLAYING) restart();
			}
			case GLFW.GLFW_KEY_Q -> {
				if (state != State.PLAYING) onClose();
			}
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		heldKeys.remove(event.key());
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		if (music != null) music.close();
		if (background != null) background.release();
		if (backgroundLoad != null) {
			// drop an image that's still decoding instead of uploading it later.
			backgroundLoad.cancel(false);
			backgroundLoad = null;
		}
		setCursorHidden(false);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	private void setCursorHidden(boolean hidden) {
		if (cursorHidden == hidden) return;
		cursorHidden = hidden;
		var window = minecraft.getWindow();
		long handle = window.handle();
		// only ever hide it, never capture it. capturing (GLFW's "disabled" mode) re-centres the
		// pointer after every move, which sends absolute-mode tablets flying into a corner.
		GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, hidden ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
		if (!relative) return;
		if (hidden) {
			GLFW.glfwGetCursorPos(handle, cursorX, cursorY);
			lastRawX = cursorX[0];
			lastRawY = cursorY[0];
		} else {
			// put the system cursor back where our cursor was.
			if (width > 0 && height > 0) {
				GLFW.glfwSetCursorPos(handle, mouseX * window.getScreenWidth() / width, mouseY * window.getScreenHeight() / height);
			}
			lastRawX = Double.NaN;
			lastRawY = Double.NaN;
		}
	}

	// ---- rendering ----

	private int guiScale() {
		return minecraft.getWindow().getGuiScale();
	}

	private float screenX(double fieldX) {
		return originX + (float) fieldX * scaleX;
	}

	private float screenY(double fieldY) {
		return originY + (float) fieldY * scaleY;
	}

	private int comboColor(Chart.Note note) {
		return comboColors[Math.floorMod(note.colorIndex, comboColors.length)];
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (Theme.minecraft() && background == null) {
			super.renderBackground(g, mouseX, mouseY, partialTick);
			g.fill(0, 0, width, height, Math.round((float) Settings.backgroundDim * 0.6f * 255) << 24);
			return;
		}
		g.fillGradient(0, 0, width, height, 0xF0101020, 0xF0201030);
		if (background != null) {
			// cover the screen without stretching, then dim it.
			float cover = Math.max((float) width / background.width(), (float) height / background.height());
			int w = Math.round(background.width() * cover);
			int h = Math.round(background.height() * cover);
			drawTexture(g, background, width / 2f, height / 2f, w, h, 0xFFFFFFFF);
			g.fill(0, 0, width, height, Math.round((float) Settings.backgroundDim * 255) << 24);
		}
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		pollLoading();
		syncMusic(now());
		update(now());
		double now = now();

		int gs = guiScale();
		float pw = width * gs;
		float ph = height * gs;
		scale = Math.min(pw / 600f, ph / 470f);
		if (Settings.fillScreen) {
			// keep whole targets on screen and leave a strip at the top for the HUD.
			float margin = targetRadius * scale + 6 * gs;
			float top = margin + 14 * gs;
			originX = margin;
			originY = top;
			scaleX = (pw - 2 * margin) / FIELD_W;
			scaleY = (ph - top - margin) / FIELD_H;
		} else {
			originX = (pw - FIELD_W * scale) / 2;
			originY = (ph - FIELD_H * scale) / 2 + 8 * gs;
			scaleX = scale;
			scaleY = scale;
		}

		updateAim(now);

		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
		renderField(g, now, gs);
		pose.popMatrix();

		renderHud(g, now);
		switch (state) {
			case LOADING -> drawCentered(g, "Loading music...", width / 2f, height / 2f + 30, 1f, 0xFFCCCCDD);
			case PAUSED -> renderOverlay(g, Theme.minecraft() ? "Game Paused" : "PAUSED", 0xFFFFFFFF, "Esc  resume     R  retry     Q  quit");
			case FAILED -> {
				if (Theme.minecraft()) renderDeathScreen(g);
				else renderOverlay(g, "FAILED", 0xFFFF5C5C, "R  retry     Esc  back");
			}
			case RESULTS -> renderResults(g);
			default -> {
			}
		}

		// while playing we draw our own cursor (or the skin's) so it can be resized and get a trail.
		boolean ownCursor = state == State.PLAYING || state == State.LOADING;
		setCursorHidden(ownCursor);
		if (ownCursor) renderCursor(g, gs);
	}

	private void renderCursor(GuiGraphics g, int gs) {
		long now = System.nanoTime();
		double trailNanos = Settings.cursorTrailMs * 1_000_000;
		while (!trail.isEmpty() && now - trail.peekFirst().nanos() > trailNanos) trail.pollFirst();

		float size = (float) Settings.cursorSize;
		Tex cursorTex = skin != null ? skin.cursor : null;
		Tex trailTex = skin != null ? skin.cursorTrail : null;
		// skin images get drawn at 0.75 gui units per image pixel, times cursor size.
		float texScale = 0.75f * size;

		Matrix3x2fStack pose = g.pose();
		if (Settings.cursorTrailMs > 0) {
			if (trailTex != null) {
				for (TrailPoint p : trail) {
					float alpha = 1 - (float) ((now - p.nanos()) / trailNanos);
					drawTexture(g, trailTex, p.x(), p.y(), trailTex.logicalWidth() * texScale, trailTex.logicalHeight() * texScale,
							Shapes.fade(0xFFFFFFFF, alpha));
				}
			} else {
				pose.pushMatrix();
				pose.scale(1f / gs, 1f / gs);
				float radius = 4.5f * size * gs;
				for (TrailPoint p : trail) {
					float k = (float) ((now - p.nanos()) / trailNanos);
					if (Theme.minecraft()) {
						// little square "particles"
						Shapes.square(g, p.x() * gs, p.y() * gs, Math.max(gs, 3f * size * gs * (1 - 0.5f * k)), Shapes.fade(0xFFFFFFFF, 0.6f * (1 - k)));
					} else {
						Shapes.disc(g, p.x() * gs, p.y() * gs, Math.max(1, radius * (1 - 0.6f * k)), Shapes.fade(CURSOR_COLOR, 0.55f * (1 - k)));
					}
				}
				pose.popMatrix();
			}
		}

		float x = aimX / gs;
		float y = aimY / gs;
		if (cursorTex != null) {
			drawTexture(g, cursorTex, x, y, cursorTex.logicalWidth() * texScale, cursorTex.logicalHeight() * texScale, 0xFFFFFFFF);
			return;
		}
		if (Theme.minecraft()) {
			// the crosshair, with its inverting blend so you can see it on any background.
			int c = Math.max(5, Math.round(15 * 1.2f * size));
			g.blitSprite(RenderPipelines.CROSSHAIR, CROSSHAIR, Math.round(x - c / 2f), Math.round(y - c / 2f), c, c);
			return;
		}
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
		float r = 7f * size * gs;
		Shapes.disc(g, x * gs, y * gs, r + Math.max(1, gs * size), 0x80000000);
		Shapes.ring(g, x * gs, y * gs, r, Math.max(2, 1.6f * gs * size), 0xFFFFFFFF);
		Shapes.disc(g, x * gs, y * gs, Math.max(1.5f, r * 0.35f), CURSOR_COLOR);
		pose.popMatrix();
	}

	private void renderField(GuiGraphics g, double now, int gs) {
		// playfield border
		if (!Settings.fillScreen) {
			g.renderOutline(Math.round(originX - 4), Math.round(originY - 4),
					Math.round(FIELD_W * scale + 8), Math.round(FIELD_H * scale + 8), 0x30FFFFFF);
		}

		float r = targetRadius * scale;
		float border = Math.max(2, r * 0.13f);
		// skin images are made for a circle radius of 64.
		float skinScale = r / 64f;

		// draw later targets first so the next one you need to hit ends up on top.
		int last = firstOpen;
		while (last < targets.size() && targets.get(last).time - now <= approachMs) last++;
		for (int i = last - 1; i >= firstOpen; i--) {
			Target t = targets.get(i);
			if (t.result != null) continue;
			double until = t.time - now;
			float appear = (float) ((approachMs - until) / (approachMs * 0.35));
			float x = screenX(t.note.x);
			if (now < t.shakeUntil) {
				// wobble side to side and settle down as it ends.
				double left = (t.shakeUntil - now) / SHAKE_MS;
				x += (float) (Math.sin((SHAKE_MS - (t.shakeUntil - now)) / 20.0 * Math.PI) * left * r * 0.18);
			}
			float y = screenY(t.note.y);
			int color = comboColor(t.note);
			float ringScale = 1 + 2.2f * (float) Math.max(0, until / approachMs);

			if (skin != null && skin.hitCircle != null) {
				drawSkinned(skin.hitCircle, g, x, y, skinScale, Shapes.fade(color, appear));
				if (skin.hitCircleOverlay != null) drawSkinned(skin.hitCircleOverlay, g, x, y, skinScale, Shapes.fade(0xFFFFFFFF, appear));
			} else if (Theme.minecraft()) {
				Shapes.pixelBullseye(g, x, y, r, TARGET_PIXELS, color, appear);
			} else {
				Shapes.disc(g, x, y, r, Shapes.fade(0xFFFFFFFF, appear));
				Shapes.disc(g, x, y, r - border, Shapes.fade(color, appear * 0.9f));
			}
			drawLabel(g, t.note, x, y, r, skinScale, appear);

			if (skin != null && skin.approachCircle != null) {
				drawSkinned(skin.approachCircle, g, x, y, skinScale * ringScale, Shapes.fade(color, appear));
			} else if (Theme.minecraft()) {
				float p = 2 * r / TARGET_PIXELS;
				Shapes.pixelRing(g, x, y, r * ringScale, p, Shapes.fade(color, appear));
			} else {
				Shapes.ring(g, x, y, r * ringScale, Math.max(2, 1.5f * gs), Shapes.fade(color, appear));
			}
		}

		// hit and miss feedback
		Iterator<Effect> it = effects.iterator();
		while (it.hasNext()) {
			Effect e = it.next();
			double age = now - e.start();
			if (age > 500 || age < 0) {
				if (state == State.PLAYING) it.remove();
				continue;
			}
			float k = (float) (age / 500);
			float x = screenX(e.x());
			float y = screenY(e.y());
			// skin hit images only when the player picked them. some skins ship blank ones on purpose
			// (to hide 300s), so with any other style we draw our own burst and text instead.
			Tex judgementTex = skin != null && Settings.judgementStyle.equals("skin") ? skin.judgements[e.judgement().ordinal()] : null;
			if (judgementTex != null) {
				if (Settings.showJudgements) {
					drawSkinned(judgementTex, g, x, y, skinScale * (0.8f + 0.2f * k), Shapes.fade(0xFFFFFFFF, 1 - k * k));
				}
				continue;
			}
			if (e.judgement() != Judgement.MISS) {
				if (Theme.minecraft()) blockParticles(g, e, x, y, r, k);
				else Shapes.ring(g, x, y, r * (1 + 0.6f * k), Math.max(2, border * (1 - k)), Shapes.fade(e.judgement().color(), 1 - k));
			}
			if (Settings.showJudgements) {
				drawCentered(g, e.judgement().label(), x, y - r * 0.4f - k * r * 0.5f, r / 26f, Shapes.fade(e.judgement().color(), 1 - k * k));
			}
		}
	}

	/** combo number for imported maps, note name for melody charts. */
	private void drawLabel(GuiGraphics g, Chart.Note note, float x, float y, float r, float skinScale, float alpha) {
		String label;
		if (chart.comboNumbers) label = String.valueOf(note.combo);
		else if (Settings.showNoteNames && note.n >= 0) label = NOTE_NAMES[Math.floorMod(note.n, 12)];
		else return;

		if (chart.comboNumbers && skin != null && skin.digits[0] != null) {
			float digitScale = skinScale * 0.8f;
			float overlap = skin.digitOverlap * digitScale;
			float total = 0;
			for (char c : label.toCharArray()) total += skin.digits[c - '0'].logicalWidth() * digitScale - overlap;
			total += overlap;
			float left = x - total / 2;
			for (char c : label.toCharArray()) {
				Tex digit = skin.digits[c - '0'];
				float w = digit.logicalWidth() * digitScale;
				drawTexture(g, digit, left + w / 2, y, w, digit.logicalHeight() * digitScale, Shapes.fade(0xFFFFFFFF, alpha));
				left += w - overlap;
			}
			return;
		}
		if (Theme.minecraft() && (skin == null || skin.hitCircle == null)) {
			if (((int) (alpha * 255)) < 8) return;
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			float k = r / 22f;
			pose.scale(k, k);
			Theme.outlined(g, font, label, -font.width(label) / 2, -font.lineHeight / 2 + 1, Shapes.fade(0xFFFFFFFF, Math.min(1, alpha)));
			pose.popMatrix();
			return;
		}
		drawCentered(g, label, x, y, r / 22f, Shapes.fade(0xFFFFFFFF, alpha));
	}

	/** block-break style debris: chunky pixels in the target's colours that fly out and fall. */
	private void blockParticles(GuiGraphics g, Effect e, float x, float y, float r, float k) {
		float p = 2 * r / TARGET_PIXELS;
		long seed = e.seed() * 0x9E3779B97F4A7C15L;
		for (int i = 0; i < 12; i++) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			double angle = ((seed >>> 33) % 6283) / 1000.0;
			double speed = 0.6 + ((seed >>> 20) % 1000) / 1000.0 * 0.9;
			float px = x + (float) (Math.cos(angle) * speed * r * k * 1.3);
			float py = y + (float) (Math.sin(angle) * speed * r * k * 1.3) + k * k * r * 1.2f;
			int color = switch (i % 3) {
				case 0 -> Theme.TARGET_RED;
				case 1 -> Theme.TARGET_WHITE;
				default -> e.color();
			};
			Shapes.square(g, px, py, p * (1.2f - 0.6f * k), Shapes.fade(color, 1 - k));
		}
	}

	private void drawSkinned(Tex tex, GuiGraphics g, float cx, float cy, float scale, int color) {
		drawTexture(g, tex, cx, cy, tex.logicalWidth() * scale, tex.logicalHeight() * scale, color);
	}

	/** draws a whole texture centred on (cx, cy), stretched to w x h and tinted by `color`. */
	private static void drawTexture(GuiGraphics g, Tex tex, float cx, float cy, float w, float h, int color) {
		if (((color >>> 24) & 0xFF) < 4 || w < 1 || h < 1) return;
		g.blit(RenderPipelines.GUI_TEXTURED, tex.id(), Math.round(cx - w / 2), Math.round(cy - h / 2), 0, 0,
				Math.round(w), Math.round(h), tex.width(), tex.height(), tex.width(), tex.height(), color);
	}

	/** draws text centred on (x, y) at the given scale, in whatever units the pose is using. */
	private void drawCentered(GuiGraphics g, String text, float x, float y, float textScale, int color) {
		if (((color >>> 24) & 0xFF) < 8) return;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(textScale, textScale);
		g.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2 + 1, color, true);
		pose.popMatrix();
	}

	private void renderHud(GuiGraphics g, double now) {
		if (Theme.minecraft()) {
			renderMinecraftHud(g, now);
		} else {
			renderClassicHud(g, now);
		}
		if (Settings.hitErrorBar) renderErrorBar(g);
		renderSkipPrompt(g, now);
		if (rate != 1.0) drawRight(g, String.format("%.2fx", rate), width - 8, 40, 1f, 0xFFFFB35C);
		if (auto) drawCentered(g, "AUTOPLAY", width / 2f, 12, 1f, Shapes.fade(Theme.accent(), 0.85f));

		// song title card during the lead-in
		if (now < 0) {
			float alpha = state == State.LOADING ? 1 : (float) Math.min(1, -now / 500);
			drawCentered(g, chart.title, width / 2f, height / 2f - 20, 2.5f, Shapes.fade(0xFFFFFFFF, alpha));
			drawCentered(g, chart.artist + "  ·  " + Math.round(chart.bpm) + " BPM", width / 2f, height / 2f + 6, 1f,
					Shapes.fade(Theme.minecraft() ? Theme.MC_YELLOW : 0xFFCCCCDD, alpha));
		}
		if (loadError != null && now < 3000) {
			drawCentered(g, loadError, width / 2f, height - 50, 1f, 0xFFFF5C5C);
		}
	}

	/** hearts for health, the XP bar for song progress, and your combo as the XP level number. */
	private void renderMinecraftHud(GuiGraphics g, double now) {
		int halves = Math.round(health * 20);
		boolean shaky = halves <= 4 && state == State.PLAYING;
		for (int i = 0; i < 10; i++) {
			int hx = 8 + i * 8;
			int hy = 8 + (shaky ? (int) ((System.nanoTime() / 50_000_000L + i * 7) % 3) - 1 : 0);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, hx, hy, 9, 9);
			if (halves >= i * 2 + 2) g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, hx, hy, 9, 9);
			else if (halves == i * 2 + 1) g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, hx, hy, 9, 9);
		}

		drawRight(g, String.format("%,d", score), width - 8, 8, 2f, 0xFFFFFFFF);
		drawRight(g, String.format("%.2f%%", accuracy()), width - 8, 28, 1f, Theme.MC_GRAY);

		int barX = width / 2 - 91;
		int barY = height - 10;
		float progress = (float) Math.max(0, Math.min(1, now / (lastNoteTime + 1)));
		g.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BACKGROUND, barX, barY, 182, 5);
		int filled = Math.round(182 * progress);
		if (filled > 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, XP_PROGRESS, 182, 5, 0, 0, barX, barY, filled, 5);
		if (combo > 0) {
			String level = String.valueOf(combo);
			Theme.outlined(g, font, level, width / 2 - font.width(level) / 2, barY - 9, 0xFF80FF20);
		}
	}

	private void renderClassicHud(GuiGraphics g, double now) {
		// health bar
		int barW = Math.min(160, width / 3);
		g.fill(8, 8, 8 + barW, 13, 0x60000000);
		int healthColor = health > 0.3f ? 0xFF7CFFCB : 0xFFFF5C5C;
		g.fill(8, 8, 8 + Math.round(barW * health), 13, healthColor);

		// song progress
		double total = lastNoteTime + 1;
		float progress = (float) Math.max(0, Math.min(1, now / total));
		g.fill(0, 0, Math.round(width * progress), 2, 0xA0FFFFFF);

		// score and accuracy, top right
		drawRight(g, String.format("%08d", score), width - 8, 8, 2f, 0xFFFFFFFF);
		drawRight(g, String.format("%.2f%%", accuracy()), width - 8, 28, 1f, 0xFFCCCCDD);

		// combo, bottom left
		if (combo > 0) {
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(8, height - 26);
			pose.scale(2.2f, 2.2f);
			g.drawString(font, combo + "x", 0, 0, 0xFFFFFFFF, true);
			pose.popMatrix();
		}
	}

	private boolean overSkipButton(double x, double y) {
		return x >= skipX0 && x < skipX1 && y >= skipY0 && y < skipY1;
	}

	/** the skip button in the bottom-right corner, shown while there's a long gap before the next target. */
	private void renderSkipPrompt(GuiGraphics g, double now) {
		if (Double.isNaN(skipTarget(now))) {
			skipX0 = skipX1 = 0;
			return;
		}
		String text = "SKIP  ▸▸   Space";
		int w = font.width(text) + 16;
		int h = 20;
		skipX1 = width - 8;
		skipX0 = skipX1 - w;
		skipY1 = height - 8;
		skipY0 = skipY1 - h;
		boolean hovered = overSkipButton(mouseX, mouseY);
		if (Theme.minecraft()) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, hovered ? BUTTON_HIGHLIGHTED : BUTTON, skipX0, skipY0, w, h);
			g.drawCenteredString(font, "Skip ▸▸  (Space)", (skipX0 + skipX1) / 2, skipY0 + 6, hovered ? Theme.MC_YELLOW : 0xFFFFFFFF);
			return;
		}
		float pulse = 0.75f + 0.25f * (float) Math.sin(System.nanoTime() / 1e9 * Math.PI * 2);
		g.fill(skipX0, skipY0, skipX1, skipY1, hovered ? 0xC0FF6FAE : 0x90000000);
		g.renderOutline(skipX0, skipY0, w, h, Shapes.fade(0xFFFF6FAE, pulse));
		g.drawString(font, text, skipX0 + 8, skipY0 + 6, 0xFFFFFFFF, true);
	}

	/** ticks for recent hits on a bar as wide as the 50 window. early is left, late is right. */
	private void renderErrorBar(GuiGraphics g) {
		int half = Math.min(90, width / 5);
		int cx = width / 2;
		int y = Theme.minecraft() ? height - 30 : height - 14;
		float perMs = half / (float) okMs;
		// bands match the judgement colours: 50 outside, then 100, then 300 in the middle
		g.fill(cx - half, y, cx + half, y + 3, Shapes.fade(Judgement.OK.color(), 0.38f));
		g.fill(cx - Math.round((float) goodMs * perMs), y, cx + Math.round((float) goodMs * perMs), y + 3, Shapes.fade(Judgement.GOOD.color(), 0.38f));
		g.fill(cx - Math.round((float) perfectMs * perMs), y, cx + Math.round((float) perfectMs * perMs), y + 3, Shapes.fade(Judgement.PERFECT.color(), 0.38f));
		g.fill(cx, y - 4, cx + 1, y + 7, 0xFFFFFFFF);
		int shown = Math.min(recentCount, recentOffsets.length);
		for (int i = 0; i < shown; i++) {
			int index = Math.floorMod(recentCount - 1 - i, recentOffsets.length);
			int x = cx + Math.round((float) recentOffsets[index] * perMs);
			float alpha = 1 - i / (float) recentOffsets.length;
			g.fill(x, y - 3, x + 1, y + 6, Shapes.fade(recentJudgements[index].color(), alpha));
		}
	}

	private void drawRight(GuiGraphics g, String text, float right, float y, float textScale, int color) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(right - font.width(text) * textScale, y);
		pose.scale(textScale, textScale);
		g.drawString(font, text, 0, 0, color, true);
		pose.popMatrix();
	}

	private void renderOverlay(GuiGraphics g, String title, int color, String hint) {
		g.fill(0, 0, width, height, 0xA0000000);
		drawCentered(g, title, width / 2f, height / 2f - 14, 3f, color);
		drawCentered(g, hint, width / 2f, height / 2f + 16, 1f, 0xFFCCCCDD);
	}

	/** like the death screen: red gradient, "You Died!" and your score. */
	private void renderDeathScreen(GuiGraphics g) {
		g.fillGradient(0, 0, width, height, 0x60500000, 0xA0803030);
		drawCentered(g, "You Died!", width / 2f, height / 4f + 6, 2f, 0xFFFFFFFF);
		String scoreLine = "Score: ";
		String value = String.format("%,d", score);
		int w = font.width(scoreLine + value);
		int sx = width / 2 - w / 2;
		int sy = Math.round(height / 4f + 30);
		g.drawString(font, scoreLine, sx, sy, 0xFFFFFFFF, true);
		g.drawString(font, value, sx + font.width(scoreLine), sy, Theme.MC_YELLOW, true);
		drawCentered(g, "R  Retry     Esc  Back", width / 2f, height / 2f + 30, 1f, 0xFFFFFFFF);
	}

	private void renderResults(GuiGraphics g) {
		g.fill(0, 0, width, height, 0xC0000000);
		if (Theme.minecraft()) {
			// panel styled like an item tooltip
			int pw = 220;
			int ph = 200;
			int px = width / 2 - pw / 2;
			int py = Math.round(height / 2f - 92);
			g.fill(px - 1, py - 2, px + pw + 1, py + ph + 2, 0xF0100010);
			g.fill(px - 2, py - 1, px + pw + 2, py + ph + 1, 0xF0100010);
			g.fillGradient(px - 1, py - 1, px, py + ph + 1, 0x505000FF, 0x5028007F);
			g.fillGradient(px + pw, py - 1, px + pw + 1, py + ph + 1, 0x505000FF, 0x5028007F);
			g.fill(px - 1, py - 1, px + pw + 1, py, 0x505000FF);
			g.fill(px - 1, py + ph, px + pw + 1, py + ph + 1, 0x5028007F);
		}
		float cx = width / 2f;
		float y = height / 2f - 80;
		drawCentered(g, chart.title, cx, y, 1.5f, 0xFFFFFFFF);
		drawCentered(g, grade(), cx, y + 36, 5f, gradeColor(grade()));
		drawCentered(g, String.format("%,d", score), cx, y + 72, 2f, 0xFFFFFFFF);
		drawCentered(g, String.format("%.2f%%  ·  max combo %dx", accuracy(), maxCombo), cx, y + 92, 1f, 0xFFCCCCDD);

		float rowY = y + 112;
		for (Judgement j : Judgement.values()) {
			drawCentered(g, j.label() + "  " + counts[j.ordinal()], cx, rowY, 1f, j.color());
			rowY += 12;
		}
		if (newBest) drawCentered(g, Theme.minecraft() ? "Advancement Made! New personal best" : "NEW PERSONAL BEST", cx, rowY + 6, 1f, Theme.minecraft() ? Theme.MC_YELLOW : 0xFFFFD36B);
		else if (auto) drawCentered(g, "Autoplay: not saved", cx, rowY + 6, 1f, 0xFF9999AA);
		else if (!ranked) drawCentered(g, "Not saved: easier settings were on", cx, rowY + 6, 1f, 0xFF9999AA);
		drawCentered(g, "R  retry     Esc  back", cx, rowY + 24, 1f, 0xFF9999AA);
	}

	private static int gradeColor(String grade) {
		if (Theme.minecraft()) {
			return switch (grade) {
				case "SS", "S" -> Theme.MC_GOLD;
				case "A" -> Theme.MC_GREEN;
				case "B" -> Theme.MC_AQUA;
				case "C" -> Theme.MC_PINK;
				default -> Theme.MC_RED;
			};
		}
		return switch (grade) {
			case "SS", "S" -> 0xFFFFD36B;
			case "A" -> 0xFF7CFFCB;
			case "B" -> 0xFF6FB7FF;
			case "C" -> 0xFFB48CFF;
			default -> 0xFFFF5C5C;
		};
	}
}
