package com.lucasmods.checklist;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FTB Teams, read through reflection so Radarcraft has no build or runtime dependency on it. Without FTB Teams (or
 * when a player is not in a party) everyone is a team of one. Grid squares are shared within a party; upgrades are not.
 */
public final class Teams {
    private static boolean resolved;
    private static Method api, isManagerLoaded, getManager, getTeamForPlayerID, isPartyTeam, getMembers, getId, getName;

    private Teams() {}

    /** The player's party, or empty when they are on their own. */
    public static Optional<Party> party(ServerPlayer player) {
        Object team = team(player.getUUID());
        if (team == null) return Optional.empty();
        try {
            if (!(Boolean) isPartyTeam.invoke(team)) return Optional.empty();
            @SuppressWarnings("unchecked")
            Set<UUID> members = new LinkedHashSet<>((Set<UUID>) getMembers.invoke(team));
            members.add(player.getUUID());
            String name = ((net.minecraft.network.chat.Component) getName.invoke(team)).getString();
            return Optional.of(new Party((UUID) getId.invoke(team), name, members));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Checklist.LOGGER.warn("FTB Teams lookup failed; treating {} as solo", player.getGameProfile().getName(), e);
            return Optional.empty();
        }
    }

    /** Everyone whose grid squares count for this player, the player first. */
    public static Set<UUID> members(ServerPlayer player) {
        return party(player).map(Party::members).orElseGet(() -> Set.of(player.getUUID()));
    }

    private static Object team(UUID id) {
        if (!resolve()) return null;
        try {
            Object a = api.invoke(null);
            if (!(Boolean) isManagerLoaded.invoke(a)) return null;
            return ((Optional<?>) getTeamForPlayerID.invoke(getManager.invoke(a), id)).orElse(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean resolve() {
        if (resolved) return api != null;
        resolved = true;
        if (!ModList.get().isLoaded("ftbteams")) return false;
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Class<?> apiIface = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
            Class<?> manager = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
            Class<?> team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
            isManagerLoaded = apiIface.getMethod("isManagerLoaded");
            getManager = apiIface.getMethod("getManager");
            getTeamForPlayerID = manager.getMethod("getTeamForPlayerID", UUID.class);
            isPartyTeam = team.getMethod("isPartyTeam");
            getMembers = team.getMethod("getMembers");
            getId = team.getMethod("getId");
            getName = team.getMethod("getName");
            api = apiClass.getMethod("api");
            Checklist.LOGGER.info("FTB Teams found: grid squares are shared within a party");
            return true;
        } catch (ReflectiveOperationException e) {
            Checklist.LOGGER.warn("FTB Teams is loaded but its API was not found; grid squares stay per player", e);
            api = null;
            return false;
        }
    }

    public record Party(UUID id, String name, Set<UUID> members) {}
}
