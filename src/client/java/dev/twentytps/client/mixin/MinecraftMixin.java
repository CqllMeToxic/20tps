package dev.twentytps.client.mixin;

import dev.twentytps.client.MainMenuScreen;
import dev.twentytps.client.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/** swaps minecraft's title screen for the 20tps menu, unless the player picked the vanilla one. */
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen twentytps$replaceTitleScreen(Screen screen) {
		if (screen instanceof TitleScreen && screen != MainMenuScreen.allowedTitle && Settings.replaceTitleScreen) {
			return new MainMenuScreen();
		}
		return screen;
	}
}
