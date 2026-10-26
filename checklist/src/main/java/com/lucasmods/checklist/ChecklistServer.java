package com.lucasmods.checklist;

import com.lucasmods.checklist.net.GridSyncPayload;
import com.lucasmods.checklist.net.SetRankPayload;
import com.lucasmods.checklist.net.SyncPayload;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** All server-side rules: levels from advancements, points, upgrade application, rewards, specials. */
public final class ChecklistServer {
    private static final String HEARTS_MODIFIER = "levels";
    private static final int EFFECT_REFRESH_TICKS = 40;
    private static final int EFFECT_DURATION_TICKS = 15 * 20;
    private static final long SECOND_WIND_COOLDOWN = 5 * 60 * 20;
    private static final Set<ResourceLocation> warnedIds = new HashSet<>();
    private static final Map<ServerPlayer, Boolean> revealAll = new WeakHashMap<>();

    private ChecklistServer() {}

    // ------------------------------------------------------------------ level

    /** Known grid squares: ids that resolve to a real advancement on this server. */
    public static List<AdvancementHolder> knownSquares(MinecraftServer server, GridConfig grid) {
        List<AdvancementHolder> list = new ArrayList<>();
        for (GridConfig.Cell cell : grid.cells()) {
            AdvancementHolder holder = server.getAdvancements().get(cell.id());
            if (holder == null) {
                if (warnedIds.add(cell.id())) Checklist.LOGGER.warn("Grid square {} is not a known advancement; treating it as empty", cell.id());
                continue;
            }
            list.add(holder);
        }
        return list;
    }

    public static int total(ServerPlayer player) {
        return knownSquares(player.server, ChecklistConfig.grid()).size();
    }

    public static int level(ServerPlayer player) {
        int n = 0;
        for (AdvancementHolder holder : knownSquares(player.server, ChecklistConfig.grid())) {
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ apply + sync

    /** Re-derives every modifier from the stored ranks and the current level. Safe to call often. */
    public static void apply(ServerPlayer player) {
        GridConfig grid = ChecklistConfig.grid();
        UpgradeCatalog catalog = ChecklistConfig.upgrades();
        PlayerState state = PlayerState.load(player);
        int level = level(player);
        boolean changed = state.enforceBudget(level);
        // drop ranks of upgrades that no longer exist or exceed their max
        for (String id : new ArrayList<>(state.ranks.keySet())) {
            UpgradeCatalog.Upgrade u = catalog.get(id);
            if (u == null) { state.setRank(id, 0); changed = true; }
            else if (state.rank(id) > u.ranks) { state.setRank(id, u.ranks); changed = true; }
        }
        if (changed) state.save(player);

        for (UpgradeCatalog.Upgrade u : catalog.all().values()) {
            int rank = state.rank(u.id);
            for (int i = 0; i < u.modifiers.size(); i++) {
                UpgradeCatalog.Modifier m = u.modifiers.get(i);
                AttributeInstance instance = attribute(player, m.attribute());
                if (instance == null) continue;
                ResourceLocation id = u.modifierId(i);
                if (rank > 0) instance.addOrUpdateTransientModifier(new AttributeModifier(id, m.amountPerRank() * rank, m.operation()));
                else instance.removeModifier(id);
            }
            if (rank == 0) {
                for (UpgradeCatalog.Effect e : u.effects) removeEffect(player, e);
                for (UpgradeCatalog.Effect e : u.effectsAtMax) removeEffect(player, e);
            } else if (rank < u.ranks) {
                for (UpgradeCatalog.Effect e : u.effectsAtMax) removeEffect(player, e);
            }
        }
        // legacy hearts-per-levels reward
        AttributeInstance health = attribute(player, ResourceLocation.withDefaultNamespace("generic.max_health"));
        if (health != null) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Checklist.MODID, HEARTS_MODIFIER);
            int hearts = grid.heartsEveryLevels > 0 ? level / grid.heartsEveryLevels : 0;
            if (hearts > 0) health.addOrUpdateTransientModifier(new AttributeModifier(id, 2.0 * hearts, AttributeModifier.Operation.ADD_VALUE));
            else health.removeModifier(id);
        }
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        refreshEffects(player, state, catalog);
    }

