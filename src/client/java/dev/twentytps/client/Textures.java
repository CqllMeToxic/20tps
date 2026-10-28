package dev.twentytps.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** uploads beatmap and skin images (png or jpg) as gui textures. */
public final class Textures {
	/** an uploaded texture. `density` is 2 for @2x skin images, so their real size is width / density. */
	public record Tex(Identifier id, int width, int height, int density) {
		public float logicalWidth() {
			return (float) width / density;
		}

		public float logicalHeight() {
			return (float) height / density;
		}

		public void release() {
			Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	private static int counter;

	private Textures() {}

	/** decodes off the render thread. returns null if the image can't be read. */
	public static BufferedImage decode(byte[] bytes) {
		if (bytes == null) return null;
		try {
			return ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** call this on the render thread. */
	public static Tex upload(BufferedImage image, int density) {
		if (image == null) return null;
		NativeImage pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				pixels.setPixel(x, y, image.getRGB(x, y));
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath(TwentyTpsClient.MOD_ID, "dynamic/" + counter++);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, pixels));
		return new Tex(id, image.getWidth(), image.getHeight(), density);
	}
}
