package dev.twentytps.client.audio;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * plays audio straight through OpenAL, for stuff that isn't a registered minecraft sound
 * (imported song music, skin hit sounds). piggybacks on the OpenAL context minecraft already
 * set up. only call this from the render thread.
 */
public final class AlClip {
	private final int buffer;
	private final List<Integer> sources = new ArrayList<>();
	private boolean closed;

	public AlClip(AudioDecoder.Pcm pcm) {
		buffer = AL10.alGenBuffers();
		ShortBuffer data = MemoryUtil.memAllocShort(pcm.samples().length);
		try {
			data.put(pcm.samples()).flip();
			int format = pcm.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
			AL10.alBufferData(buffer, format, data, pcm.sampleRate());
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private int newSource() {
		int source = AL10.alGenSources();
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		// not positional, so it plays "in your head" no matter where the player is.
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
		AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
		AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
		sources.add(source);
		return source;
	}

	public void play(float volume) {
		if (closed) return;
		int source = -1;
		for (int s : sources) {
			if (AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
				source = s;
				break;
			}
		}
		if (source < 0) {
			if (sources.size() >= 16) return;
			source = newSource();
		}
		AL10.alSourcef(source, AL10.AL_GAIN, volume);
		AL10.alSourcePlay(source);
	}

	public Track track() {
		return new Track(newSource());
	}

	public void close() {
		if (closed) return;
		closed = true;
		for (int s : sources) {
			AL10.alSourceStop(s);
			AL10.alDeleteSources(s);
		}
		sources.clear();
		AL10.alDeleteBuffers(buffer);
	}

	public final class Track {
		private final int source;

		private Track(int source) {
			this.source = source;
		}

		public void setVolume(float volume) {
			AL10.alSourcef(source, AL10.AL_GAIN, volume);
		}

		/** playback rate. also shifts pitch. */
		public void setPitch(float pitch) {
			AL10.alSourcef(source, AL10.AL_PITCH, pitch);
		}

		public void playFrom(double seconds) {
			if (closed) return;
			AL10.alSourcef(source, AL11.AL_SEC_OFFSET, (float) Math.max(0, seconds));
			AL10.alSourcePlay(source);
		}

		public void resume() {
			if (!closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) AL10.alSourcePlay(source);
		}

		public void pause() {
			if (!closed && isPlaying()) AL10.alSourcePause(source);
		}

		public void stop() {
			if (!closed) AL10.alSourceStop(source);
		}

		public boolean isPlaying() {
			return !closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
		}

		/** playback position in milliseconds. */
		public double positionMs() {
			return AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) * 1000.0;
		}
	}
}