    private static void refreshEffects(ServerPlayer player, PlayerState state, UpgradeCatalog catalog) {
        for (UpgradeCatalog.Upgrade u : catalog.all().values()) {
            int rank = state.rank(u.id);
            if (rank <= 0) continue;
            for (UpgradeCatalog.Effect e : u.effects) addEffect(player, e, e.amplifier() + rank - 1);
            if (rank >= u.ranks) for (UpgradeCatalog.Effect e : u.effectsAtMax) addEffect(player, e, e.amplifier());
        }
    }

    private static void addEffect(ServerPlayer player, UpgradeCatalog.Effect e, int amplifier) {
        Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(e.effect()).orElse(null);
        if (effect == null) return;
        MobEffectInstance current = player.getEffect(effect);
        if (current != null && current.getAmplifier() > amplifier) return; // a stronger potion is active; leave it
        player.addEffect(new MobEffectInstance(effect, EFFECT_DURATION_TICKS, amplifier, true, false, false));
    }

    private static void removeEffect(ServerPlayer player, UpgradeCatalog.Effect e) {
        Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(e.effect()).orElse(null);
        if (effect == null) return;
        MobEffectInstance current = player.getEffect(effect);
        if (current != null && current.isAmbient() && !current.isVisible()) player.removeEffect(effect); // only ours look like this
    }

