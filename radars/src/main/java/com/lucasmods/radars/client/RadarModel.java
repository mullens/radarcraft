package com.lucasmods.radars.client;

import com.lucasmods.radars.RadarEntity;
import net.minecraft.client.model.OcelotModel;
import net.minecraft.client.model.geom.ModelPart;

/** The vanilla cat/ocelot mesh, with the cat's sitting pose applied for tamed Radars. */
public final class RadarModel extends OcelotModel<RadarEntity> {
    public RadarModel(ModelPart root) {
        super(root);
    }

    @Override
    public void prepareMobModel(RadarEntity entity, float limbSwing, float limbSwingAmount, float partialTick) {
        super.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
        if (entity.isSitting()) {
            // same offsets the vanilla CatModel uses for a sitting cat
            this.body.xRot = (float) (Math.PI / 4);
            this.body.y += -4.0F;
            this.body.z += 5.0F;
            this.head.y += -3.3F;
            this.head.z++;
            this.tail1.y += 8.0F;
            this.tail1.z += -2.0F;
            this.tail2.y += 2.0F;
            this.tail2.z += -0.8F;
            this.tail1.xRot = 1.7278761F;
            this.tail2.xRot = 2.670354F;
            this.leftFrontLeg.xRot = (float) (-Math.PI / 20);
            this.leftFrontLeg.y = 16.1F;
            this.leftFrontLeg.z = -7.0F;
            this.rightFrontLeg.xRot = (float) (-Math.PI / 20);
            this.rightFrontLeg.y = 16.1F;
            this.rightFrontLeg.z = -7.0F;
            this.leftHindLeg.xRot = (float) (-Math.PI / 2);
            this.leftHindLeg.y = 21.0F;
            this.leftHindLeg.z = 1.0F;
            this.rightHindLeg.xRot = (float) (-Math.PI / 2);
            this.rightHindLeg.y = 21.0F;
            this.rightHindLeg.z = 1.0F;
            this.state = 3;
        }
    }
}
