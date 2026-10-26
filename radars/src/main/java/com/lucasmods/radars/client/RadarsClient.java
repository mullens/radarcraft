package com.lucasmods.radars.client;

import com.lucasmods.radars.Radars;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Radars.MODID, value = Dist.CLIENT)
public final class RadarsClient {
    private RadarsClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Radars.RADAR.get(), RadarRenderer::new);
        event.registerEntityRenderer(Radars.HAIRBALL.get(), ThrownItemRenderer::new);
    }
}
