package com.lucasmods.checklist;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;

/**
 * /checklist level [player] | upgrades [player] | respec [player] | reload | reveal | grant x y
 */
public final class ChecklistCommands {
    private ChecklistCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("checklist")
                .then(Commands.literal("level")
                        .executes(ctx -> level(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> level(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("upgrades")
                        .executes(ctx -> upgrades(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> upgrades(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("respec")
                        .executes(ctx -> respec(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> respec(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(ChecklistCommands::reload))
                .then(Commands.literal("reveal").requires(s -> s.hasPermission(2)).executes(ChecklistCommands::reveal))
                .then(Commands.literal("grant").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("x", IntegerArgumentType.integer(0))
                                .then(Commands.argument("y", IntegerArgumentType.integer(0)).executes(ChecklistCommands::grant)))));
    }

    private static int level(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        int level = ChecklistServer.level(player);
        int total = ChecklistServer.total(player);
        PlayerState state = PlayerState.load(player);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s: level %d / %d, %d points spent, %d available",
                player.getGameProfile().getName(), level, total, state.spent(), level - state.spent())), true);
        return level;
    }

    private static int upgrades(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        PlayerState state = PlayerState.load(player);
        UpgradeCatalog catalog = ChecklistConfig.upgrades();
        if (state.ranks.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName() + " has no upgrades"), true);
            return 0;
        }
        StringBuilder sb = new StringBuilder(player.getGameProfile().getName()).append(": ");
        for (Map.Entry<String, Integer> e : state.ranks.entrySet()) {
            UpgradeCatalog.Upgrade u = catalog.get(e.getKey());
            sb.append(u == null ? e.getKey() : u.name).append(" ").append(e.getValue()).append("/").append(u == null ? "?" : u.ranks).append(", ");
        }
        sb.setLength(sb.length() - 2);
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), true);
        return state.spent();
    }

    private static int respec(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        ChecklistServer.respec(player);
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared all upgrades of " + player.getGameProfile().getName()), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ChecklistConfig.invalidate();
        GridConfig grid = ChecklistConfig.grid();
        UpgradeCatalog catalog = ChecklistConfig.upgrades();
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) ChecklistServer.applyAndSync(p);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("Reloaded: %d grid squares (%dx%d), %d upgrades / %d ranks",
                grid.cells().size(), grid.width(), grid.height(), catalog.all().size(), catalog.totalRanks)), true);
        return 1;
    }

    private static int reveal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ChecklistServer.setRevealAll(player, true);
        ctx.getSource().sendSuccess(() -> Component.literal("Grid fully revealed for you (dev); relog to undo"), true);
        return 1;
    }

    private static int grant(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        ResourceLocation id = ChecklistConfig.grid().at(x, y);
        if (id == null) {
            ctx.getSource().sendFailure(Component.literal("No square at " + x + ", " + y));
            return 0;
        }
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        if (holder == null) {
            ctx.getSource().sendFailure(Component.literal("Square " + id + " is not a known advancement"));
            return 0;
        }
        for (String criterion : holder.value().criteria().keySet()) player.getAdvancements().award(holder, criterion);
        ctx.getSource().sendSuccess(() -> Component.literal("Granted " + id), true);
        return 1;
    }
}
