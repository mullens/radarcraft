package com.lucasmods.radars.client;

import com.lucasmods.radars.RadarEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Vanilla tuxedo-cat texture on the vanilla cat mesh; size comes from the entity's SCALE attribute. */
public final class RadarRenderer extends MobRenderer<RadarEntity, RadarModel> {
    private static final ResourceLocation TUXEDO = ResourceLocation.withDefaultNamespace("textures/entity/cat/black.png");

    public RadarRenderer(EntityRendererProvider.Context context) {
        super(context, new RadarModel(context.bakeLayer(ModelLayers.CAT)), 0.4f);
    }

    @Override
    public ResourceLocation getTextureLocation(RadarEntity entity) {
        return TUXEDO;
    }
}
