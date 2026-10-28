package dev.twentytps.client.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/** turns mp3, ogg and wav files into plain 16-bit PCM. */
public final class AudioDecoder {
	/** interleaved 16-bit samples (L R L R... for stereo). */
	public record Pcm(short[] samples, int channels, int sampleRate) {}

	private AudioDecoder() {}

	public static Pcm decode(byte[] data) throws IOException {
		if (data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') return decodeOgg(data);
		if (data.length >= 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return decodeWav(data);
		return decodeMp3(data);
	}

	private static Pcm decodeMp3(byte[] data) throws IOException {
		Bitstream bitstream = new Bitstream(new ByteArrayInputStream(data));
		Decoder decoder = new Decoder();
		short[] out = new short[1 << 20];
		int length = 0;
		int channels = 0;
		int rate = 0;
		try {
			Header header;
			while ((header = bitstream.readFrame()) != null) {
				SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				if (channels == 0) {
					channels = frame.getChannelCount();
					rate = frame.getSampleFrequency();
				}
				int n = frame.getBufferLength();
				if (length + n > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, length + n));
				System.arraycopy(frame.getBuffer(), 0, out, length, n);
				length += n;
				bitstream.closeFrame();
			}
		} catch (BitstreamException | DecoderException | ArrayIndexOutOfBoundsException e) {
			// lots of mp3s have a busted or cut-off ending. just keep whatever decoded fine.
			if (length == 0) throw new IOException("Couldn't decode MP3: " + e.getMessage(), e);
		} finally {
			try {
				bitstream.close();
			} catch (BitstreamException ignored) {
			}
		}
		if (length == 0) throw new IOException("MP3 contained no audio");
		return new Pcm(Arrays.copyOf(out, length), channels, rate);
	}

	private static Pcm decodeOgg(byte[] data) throws IOException {
		ByteBuffer input = MemoryUtil.memAlloc(data.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			input.put(data).flip();
			IntBuffer channels = stack.mallocInt(1);
			IntBuffer rate = stack.mallocInt(1);
			ShortBuffer decoded = STBVorbis.stb_vorbis_decode_memory(input, channels, rate);
			if (decoded == null) throw new IOException("Couldn't decode OGG");
			try {
				short[] samples = new short[decoded.remaining()];
				decoded.get(samples);
				return new Pcm(samples, channels.get(0), rate.get(0));
			} finally {
				MemoryUtil.memFree(decoded);
			}
		} finally {
			MemoryUtil.memFree(input);
		}
	}

	private static Pcm decodeWav(byte[] data) throws IOException {
		try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
			AudioFormat from = source.getFormat();
			AudioFormat to = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, from.getSampleRate(), 16,
					from.getChannels(), from.getChannels() * 2, from.getSampleRate(), false);
			try (AudioInputStream converted = AudioSystem.getAudioInputStream(to, source)) {
				byte[] bytes = converted.readAllBytes();
				ShortBuffer shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
				short[] samples = new short[shorts.remaining()];
				shorts.get(samples);
				return new Pcm(samples, to.getChannels(), Math.round(to.getSampleRate()));
			}
		} catch (UnsupportedAudioFileException | IllegalArgumentException e) {
			throw new IOException("Couldn't decode WAV: " + e.getMessage(), e);
		}
	}
}
