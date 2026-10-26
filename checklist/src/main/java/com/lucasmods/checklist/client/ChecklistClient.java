package com.lucasmods.checklist.client;

import com.lucasmods.checklist.Checklist;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/** Client wiring: the keybind, the button on the vanilla Advancements screen. */
public final class ChecklistClient {
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.checklist.open", KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN, "key.categories.checklist");

    private ChecklistClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_KEY));
        NeoForge.EVENT_BUS.addListener(ChecklistClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ChecklistClient::onScreenInit);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new ChecklistScreen(null));
        }
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AdvancementsScreen screen)) return;
        event.addListener(Button.builder(Component.literal("Checklist"), b -> Minecraft.getInstance().setScreen(new ChecklistScreen(screen)))
                .bounds(screen.width - 74, 4, 70, 20).build());
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ChecklistScreen(Minecraft.getInstance().screen));
    }

    static {
        Checklist.LOGGER.debug("Checklist client loaded");
    }
}
