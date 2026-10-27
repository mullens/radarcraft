package com.lucasmods.radars;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * One entity class for every Radar: the plain tamed cat (#1), the six giant bosses (#2..#7),
 * their tamed-back-down versions, Radar Raider's kittens and Radiant Radar's mirror images.
 * Rendered with the vanilla cat model and tuxedo texture; size comes from the SCALE attribute.
 */
public final class RadarEntity extends Monster {
    private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(RadarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_TAMED = SynchedEntityData.defineId(RadarEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_CHARGING = SynchedEntityData.defineId(RadarEntity.class, EntityDataSerializers.BOOLEAN);

    private static final double LEASH_RANGE = 48;
    private static final int RESET_IDLE_TICKS = 400;
    private static final DustParticleOptions GREEN = new DustParticleOptions(new Vector3f(0.35f, 1.0f, 0.25f), 2.0f);

    /** 1 for the player-made Radar; 2..7 for bosses and their tamed cats; 0 for minions. */
    private int shrineIndex = 0;
    @Nullable private BlockPos homePos;
    @Nullable private UUID ownerId; // minions: the boss that summoned them
    private int lifetime = 0;        // minions: ticks alive

    private int cooldown = 0;
    private int secondaryCooldown = 0;
    private int teleportCooldown = 0;
    private int idleTicks = 0;
    private int bounces = 0;
    private int beamTicks = 0;
    private int mirrorCooldown = 0;
    private boolean pouncing = false;
    private boolean bouncing = false;
    private boolean slamming = false;
    private Vec3 lastMotion = Vec3.ZERO;
    private Vec3 beamDir = Vec3.ZERO;

    @Nullable private ServerBossEvent bossEvent;

    public RadarEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.ATTACK_DAMAGE, 4)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ARMOR, 0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0)
                .add(Attributes.FOLLOW_RANGE, 48)
                .add(Attributes.ATTACK_KNOCKBACK, 1)
                .add(Attributes.STEP_HEIGHT, 1.0)
                .add(Attributes.SCALE, 1.0);
    }

    /** Spawns a configured Radar at a block position. Returns null if the entity could not be created. */
    @Nullable
    public static RadarEntity spawn(ServerLevel level, RadarVariant variant, boolean tamed, BlockPos at, int shrineIndex) {
        RadarEntity radar = Radars.RADAR.get().create(level);
        if (radar == null) return null;
        radar.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        radar.shrineIndex = shrineIndex;
        radar.homePos = at;
        radar.applyVariant(variant, tamed);
        radar.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.MOB_SUMMONED, null);
        level.addFreshEntity(radar);
        return radar;
    }

    // ------------------------------------------------------------------ data

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, RadarVariant.RADAR.id);
        builder.define(DATA_TAMED, false);
        builder.define(DATA_CHARGING, false);
    }

    public RadarVariant getVariant() { return RadarVariant.byId(entityData.get(DATA_VARIANT)); }
    public boolean isTamed() { return entityData.get(DATA_TAMED); }
    public boolean isCharging() { return entityData.get(DATA_CHARGING); }
    private void setCharging(boolean charging) { entityData.set(DATA_CHARGING, charging); }
    public boolean isBoss() { return getVariant().isBoss() && !isTamed(); }
    /** Tamed cats sit; used by the model. */
    public boolean isSitting() { return isTamed(); }
    private boolean isPhaseTwo() { return getHealth() < getMaxHealth() / 2; }

    public void applyVariant(RadarVariant variant, boolean tamed) {
        entityData.set(DATA_VARIANT, variant.id);
        entityData.set(DATA_TAMED, tamed);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(tamed ? 20 : variant.health);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(variant.attack);
        getAttribute(Attributes.ARMOR).setBaseValue(variant.armor);
        getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(tamed ? 1.0 : variant.knockbackResistance);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(variant.speed);
        float scale = tamed ? 1.0f : variant.scale;
        getAttribute(Attributes.SCALE).setBaseValue(scale);
        getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6 + scale * 0.35);
        refreshDimensions();
        setHealth(getMaxHealth());
        setCustomName(Component.literal(variant.displayName));
        setCustomNameVisible(true);
        setPersistenceRequired();
        setNoAi(tamed);
        setInvulnerable(tamed);
        if (!tamed && variant.isBoss()) {
            bossEvent = new ServerBossEvent(getDisplayName(), variant.barColor, BossEvent.BossBarOverlay.NOTCHED_10);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Variant", getVariant().id);
        tag.putBoolean("Tamed", isTamed());
        tag.putInt("ShrineIndex", shrineIndex);
        if (homePos != null) tag.put("HomePos", NbtUtils.writeBlockPos(homePos));
        if (ownerId != null) tag.putUUID("OwnerBoss", ownerId);
        tag.putInt("Lifetime", lifetime);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        shrineIndex = tag.getInt("ShrineIndex");
        homePos = NbtUtils.readBlockPos(tag, "HomePos").orElse(null);
        ownerId = tag.hasUUID("OwnerBoss") ? tag.getUUID("OwnerBoss") : null;
        lifetime = tag.getInt("Lifetime");
        RadarVariant variant = RadarVariant.byId(tag.getInt("Variant"));
        boolean tamed = tag.getBoolean("Tamed");
        float health = getHealth();
        applyVariant(variant, tamed);
        setHealth(Math.min(health, getMaxHealth()));
    }

    // ------------------------------------------------------------------ AI

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 16.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (isTamed()) tickTamed((ServerLevel) level());
        else if (getVariant().isMinion()) tickMinion((ServerLevel) level());
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!isBoss()) return;
        ServerLevel level = (ServerLevel) level();
        if (bossEvent != null) bossEvent.setProgress(getHealth() / getMaxHealth());
        if (cooldown > 0) cooldown--;
        if (secondaryCooldown > 0) secondaryCooldown--;
        if (teleportCooldown > 0) teleportCooldown--;
        if (mirrorCooldown > 0) mirrorCooldown--;
        tickLeash(level);

        LivingEntity target = getTarget();
        switch (getVariant()) {
            case ROWDY -> tickRowdy(level, target);
            case RADIATION -> tickRadiation(level);
            case RICOCHET -> tickRicochet(level, target);
            case RAIDER -> tickRaider(level, target);
            case RUMBLING -> tickRumbling(level, target);
            case RADIANT -> tickRadiant(level, target);
            default -> {}
        }
        lastMotion = getDeltaMovement();
    }

    /** Bosses stay near their shrine and heal up when nobody has fought them for a while. */
    private void tickLeash(ServerLevel level) {
        if (homePos == null) return;
        Vec3 home = Vec3.atBottomCenterOf(homePos);
        if (distanceToSqr(home) > LEASH_RANGE * LEASH_RANGE) {
            teleportTo(home.x, home.y, home.z);
            getNavigation().stop();
            level.sendParticles(ParticleTypes.PORTAL, home.x, home.y + 1, home.z, 40, 1, 1, 1, 0.5);
        }
        boolean playerNear = !level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(LEASH_RANGE), p -> !p.isSpectator()).isEmpty();
        idleTicks = playerNear ? 0 : idleTicks + 1;
        if (idleTicks >= RESET_IDLE_TICKS && getHealth() < getMaxHealth()) {
            setHealth(getMaxHealth());
            idleTicks = 0;
        }
    }

    // --- #2 Rowdy Radar: pounces from range, zig-zag sprints, claws up close.
    private void tickRowdy(ServerLevel level, @Nullable LivingEntity target) {
        if (pouncing && onGround()) {
            pouncing = false;
            if (target != null && distanceTo(target) < 3.5 + getScale()) doHurtTarget(target);
            level.sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 20, getScale() * 0.5, 0.1, getScale() * 0.5, 0.05);
        }
        if (target != null && cooldown == 0 && onGround()) {
            double d = distanceTo(target);
            if (d > 4 && d < 14) {
                Vec3 dir = target.position().subtract(position());
                dir = new Vec3(dir.x, 0, dir.z).normalize().scale(1.4).add(0, 0.65, 0);
                setDeltaMovement(dir);
                hasImpulse = true;
                pouncing = true;
                playSound(SoundEvents.CAT_HISS, 2.0f, 0.6f);
                cooldown = isPhaseTwo() ? 50 : 90;
            }
        }
        if (secondaryCooldown == 0) {
            setSprinting(true);
            Vec3 look = getLookAngle();
            double side = random.nextBoolean() ? 1 : -1;
            push(-look.z * 0.35 * side, 0, look.x * 0.35 * side);
            secondaryCooldown = 100;
        } else if (secondaryCooldown == 70) {
            setSprinting(false);
        }
    }

    // --- #3 Radiation Radar: slow and tanky, pulses damage and leaves poison clouds.
    private void tickRadiation(ServerLevel level) {
        if (cooldown == 0) {
            cooldown = isPhaseTwo() ? 50 : 80;
            playSound(SoundEvents.CAT_HISS, 2.0f, 0.4f);
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI * 2 / 48;
                level.sendParticles(GREEN, getX() + Math.cos(a) * 8, getY() + 1, getZ() + Math.sin(a) * 8, 2, 0.2, 0.5, 0.2, 0);
            }
            for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(8), p -> !p.isSpectator() && !p.isCreative())) {
                p.hurt(damageSources().mobAttack(this), 6.0f);
                p.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0));
            }
        }
        if (tickCount % 20 == 0 && getDeltaMovement().horizontalDistanceSqr() > 0.002) {
            AreaEffectCloud cloud = new AreaEffectCloud(level, getX(), getY(), getZ());
            cloud.setOwner(this);
            cloud.setRadius(1.8f);
            cloud.setDuration(120);
            cloud.setRadiusPerTick(-0.012f);
            cloud.setPotionContents(new PotionContents(Potions.POISON));
            cloud.setParticle(GREEN);
            level.addFreshEntity(cloud);
        }
    }

    // --- #4 Ricochet Radar: launches itself and bounces off walls, spits bouncing hairballs.
    private void tickRicochet(ServerLevel level, @Nullable LivingEntity target) {
        if (bouncing) {
            if (horizontalCollision && bounces > 0) {
                bounces--;
                setDeltaMovement(-lastMotion.x * 0.9, 0.4, -lastMotion.z * 0.9);
                hasImpulse = true;
                playSound(SoundEvents.SLIME_BLOCK_FALL, 1.5f, 1.2f);
                level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() / 2, getZ(), 15, 0.5, 0.5, 0.5, 0.2);
            } else if (onGround() && getDeltaMovement().horizontalDistanceSqr() < 0.01 && tickCount % 5 == 0) {
                bouncing = false;
            }
        }
        if (target != null && cooldown == 0 && onGround()) {
            Vec3 dir = target.position().subtract(position());
            dir = new Vec3(dir.x, 0, dir.z).normalize().scale(1.7).add(0, 0.35, 0);
            setDeltaMovement(dir);
            hasImpulse = true;
            bouncing = true;
            bounces = 3;
            playSound(SoundEvents.CAT_STRAY_AMBIENT, 1.5f, 1.3f);
            cooldown = isPhaseTwo() ? 28 : 45;
        }
        if (target != null && secondaryCooldown == 0) {
            HairballEntity ball = new HairballEntity(level, this);
            Vec3 from = getEyePosition();
            Vec3 to = target.getEyePosition().subtract(from);
            ball.setPos(from.x, from.y - 0.3, from.z);
            ball.shoot(to.x, to.y + to.horizontalDistance() * 0.08, to.z, 1.3f, 4.0f);
            level.addFreshEntity(ball);
            playSound(SoundEvents.CAT_HISS, 1.0f, 1.5f);
            secondaryCooldown = isPhaseTwo() ? 40 : 65;
        }
    }

    // --- #5 Radar Raider: howls up kittens, blinks behind anyone who shoots it.
    private void tickRaider(ServerLevel level, @Nullable LivingEntity target) {
        if (target != null && cooldown == 0) {
            cooldown = isPhaseTwo() ? 160 : 260;
            playSound(SoundEvents.WOLF_HOWL, 3.0f, 0.5f);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + getBbHeight(), getZ(), 12, 1.5, 0.5, 1.5, 0);
            List<RadarEntity> kittens = ownedMinions(level, RadarVariant.KITTEN);
            for (RadarEntity k : kittens) k.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 300, 0));
            for (int i = kittens.size(); i < 6 && i < kittens.size() + 3; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                BlockPos at = BlockPos.containing(getX() + Math.cos(a) * 3, getY() + 0.5, getZ() + Math.sin(a) * 3);
                RadarEntity kitten = spawn(level, RadarVariant.KITTEN, false, at, 0);
                if (kitten != null) {
                    kitten.ownerId = getUUID();
                    kitten.setTarget(target);
                    level.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
    }

    // --- #6 Rumbling Radar: leaps and ground-slams; the shockwave throws players around.
    private void tickRumbling(ServerLevel level, @Nullable LivingEntity target) {
        if (slamming && onGround() && secondaryCooldown == 0) {
            slamming = false;
            slam(level);
        }
        if (!slamming && target != null && cooldown == 0 && onGround() && distanceTo(target) < 14) {
            setDeltaMovement(getDeltaMovement().x * 0.5, 1.25, getDeltaMovement().z * 0.5);
            hasImpulse = true;
            slamming = true;
            secondaryCooldown = 8; // airborne grace before the landing check
            playSound(SoundEvents.RAVAGER_ROAR, 2.0f, 0.6f);
            cooldown = isPhaseTwo() ? 60 : 120;
        }
    }

    private void slam(ServerLevel level) {
        level.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0f, 0.7f);
        ParticleOptions debris = new BlockParticleOption(ParticleTypes.BLOCK, getBlockStateOn());
        for (int ring = 2; ring <= 7; ring++) {
            for (int i = 0; i < ring * 6; i++) {
                double a = i * Math.PI * 2 / (ring * 6);
                level.sendParticles(debris, getX() + Math.cos(a) * ring, getY() + 0.2, getZ() + Math.sin(a) * ring, 3, 0.2, 0.3, 0.2, 0.1);
            }
        }
        for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(10), p -> !p.isSpectator() && !p.isCreative())) {
            Vec3 away = p.position().subtract(position());
            double d = away.horizontalDistance();
            if (d <= 7) {
                Vec3 dir = new Vec3(away.x, 0, away.z).normalize();
                p.push(dir.x * 1.2, 0.9, dir.z * 1.2);
                p.hurtMarked = true;
                p.hurt(damageSources().mobAttack(this), 10.0f);
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
            } else {
                p.push((random.nextDouble() - 0.5) * 0.3, 0.25, (random.nextDouble() - 0.5) * 0.3);
                p.hurtMarked = true;
            }
        }
    }

    // --- #7 Radiant Radar: telegraphed laser, blinding flash, mirror images, a shield that opens while charging.
    private void tickRadiant(ServerLevel level, @Nullable LivingEntity target) {
        if (beamTicks > 0) {
            beamTicks--;
            Vec3 from = getEyePosition();
            if (beamTicks > 10) {
                if (beamTicks % 4 == 0) lineParticles(level, from, beamDir, 24, ParticleTypes.ELECTRIC_SPARK, 1.5);
            } else if (beamTicks == 10) {
                setCharging(false);
                lineParticles(level, from, beamDir, 24, ParticleTypes.END_ROD, 0.4);
                level.playSound(null, blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 2.0f, 1.6f);
                for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(26), p -> !p.isSpectator() && !p.isCreative())) {
                    if (distanceToSegment(p.getEyePosition(), from, from.add(beamDir.scale(24))) < 1.6) {
                        p.hurt(damageSources().mobAttack(this), 12.0f);
                        p.igniteForSeconds(3);
                    }
                }
            }
        } else if (target != null && cooldown == 0) {
            beamDir = target.getEyePosition().subtract(getEyePosition()).normalize();
            beamTicks = 30;
            setCharging(true);
            playSound(SoundEvents.CAT_PURREOW, 2.0f, 0.5f);
            cooldown = isPhaseTwo() ? 70 : 110;
        }
        if (target != null && secondaryCooldown == 0) {
            secondaryCooldown = 300;
            level.sendParticles(ParticleTypes.FLASH, getX(), getY() + getBbHeight() / 2, getZ(), 3, 0, 0, 0, 0);
            playSound(SoundEvents.CAT_STRAY_AMBIENT, 2.0f, 2.0f);
            for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(12), p -> !p.isSpectator() && !p.isCreative())) {
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0));
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0));
            }
        }
        if (target != null && mirrorCooldown == 0 && ownedMinions(level, RadarVariant.MIRROR).isEmpty()) {
            mirrorCooldown = 400;
            for (int side = -1; side <= 1; side += 2) {
                BlockPos at = BlockPos.containing(getX() + side * 4, getY(), getZ());
                RadarEntity mirror = spawn(level, RadarVariant.MIRROR, false, at, 0);
                if (mirror != null) {
                    mirror.ownerId = getUUID();
                    mirror.setTarget(target);
                    level.sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 20, 1, 1, 1, 0.05);
                }
            }
        }
    }

    private void lineParticles(ServerLevel level, Vec3 from, Vec3 dir, double length, ParticleOptions particle, double spacing) {
        for (double d = 1; d <= length; d += spacing) {
            Vec3 p = from.add(dir.scale(d));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double t = Mth.clamp(p.subtract(a).dot(ab) / ab.lengthSqr(), 0, 1);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private List<RadarEntity> ownedMinions(ServerLevel level, RadarVariant variant) {
        UUID me = getUUID();
        return (List<RadarEntity>) level.getEntities(EntityTypeTest.forClass(RadarEntity.class),
                r -> r.getVariant() == variant && me.equals(r.ownerId) && r.isAlive());
    }

    private void tickMinion(ServerLevel level) {
        lifetime++;
        if (tickCount % 20 == 0) {
            Entity owner = ownerId == null ? null : level.getEntity(ownerId);
            boolean ownerGone = owner == null || !owner.isAlive();
            int maxLife = getVariant() == RadarVariant.MIRROR ? 600 : 2400;
            if (ownerGone || lifetime > maxLife) {
                level.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.5, getZ(), 10, 0.3, 0.3, 0.3, 0.05);
                discard();
            }
        }
    }

    // ------------------------------------------------------------------ tamed cats: sit and point

    private void tickTamed(ServerLevel level) {
        Vec3 target = pointTarget(level);
        if (target == null) return;
        double dx = target.x - getX();
        double dz = target.z - getZ();
        float yaw = (float) Math.toDegrees(Mth.atan2(-dx, dz));
        setYRot(yaw);
        yRotO = yaw;
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        yHeadRotO = yaw;
        if (tickCount % 60 == 0) {
            Vec3 dir = new Vec3(dx, 0, dz).normalize();
            for (int i = 1; i <= 10; i++) {
                Vec3 p = position().add(dir.scale(i * 0.7)).add(0, 0.6, 0);
                level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }
    }

    /** Where this cat points: the next shrine, or world spawn once Radar #7 has been tamed. */
    @Nullable
    private Vec3 pointTarget(ServerLevel level) {
        int next = shrineIndex + 1;
        if (next > RadarVariant.LAST_SHRINE) return Vec3.atCenterOf(level.getServer().overworld().getSharedSpawnPos());
        RadarsData.Shrine shrine = RadarsData.get(level).get(next);
        return shrine == null ? null : shrine.pointTarget();
    }

    public static String compass(double dx, double dz) {
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        if (bearing < 0) bearing += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(bearing / 45) % 8];
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isTamed()) return super.mobInteract(player, hand);
        if (!level().isClientSide()) {
            ServerLevel level = (ServerLevel) level();
            String message;
            if (shrineIndex >= RadarVariant.LAST_SHRINE) {
                message = getName().getString() + " is at peace. All the Radars have been tamed.";
            } else {
                Vec3 target = pointTarget(level);
                if (target == null) {
                    message = getName().getString() + " looks around, unsure.";
                } else {
                    double dx = target.x - getX(), dz = target.z - getZ();
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    String name = RadarVariant.byId(shrineIndex + 1).displayName;
                    message = String.format("%s stares %s, about %d blocks away. %s waits there.",
                            getName().getString(), compass(dx, dz), Math.round(distance / 10.0) * 10, name);
                }
            }
            player.sendSystemMessage(Component.literal(message));
            playSound(SoundEvents.CAT_PURR, 1.0f, 1.0f);
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    // ------------------------------------------------------------------ damage, death, defeat

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (isTamed()) return !source.is(DamageTypes.GENERIC_KILL);
        return super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isBoss() && !level().isClientSide()) {
            RadarVariant v = getVariant();
            if (v == RadarVariant.RADIANT && isPhaseTwo() && !isCharging() && !source.is(DamageTypes.GENERIC_KILL)) {
                playSound(SoundEvents.SHIELD_BLOCK, 1.5f, 0.7f);
                ((ServerLevel) level()).sendParticles(ParticleTypes.END_ROD, getX(), getY() + getBbHeight() / 2, getZ(), 12, getScale() * 0.4, getScale() * 0.4, getScale() * 0.4, 0.02);
                return false;
            }
            boolean result = super.hurt(source, amount);
            if (result && v == RadarVariant.RAIDER && teleportCooldown == 0 && source.getDirectEntity() instanceof Projectile
                    && source.getEntity() instanceof LivingEntity attacker) {
                blinkBehind((ServerLevel) level(), attacker);
                teleportCooldown = 60;
            }
            return result;
        }
        return super.hurt(source, amount);
    }

    private void blinkBehind(ServerLevel level, LivingEntity attacker) {
        Vec3 behind = attacker.position().subtract(attacker.getLookAngle().multiply(1, 0, 1).normalize().scale(2.5 + getScale() * 0.5));
        level.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 1, getZ(), 40, 1, 1, 1, 0.5);
        teleportTo(behind.x, attacker.getY(), behind.z);
        getNavigation().stop();
        level.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 1, getZ(), 40, 1, 1, 1, 0.5);
        level.playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.6f);
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (!level().isClientSide() && isBoss() && !isRemoved()) {
            onBossDefeated((ServerLevel) level());
        }
    }

    private void onBossDefeated(ServerLevel level) {
        RadarVariant variant = getVariant();
        BlockPos at = homePos != null ? homePos : blockPosition();
        RadarEntity cat = spawn(level, variant, true, at, shrineIndex);
        for (RadarEntity minion : ownedMinions(level, RadarVariant.KITTEN)) minion.discard();
        for (RadarEntity minion : ownedMinions(level, RadarVariant.MIRROR)) minion.discard();
        placeLootChest(level, at, variant);
        if (shrineIndex >= RadarVariant.FIRST_SHRINE) {
            RadarsData data = RadarsData.get(level);
            RadarsData.Shrine shrine = data.get(shrineIndex);
            if (shrine != null) {
                shrine.defeated = true;
                shrine.bossId = null;
                shrine.catId = cat == null ? null : cat.getUUID();
                data.setDirty();
            }
        }
        String next = shrineIndex >= RadarVariant.LAST_SHRINE ? "Radar is at peace." : "It now points toward " + RadarVariant.byId(shrineIndex + 1).displayName + ".";
        Component message = Component.literal(variant.displayName + " has been tamed back down. " + next);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(this) < 96 * 96) p.sendSystemMessage(message);
        }
        Radars.LOGGER.info("{} defeated at {}; tamed cat {} spawned", variant.displayName, at.toShortString(), cat == null ? "NOT" : cat.getUUID());
    }

    /**
     * Puts a loot chest beside the shrine centre (never on the lodestone) carrying only a LootTable tag, so
     * loot mods such as Lootr can turn it into a per-player chest. Prefers a free block with solid ground.
     */
    private void placeLootChest(ServerLevel level, BlockPos centre, RadarVariant variant) {
        BlockPos chosen = null;
        BlockPos fallback = null;
        for (int ring = 1; ring <= 3 && chosen == null; ring++) {
            for (BlockPos candidate : ringAround(centre, ring)) {
                if (!level.getBlockState(candidate).canBeReplaced()) continue;
                if (fallback == null) fallback = candidate;
                if (level.getBlockState(candidate.below()).isSolid()) {
                    chosen = candidate;
                    break;
                }
            }
        }
        if (chosen == null) chosen = fallback != null ? fallback : centre.east();
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.getNearest(centre.getX() - chosen.getX(), 0, centre.getZ() - chosen.getZ());
        if (facing.getAxis().isVertical()) facing = net.minecraft.core.Direction.NORTH;
        level.setBlock(chosen, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing), 3);
        if (level.getBlockEntity(chosen) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
            ResourceLocation table = ResourceLocation.fromNamespaceAndPath(Radars.MODID, "chests/radar_" + variant.id);
            chest.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table), level.random.nextLong());
            chest.setChanged();
            Radars.LOGGER.info("Loot chest for {} placed at {} with loot table {}", variant.displayName, chosen.toShortString(), table);
        }
    }

    /** The blocks at Chebyshev distance {@code ring} around a centre, on the centre's level, cardinal neighbours first. */
    private static List<BlockPos> ringAround(BlockPos centre, int ring) {
        List<BlockPos> list = new java.util.ArrayList<>();
        if (ring == 1) {
            list.add(centre.east()); list.add(centre.west()); list.add(centre.south()); list.add(centre.north());
        }
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                BlockPos p = centre.offset(dx, 0, dz);
                if (!list.contains(p)) list.add(p);
            }
        }
        return list;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        if (isBoss()) {
            ItemStack whisker = new ItemStack(Items.NAME_TAG);
            whisker.set(DataComponents.CUSTOM_NAME, Component.literal("Whisker of " + getVariant().displayName));
            spawnAtLocation(whisker);
        }
    }

    @Override
    protected int getBaseExperienceReward() {
        return isTamed() ? 0 : getVariant().xp;
    }

    // ------------------------------------------------------------------ misc overrides

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (bossEvent != null && isBoss()) bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (bossEvent != null) bossEvent.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (bossEvent != null) bossEvent.removeAllPlayers();
    }

    @Override
    public void checkDespawn() {
        // Radars never despawn; minions clean themselves up in tickMinion.
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return !isTamed() && super.isPushable();
    }

    @Override
    public boolean isCurrentlyGlowing() {
        RadarVariant v = getVariant();
        return !isTamed() && (v == RadarVariant.RADIATION || v == RadarVariant.RADIANT || v == RadarVariant.MIRROR) || super.isCurrentlyGlowing();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return isTamed() ? SoundEvents.CAT_PURREOW : SoundEvents.CAT_STRAY_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.CAT_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.CAT_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return isTamed() ? 1.1f : Math.max(0.35f, 1.4f / getScale());
    }
}
