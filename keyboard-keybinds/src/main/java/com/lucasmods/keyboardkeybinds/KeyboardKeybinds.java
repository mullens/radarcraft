package com.lucasmods.keyboardkeybinds;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.neoforged.api.distmarker.Dist;
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
        NeoForge.EVENT_BUS.addListener(KeyboardKeybinds::onScreenOpening);
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
        if (!(event.getNewScreen() instanceof KeyBindsScreen vanilla)) return;
        if (KeyboardScreen.openingClassic) {
            // The keyboard screen itself asked for the classic list; let it through once.
            KeyboardScreen.openingClassic = false;
            return;
        }
        // lastScreen is made public by META-INF/accesstransformer.cfg
        event.setNewScreen(new KeyboardScreen(vanilla.lastScreen, Minecraft.getInstance().options));
    }
}
