package com.lucasmods.radars;

import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Ricochet Radar's projectile: bounces off blocks up to three times before fizzling. */
public final class HairballEntity extends ThrowableItemProjectile {
    private static final int MAX_BOUNCES = 3;
    private static final float DAMAGE = 5.0f;
    private int bounces = 0;
    private int life = 0;

    public HairballEntity(EntityType<? extends ThrowableItemProjectile> type, Level level) {
        super(type, level);
    }

    public HairballEntity(Level level, LivingEntity shooter) {
        super(Radars.HAIRBALL.get(), shooter, level);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.LIGHT_GRAY_WOOL;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && ++life > 120) discard();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level().isClientSide()) return;
        if (result.getEntity() == getOwner()) return;
        result.getEntity().hurt(damageSources().thrown(this, getOwner()), DAMAGE);
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (level().isClientSide()) return;
        if (bounces >= MAX_BOUNCES) {
            discard();
            return;
        }
        bounces++;
        Vec3 v = getDeltaMovement();
        Direction.Axis axis = result.getDirection().getAxis();
        Vec3 reflected = switch (axis) {
            case X -> new Vec3(-v.x, v.y, v.z);
            case Y -> new Vec3(v.x, -v.y, v.z);
            case Z -> new Vec3(v.x, v.y, -v.z);
        };
        setDeltaMovement(reflected.scale(0.8));
        hasImpulse = true;
        level().playSound(null, blockPosition(), SoundEvents.SLIME_BLOCK_FALL, SoundSource.HOSTILE, 1.0f, 1.4f);
    }
}
