package com.lucasmods.checklist.client;

import com.lucasmods.checklist.Checklist;
import com.lucasmods.checklist.ClientState;
import com.lucasmods.checklist.net.DoubleJumpPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/** Client wiring: the keybind, the Radarcraft button on the Advancements screen, double jump. */
public final class ChecklistClient {
    /** Default M: free in the Skybound pack (the keyboard screen showed it unbound); rebind in Key Binds. */
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.checklist.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_M, "key.categories.checklist");
    /** Double Jump: whether the jump key was down last tick, and whether the air jump is spent. */
    private static boolean jumpWasDown;
    private static boolean airJumpUsed;

    private ChecklistClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_KEY));
        NeoForge.EVENT_BUS.addListener(ChecklistClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ChecklistClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ChecklistClient::onScreenRender);
    }

    /** Buttons we added, per screen; some mod screens (Better Advancements) paint over foreign widgets, so we redraw them last. */
    private static final java.util.Map<Screen, Button> BUTTONS = new java.util.WeakHashMap<>();

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new ChecklistScreen(null));
        }
        doubleJump(mc);
    }

    /** Double Jump: a fresh jump press in mid-air jumps again, once per airtime. The server validates and clears fall damage. */
    private static void doubleJump(Minecraft mc) {
        var player = mc.player;
        if (player == null) return;
        boolean down = mc.options.keyJump.isDown();
        boolean grounded = player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()
                || player.getAbilities().flying || player.isFallFlying() || player.isPassenger();
        if (grounded) {
            airJumpUsed = false;
        } else if (down && !jumpWasDown && !airJumpUsed && mc.screen == null && ClientState.rank("double_jump") > 0) {
            airJumpUsed = true;
            player.jumpFromGround();
            PacketDistributor.sendToServer(DoubleJumpPayload.INSTANCE);
        }
        jumpWasDown = down;
    }

    /**
     * Adds a Radarcraft button to the vanilla Advancements screen and to mod replacements of it
     * (Better Advancements' BetterAdvancementsScreen...). Bottom-right corner, clear of their tab rows.
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof ChecklistScreen) return;
        if (!(screen instanceof AdvancementsScreen) && !screen.getClass().getSimpleName().contains("Advancements")) return;
        Button button = Button.builder(Component.literal("Radarcraft"), b -> Minecraft.getInstance().setScreen(new ChecklistScreen(screen)))
                .bounds(screen.width - 74, screen.height - 24, 70, 20).build();
        event.addListener(button);
        BUTTONS.put(screen, button);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        Button button = BUTTONS.get(event.getScreen());
        if (button != null) button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ChecklistScreen(Minecraft.getInstance().screen));
    }

    static {
        Checklist.LOGGER.debug("Checklist client loaded");
    }
}
