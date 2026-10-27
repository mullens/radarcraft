package com.lucasmods.keyboardkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keyboard Keybinds: a visual keyboard view of the key binds, as a bonus next to the normal Key Binds list (vanilla's or
 * Controlling's). Every key shows what is bound to it, real conflicts are red and intentional overlaps are yellow.
 * The list screen stays in charge; this mod only adds a "Keyboard" button to it, plus an optional key that opens the
 * keyboard directly. Client-side only.
 */
@Mod(value = KeyboardKeybinds.MODID, dist = Dist.CLIENT)
public final class KeyboardKeybinds {
    public static final String MODID = "keyboardkeybinds";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Opens the keyboard straight from the game. Unbound by default so this mod never adds a conflict of its own. */
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.keyboardkeybinds.open", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, "key.categories.keyboardkeybinds");

    /** Our button per list screen; some mods paint over foreign widgets, so the button is drawn again last. */
    private static final Map<Screen, Button> BUTTONS = new WeakHashMap<>();

    public KeyboardKeybinds(IEventBus modBus, ModContainer container) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_KEY));
        NeoForge.EVENT_BUS.addListener(KeyboardKeybinds::onScreenInit);
        NeoForge.EVENT_BUS.addListener(KeyboardKeybinds::onScreenRender);
        NeoForge.EVENT_BUS.addListener(KeyboardKeybinds::onClientTick);
        NeoForge.EVENT_BUS.addListener(KeyboardKeybinds::onScreenOpening);
        LOGGER.info("Keyboard Keybinds loaded: a Keyboard button sits on the Key Binds screen.");
    }

    /** Dev aid: -Dkeyboardkeybinds.devOpen=true opens the keyboard screen as soon as the title screen appears. */
    private static final boolean DEV_OPEN = Boolean.getBoolean("keyboardkeybinds.devOpen");
    private static boolean devOpened = false;

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (DEV_OPEN && !devOpened && event.getNewScreen() instanceof TitleScreen title) {
            devOpened = true;
            event.setNewScreen(new KeyboardScreen(title, Minecraft.getInstance().options));
        }
    }

    /** Adds the Keyboard button to the vanilla Key Binds screen and to replacements of it (Controlling's NewKeyBindsScreen). */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isKeyBindsScreen(screen)) return;
        // Top-right corner: clear of the centred title, Controlling's search bar and the bottom button rows.
        Button button = Button.builder(Component.translatable("keyboardkeybinds.button.keyboard"),
                        b -> Minecraft.getInstance().setScreen(new KeyboardScreen(screen, Minecraft.getInstance().options)))
                .bounds(screen.width - 82, 2, 78, 18) // ends at y=20: Controlling's search bar starts at 21, vanilla's list at 33
                .tooltip(Tooltip.create(Component.translatable("keyboardkeybinds.button.keyboard.tooltip")))
                .build();
        event.addListener(button);
        BUTTONS.put(screen, button);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        Button button = BUTTONS.get(event.getScreen());
        if (button != null) button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new KeyboardScreen(null, mc.options));
        }
    }

    /** Vanilla's screen, or a mod's replacement for it (Controlling's NewKeyBindsScreen is not a subclass). */
    static boolean isKeyBindsScreen(Screen screen) {
        return screen != null && (screen instanceof KeyBindsScreen || screen.getClass().getSimpleName().endsWith("KeyBindsScreen"));
    }
}
