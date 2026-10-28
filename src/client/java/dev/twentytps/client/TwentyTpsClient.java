package dev.twentytps.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TwentyTpsClient implements ClientModInitializer {
	public static final String MOD_ID = "twentytps";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** keys that hit targets while you play. you can rebind them under Controls. */
	public static KeyMapping tapLeft;
	public static KeyMapping tapRight;

	@Override
	public void onInitializeClient() {
		Settings.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping open = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.twentytps.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		tapLeft = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.twentytps.tap_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		tapRight = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.twentytps.tap_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (open.consumeClick()) {
				if (client.screen == null) client.setScreen(new MainMenuScreen());
			}
		});

		// the vanilla title screen (you get there with the menu's "Minecraft" button) needs a way back.
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen) {
				Library.ensureStarted();
				Screens.getButtons(screen).add(Button.builder(Component.literal("20tps"), b -> client.setScreen(new MainMenuScreen()))
						.bounds(4, 4, 50, 20).build());
			}
		});
	}
}