    private static AttributeInstance attribute(ServerPlayer player, ResourceLocation id) {
        Holder<Attribute> holder = BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null);
        if (holder == null) {
            if (warnedIds.add(id)) Checklist.LOGGER.warn("Unknown attribute {} in checklist-upgrades.json", id);
            return null;
        }
        return player.getAttribute(holder);
    }

    public static void sync(ServerPlayer player) {
        PlayerState state = PlayerState.load(player);
        PacketDistributor.sendToPlayer(player, new SyncPayload(level(player), total(player), revealAll.getOrDefault(player, false), state.ranks));
    }

    /** Sends the whole grid with display data and done flags (login, advancement earned, reload). */
    public static void syncGrid(ServerPlayer player) {
        GridConfig grid = ChecklistConfig.grid();
        List<GridSyncPayload.Square> squares = new ArrayList<>();
        for (GridConfig.Cell cell : grid.cells()) {
            AdvancementHolder holder = player.server.getAdvancements().get(cell.id());
            if (holder == null) continue; // unknown: treated as empty
            boolean done = player.getAdvancements().getOrStartProgress(holder).isDone();
            var display = holder.value().display().orElse(null);
            squares.add(new GridSyncPayload.Square(cell.x(), cell.y(), cell.id().toString(),
                    display != null ? display.getTitle() : Component.literal(cell.id().toString()),
                    display != null ? display.getDescription() : Component.empty(),
                    display != null ? display.getIcon() : net.minecraft.world.item.ItemStack.EMPTY,
                    display != null ? display.getType().ordinal() : 0, done));
        }
        PacketDistributor.sendToPlayer(player, new GridSyncPayload(squares));
    }

    public static void applyAndSync(ServerPlayer player) {
        apply(player);
        sync(player);
        syncGrid(player);
    }

    public static void setRevealAll(ServerPlayer player, boolean reveal) {
        revealAll.put(player, reveal);
        sync(player);
    }

    // ------------------------------------------------------------------ rank changes

    public static void handleSetRank(SetRankPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                String result = setRank(player, payload.upgradeId(), payload.rank());
                if (result != null) player.sendSystemMessage(Component.literal(result));
                apply(player);
                sync(player);
            }
        });
    }

    /** Validates and stores a rank change. Returns a message for the player on refusal, else null. */
    public static String setRank(ServerPlayer player, String upgradeId, int rank) {
        UpgradeCatalog catalog = ChecklistConfig.upgrades();
        UpgradeCatalog.Upgrade u = catalog.get(upgradeId);
        if (u == null) return "Unknown upgrade " + upgradeId;
        rank = Math.max(0, Math.min(rank, u.ranks));
        PlayerState state = PlayerState.load(player);
        int current = state.rank(upgradeId);
        if (rank == current) return null;
        if (rank > current) {
            int available = level(player) - state.spent();
            if (rank - current > available) return "Not enough points";
            for (String other : u.excludes) if (state.rank(other) > 0) return u.name + " cannot be combined with " + catalog.get(other).name;
            for (UpgradeCatalog.Upgrade o : catalog.all().values()) {
                if (o.excludes.contains(upgradeId) && state.rank(o.id) > 0) return u.name + " cannot be combined with " + o.name;
            }
        }
        state.setRank(upgradeId, rank);
        state.save(player);
        return null;
    }

    public static void respec(ServerPlayer player) {
        PlayerState state = PlayerState.load(player);
        state.ranks.clear();
        state.order.clear();
        state.save(player);
        applyAndSync(player);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        GridConfig grid = ChecklistConfig.grid();
        AdvancementHolder holder = event.getAdvancement();
        if (!grid.contains(holder.id())) return;
        int before = level(player) - 1; // this advancement is already done when the event fires
        int level = level(player);
        int total = total(player);
        String title = holder.value().display().map(d -> d.getTitle().getString()).orElse(holder.id().toString());
        player.sendSystemMessage(Component.literal(String.format("Level %d / %d: %s", level, total, title)));
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1.0f);
        if (grid.clearVanillaXpRewards) {
            int xp = holder.value().rewards().experience();
            if (xp > 0) player.giveExperiencePoints(-xp);
        }
        runLevelCommands(player, grid, before, level);
        applyAndSync(player);
    }

    private static void runLevelCommands(ServerPlayer player, GridConfig grid, int before, int level) {
        if (grid.commandsOnLevel.isEmpty()) return;
        PlayerState state = PlayerState.load(player);
        boolean changed = false;
        for (Map.Entry<Integer, List<String>> e : grid.commandsOnLevel.entrySet()) {
            int at = e.getKey();
            if (at <= before || at > level || state.rewardedLevels.contains(at)) continue;
            for (String command : e.getValue()) {
                String cmd = command.replace("@s", player.getGameProfile().getName()).replace("＠s", player.getGameProfile().getName());
                player.server.getCommands().performPrefixedCommand(player.server.createCommandSourceStack().withSuppressedOutput(), cmd);
            }
            state.rewardedLevels.add(at);
            changed = true;
        }
        if (changed) state.save(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) applyAndSync(player);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) apply(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) applyAndSync(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerState state = null;
        if (player.tickCount % EFFECT_REFRESH_TICKS == 0) {
            state = PlayerState.load(player);
            if (!state.ranks.isEmpty()) refreshEffects(player, state, ChecklistConfig.upgrades());
        }
        // magnet: pull nearby item entities toward the player
        int magnet = (state != null ? state : PlayerState.load(player)).rank("magnet");
        if (magnet > 0 && player.tickCount % 2 == 0) {
            double radius = 1.5 * magnet;
            ServerLevel level = player.serverLevel();
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(radius), i -> !i.hasPickUpDelay())) {
                Vec3 dir = player.position().add(0, 0.8, 0).subtract(item.position());
                double d = dir.length();
                if (d < 0.5 || d > radius) continue;
                item.setDeltaMovement(item.getDeltaMovement().scale(0.5).add(dir.normalize().scale(0.35)));
                item.hasImpulse = true;
            }
        }
    }

    /** Second Wind: a lethal hit leaves you at one health instead, once every five minutes. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getAmount() < player.getHealth()) return;
        if (event.getSource().is(DamageTypes.GENERIC_KILL)) return;
        if (trySecondWind(player)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSource().is(DamageTypes.GENERIC_KILL)) return;
        if (trySecondWind(player)) event.setCanceled(true);
    }

    private static boolean trySecondWind(ServerPlayer player) {
        PlayerState state = PlayerState.load(player);
        if (state.rank("second_wind") <= 0) return false;
        long now = player.level().getGameTime();
        if (now < state.secondWindReadyAt) return false;
        state.secondWindReadyAt = now + SECOND_WIND_COOLDOWN;
        state.save(player);
        player.setHealth(1.0f);
        player.level().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        player.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 60, 0.5, 0.8, 0.5, 0.3);
        player.sendSystemMessage(Component.literal("Second Wind! You cling to life. (5 minute cooldown)"));
        return true;
    }
}
