package com.lucasmods.checklist;

import com.lucasmods.checklist.net.GridSyncPayload;
import com.lucasmods.checklist.net.SetRankPayload;
import com.lucasmods.checklist.net.SyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side rules: square level from advancements, Renown from lifetime XP, points (squares + Renown), upgrade
 * application, rewards, and the retroactive baseline for advancements earned before the mod was installed.
 * The code-backed upgrades live in {@link Specials}.
 */
public final class ChecklistServer {
    private static final String HEARTS_MODIFIER = "levels";
    private static final int EFFECT_DURATION_TICKS = 15 * 20;
    private static final int SYNC_FLUSH_TICKS = 10;
    /** Retroactive Renown seed: XP credited per recorded mob kill (an approximation of past kill XP). */
    public static final int RENOWN_SEED_PER_KILL = 5;
    private static final Set<ResourceLocation> warnedIds = new HashSet<>();
    private static final Map<ServerPlayer, Boolean> revealAll = new WeakHashMap<>();
    /** Players whose Renown progress changed since the last sync. */
    private static final Set<ServerPlayer> syncDirty = Collections.newSetFromMap(new WeakHashMap<>());
    /** Each online player's party as last seen, to notice joins and leaves (FTB Teams is only read, never hooked). */
    private static final Map<ServerPlayer, String> teamKeys = new WeakHashMap<>();
    private static final int TEAM_CHECK_TICKS = 40;

    private ChecklistServer() {}

    // ------------------------------------------------------------------ squares, Renown, points

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

    /** Done grid squares (the square level), the party's included. */
    public static int level(ServerPlayer player) {
        return teamSquares(player).size();
    }

