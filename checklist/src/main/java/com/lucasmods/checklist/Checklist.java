package com.lucasmods.checklist;

import com.lucasmods.checklist.client.ChecklistClient;
import com.lucasmods.checklist.net.GridSyncPayload;
import com.lucasmods.checklist.net.SetRankPayload;
import com.lucasmods.checklist.net.SyncPayload;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Checklist: a challenge grid of curated advancements (Kirby Air Ride style). Every completed square
 * is one level and one point; points buy upgrade ranks that can be taken back at any time.
 * Squares are revealed when done, next to a done square, or listed as start squares.
 */
@Mod(Checklist.MODID)
public final class Checklist {
    public static final String MODID = "checklist";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Checklist(IEventBus modBus, ModContainer container) {
        modBus.addListener(Checklist::registerPayloads);
        NeoForge.EVENT_BUS.register(ChecklistServer.class);
        NeoForge.EVENT_BUS.register(ChecklistCommands.class);
        if (FMLEnvironment.dist.isClient()) {
            ChecklistClient.register(modBus);
        }
        LOGGER.info("Checklist loaded: achievements are the gate.");
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SetRankPayload.TYPE, SetRankPayload.CODEC, ChecklistServer::handleSetRank);
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ClientState.level = payload.level();
            ClientState.total = payload.total();
            ClientState.revealAll = payload.revealAll();
            ClientState.ranks = payload.ranks();
        }));
        registrar.playToClient(GridSyncPayload.TYPE, GridSyncPayload.CODEC, (payload, context) -> context.enqueueWork(() -> ClientState.squares = payload.squares()));
    }
}
