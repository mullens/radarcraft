package com.lucasmods.keyboardkeybinds;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Keyboard Keybinds: replaces the vanilla Key Binds screen with a visual keyboard where every
 * key shows what is bound to it, real conflicts are red and intentional overlaps are yellow.
 * Client-side only.
 */
@Mod(value = KeyboardKeybinds.MODID, dist = Dist.CLIENT)
public final class KeyboardKeybinds {
    public static final String MODID = "keyboardkeybinds";
    public static final Logger LOGGER = LogUtils.getLogger();

    public KeyboardKeybinds(IEventBus modBus, ModContainer container) {
        // Lowest priority: run after other mods that swap the Key Binds screen (Controlling), so the keyboard wins.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, KeyboardKeybinds::onScreenOpening);
        LOGGER.info("Keyboard Keybinds loaded: the Key Binds screen now shows a keyboard.");
    }

    /** Dev aid: -Dkeyboardkeybinds.devOpen=true opens the keyboard screen as soon as the title screen appears. */
    private static final boolean DEV_OPEN = Boolean.getBoolean("keyboardkeybinds.devOpen");
    private static boolean devOpened = false;

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (DEV_OPEN && !devOpened && event.getNewScreen() instanceof TitleScreen title) {
            devOpened = true;
            event.setNewScreen(new KeyboardScreen(title, Minecraft.getInstance().options));
            return;
        }
        if (!isKeyBindsScreen(event.getNewScreen())) return;
        if (KeyboardScreen.openingClassic) {
            // The keyboard screen itself asked for the classic list; let it through once
            // (if Controlling is installed, its own handler has already swapped in its list).
            KeyboardScreen.openingClassic = false;
            return;
        }
        // Return to whatever opened the key binds (normally the Controls screen).
        Screen back = event.getNewScreen() instanceof OptionsSubScreen sub ? sub.lastScreen : event.getCurrentScreen();
        event.setNewScreen(new KeyboardScreen(back, Minecraft.getInstance().options));
    }

    /** Vanilla's screen, or a mod's replacement for it (Controlling's NewKeyBindsScreen is not a subclass). */
    private static boolean isKeyBindsScreen(Screen screen) {
        return screen instanceof KeyBindsScreen || screen.getClass().getSimpleName().endsWith("KeyBindsScreen");
    }
}