    /**
     * Done grid squares that count for this player: their own, plus those of everyone in their FTB Teams party
     * (online players read live, offline ones from the {@link SquareLedger}). The value is who did it: empty when the
     * player did it themselves, else a teammate's name.
     */
    public static Map<ResourceLocation, String> teamSquares(ServerPlayer player) {
        List<AdvancementHolder> known = knownSquares(player.server, ChecklistConfig.grid());
        Map<ResourceLocation, String> done = new LinkedHashMap<>();
        for (AdvancementHolder holder : known) {
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) done.put(holder.id(), "");
        }
        for (UUID member : Teams.members(player)) {
            if (member.equals(player.getUUID())) continue;
            ServerPlayer online = player.server.getPlayerList().getPlayer(member);
            SquareLedger.Entry offline = online == null ? SquareLedger.get(player.server).entry(player.server, member) : null;
            String name = online != null ? online.getGameProfile().getName() : offline.name();
            for (AdvancementHolder holder : known) {
                if (done.containsKey(holder.id())) continue;
                boolean isDone = online != null ? online.getAdvancements().getOrStartProgress(holder).isDone() : offline.done().contains(holder.id().toString());
                if (isDone) done.put(holder.id(), name);
            }
        }
        return done;
    }

    /** Remembers every advancement the player has done, for teammates to count while this player is offline. */
    private static void snapshot(ServerPlayer player) {
        Set<String> done = new LinkedHashSet<>();
        for (AdvancementHolder holder : player.server.getAdvancements().getAllAdvancements()) {
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) done.add(holder.id().toString());
        }
        SquareLedger.get(player.server).record(player.getUUID(), player.getGameProfile().getName(), done);
    }

    /** "party id:members", or "solo"; a change means the player joined or left a party, or it gained or lost someone. */
    private static String teamKey(ServerPlayer player) {
        return Teams.party(player).map(p -> p.id() + ":" + new TreeSet<>(p.members())).orElse("solo");
    }

    public static int renownLevel(ServerPlayer player) {
        return Renown.level(PlayerState.load(player).renownXp);
    }

    /** All points a player has: one per done square plus one per Renown level. */
    public static int points(ServerPlayer player) {
        return level(player) + renownLevel(player);
    }

    // ------------------------------------------------------------------ apply + sync

    /** Re-derives every modifier from the stored ranks and the current points. Safe to call often. */
    public static void apply(ServerPlayer player) {
        GridConfig grid = ChecklistConfig.grid();
        UpgradeCatalog catalog = ChecklistConfig.upgrades();
        PlayerState state = PlayerState.load(player);
        int level = level(player);
        boolean changed = false;
        // drop ranks of upgrades that no longer exist or exceed their max
        for (String id : new ArrayList<>(state.ranks.keySet())) {
            UpgradeCatalog.Upgrade u = catalog.get(id);
            if (u == null) { state.setRank(id, 0); changed = true; }
            else if (state.rank(id) > u.ranks) { state.setRank(id, u.ranks); changed = true; }
        }
        changed |= state.enforceBudget(level + Renown.level(state.renownXp), catalog);
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

    static void refreshEffects(ServerPlayer player, PlayerState state, UpgradeCatalog catalog) {
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
        long xp = state.renownXp;
        PacketDistributor.sendToPlayer(player, new SyncPayload(level(player), total(player), revealAll.getOrDefault(player, false),
                state.ranks, Renown.level(xp), Renown.into(xp), Renown.need(xp), Teams.party(player).map(Teams.Party::name).orElse("")));
        syncDirty.remove(player);
    }

    /** Sends the whole grid with display data and done flags (login, advancement earned, reload). */
    public static void syncGrid(ServerPlayer player) {
        GridConfig grid = ChecklistConfig.grid();
        Map<ResourceLocation, String> teamDone = teamSquares(player);
        List<GridSyncPayload.Square> squares = new ArrayList<>();
        for (GridConfig.Cell cell : grid.cells()) {
            AdvancementHolder holder = player.server.getAdvancements().get(cell.id());
            if (holder == null) continue; // unknown: treated as empty
            boolean done = teamDone.containsKey(holder.id());
            var display = holder.value().display().orElse(null);
            squares.add(new GridSyncPayload.Square(cell.x(), cell.y(), cell.id().toString(),
                    display != null ? display.getTitle() : Component.literal(cell.id().toString()),
                    display != null ? display.getDescription() : Component.empty(),
                    display != null ? display.getIcon() : net.minecraft.world.item.ItemStack.EMPTY,
                    display != null ? display.getType().ordinal() : 0, done, teamDone.getOrDefault(holder.id(), "")));
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

    // ------------------------------------------------------------------ retroactive baseline

    /**
     * Counts what a player already did before Radarcraft saw it, without any level-up fanfare: done squares that were
     * never credited are credited silently, level commands for thresholds already passed are marked as given without
     * running them, and Renown is seeded once from past XP. One summary line tells the player what they got.
     * Also runs when the player's party changes: squares their new teammates did start counting, and squares only a
     * former teammate did stop counting.
     *
     * @param gridChanged true after /checklist reload, where newly counted squares come from a changed grid
     */
    public static void baseline(ServerPlayer player, boolean gridChanged) {
        GridConfig grid = ChecklistConfig.grid();
        PlayerState state = PlayerState.load(player);
        boolean firstTime = !state.existed;
        // Players from before the credited list existed already saw these squares announced one by one: credit quietly.
        boolean announceSquares = firstTime || gridChanged;

        int newSquares = 0;
        int teamSquares = 0;
        Map<ResourceLocation, String> done = teamSquares(player);
        for (Map.Entry<ResourceLocation, String> e : done.entrySet()) {
            if (state.credited.add(e.getKey().toString())) {
                if (e.getValue().isEmpty()) newSquares++; else teamSquares++;
            }
        }
        int lostSquares = 0;
        for (AdvancementHolder holder : knownSquares(player.server, grid)) {
            if (!done.containsKey(holder.id()) && state.credited.remove(holder.id().toString())) lostSquares++;
        }
        int level = done.size();
        for (Integer at : grid.commandsOnLevel.keySet()) {
            if (at <= level && !state.rewardedLevels.contains(at)) state.rewardedLevels.add(at);
        }
        int seededRenown = 0;
        if (!state.renownSeeded) {
            int kills = player.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
            state.renownXp = Math.max(0, player.totalExperience) + (long) kills * RENOWN_SEED_PER_KILL;
            state.renownSeeded = true;
            seededRenown = Renown.level(state.renownXp);
            Checklist.LOGGER.info("{}: Renown seeded from past play: {} XP ({} total experience + {} kills x {}) = Renown {}",
                    player.getGameProfile().getName(), state.renownXp, player.totalExperience, kills, RENOWN_SEED_PER_KILL, seededRenown);
        }
        state.save(player);

        List<String> parts = new ArrayList<>();
        if (newSquares > 0 && announceSquares) {
            parts.add(gridChanged && !firstTime
                    ? String.format("the grid changed: %d more square%s already done", newSquares, newSquares == 1 ? " is" : "s are")
                    : String.format("%d square%s already done from your past adventures", newSquares, newSquares == 1 ? "" : "s"));
        }
        if (teamSquares > 0) parts.add(String.format("%d square%s done by your team", teamSquares, teamSquares == 1 ? "" : "s"));
        if (lostSquares > 0) parts.add(String.format("%d square%s only a former teammate did no longer count", lostSquares, lostSquares == 1 ? "" : "s"));
        if (seededRenown > 0) parts.add(String.format("Renown %d from your past experience", seededRenown));
        if (!parts.isEmpty()) {
            int available = level + Renown.level(state.renownXp) - state.spent(ChecklistConfig.upgrades());
            String tail = available >= 0 ? String.format(", %d point%s to spend.", available, available == 1 ? "" : "s")
                    : String.format(": your newest upgrades give back %d point%s.", -available, available == -1 ? "" : "s");
            player.sendSystemMessage(Component.literal("Radarcraft: " + String.join(" and ", parts) + tail).withStyle(ChatFormatting.GOLD));
        }
        if (newSquares > 0 || teamSquares > 0 || lostSquares > 0 || seededRenown > 0) {
            Checklist.LOGGER.info("{}: baseline credited {} square(s) silently ({}), {} from the team, {} no longer counted, Renown seed {}",
                    player.getGameProfile().getName(), newSquares, gridChanged ? "grid changed" : firstTime ? "first login" : "login",
                    teamSquares, lostSquares, seededRenown);
        }
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
            int available = level(player) + Renown.level(state.renownXp) - state.spent(catalog);
            int needed = (rank - current) * u.cost;
            if (needed > available) return "Not enough points (" + u.name + " needs " + needed + ", you have " + available + ")";
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
        PlayerState state = PlayerState.load(player);
        if (grid.clearVanillaXpRewards) {
            int xp = holder.value().rewards().experience();
            if (xp > 0) {
                player.giveExperiencePoints(-xp);
                state = PlayerState.load(player); // the reward already counted toward Renown; take it back too
                state.renownXp = Math.max(0, state.renownXp - xp);
            }
        }
        boolean fresh = state.credited.add(holder.id().toString());
        state.save(player);
        snapshot(player);
        String title = holder.value().display().map(d -> d.getTitle().getString()).orElse(holder.id().toString());
        creditSquare(player, title, fresh, "");
        // the party shares squares: every online teammate who did not have it yet gets the point too
        for (UUID member : Teams.members(player)) {
            ServerPlayer mate = member.equals(player.getUUID()) ? null : player.server.getPlayerList().getPlayer(member);
            if (mate == null) continue;
            PlayerState mateState = PlayerState.load(mate);
            boolean mateFresh = mateState.credited.add(holder.id().toString());
            if (mateFresh) mateState.save(mate);
            creditSquare(mate, title, mateFresh, player.getGameProfile().getName());
        }
    }

    /** Fanfare and level rewards for a newly counted square; only a resync when it already counted (re-earned, or a teammate had it). */
    private static void creditSquare(ServerPlayer player, String title, boolean fresh, String by) {
        if (!fresh) {
            applyAndSync(player);
            return;
        }
        int level = level(player);
        int before = level - 1; // this square is already done when the event fires
        player.sendSystemMessage(Component.literal(String.format("Square %d / %d: %s (+1 point%s)", level, total(player), title,
                by.isEmpty() ? "" : ", by " + by)));
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1.0f);
        runLevelCommands(player, ChecklistConfig.grid(), before, level);
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

    /** Renown: every positive XP gain counts, forever. */
    @SubscribeEvent
    public static void onXpChange(PlayerXpEvent.XpChange event) {
        if (event.getAmount() <= 0 || !(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerState state = PlayerState.load(player);
        if (!state.renownSeeded) return; // not logged in through the baseline yet
        int before = Renown.level(state.renownXp);
        state.renownXp += event.getAmount();
        state.save(player);
        int after = Renown.level(state.renownXp);
        if (after > before) {
            int gained = after - before;
            player.sendSystemMessage(Component.literal(String.format("Renown %d (+%d point%s)", after, gained, gained == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            if (after >= 10) player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7f, 1.2f);
            apply(player);
            sync(player);
        } else {
            syncDirty.add(player);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            snapshot(player);
            teamKeys.put(player, teamKey(player));
            baseline(player, false);
            applyAndSync(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            snapshot(player);
            teamKeys.remove(player);
        }
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
        if (player.tickCount % SYNC_FLUSH_TICKS == 0 && syncDirty.contains(player)) sync(player);
        if (player.tickCount % TEAM_CHECK_TICKS == 0) {
            String key = teamKey(player);
            String old = teamKeys.put(player, key);
            if (old != null && !old.equals(key)) {
                Checklist.LOGGER.info("{}: party changed ({} -> {}), recounting squares", player.getGameProfile().getName(), old, key);
                baseline(player, false);
                applyAndSync(player);
            }
        }
    }
}
