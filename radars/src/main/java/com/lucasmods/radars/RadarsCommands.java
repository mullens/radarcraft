package com.lucasmods.radars;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;


/**
 * /radars status | locate n | tp n | reset n | spawn n  (operator level 2; n = 2..7, spawn also accepts 1, 8, 9)
 */
public final class RadarsCommands {
    private RadarsCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("radars")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(RadarsCommands::status))
                .then(Commands.literal("locate").then(Commands.argument("n", IntegerArgumentType.integer(2, 7)).executes(RadarsCommands::locate)))
                .then(Commands.literal("tp").then(Commands.argument("n", IntegerArgumentType.integer(2, 7)).executes(RadarsCommands::tp)))
                .then(Commands.literal("reset").then(Commands.argument("n", IntegerArgumentType.integer(2, 7)).executes(RadarsCommands::reset)))
                .then(Commands.literal("spawn").then(Commands.argument("n", IntegerArgumentType.integer(1, 9)).executes(RadarsCommands::spawn))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().overworld();
        RadarsData data = RadarsData.get(level);
        for (RadarsData.Shrine s : data.all()) {
            ctx.getSource().sendSuccess(() -> Component.literal(s.describe()), true);
        }
        return 1;
    }

    private static int locate(CommandContext<CommandSourceStack> ctx) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        ServerLevel level = ctx.getSource().getServer().overworld();
        RadarsData.Shrine s = RadarsData.get(level).get(n);
        Vec3 target = s.pointTarget();
        Vec3 from = ctx.getSource().getPosition();
        double distance = Math.sqrt(Math.pow(target.x - from.x, 2) + Math.pow(target.z - from.z, 2));
        RadarsData.Shrine previous = n > RadarVariant.FIRST_SHRINE ? RadarsData.get(level).get(n - 1) : null;
        String hop = previous == null ? "" : String.format(", %.0f blocks from Radar #%d's target", Math.hypot(s.targetX - previous.targetX, s.targetZ - previous.targetZ), n - 1);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s. From you: %s, %.0f blocks away%s", s.describe(), RadarEntity.compass(target.x - from.x, target.z - from.z), distance, hop)), true);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getServer().overworld();
        RadarsData data = RadarsData.get(level);
        RadarsData.Shrine s = data.get(n);
        if (s.pos == null) {
            s.pos = ShrineBuilder.resolveAndBuild(level, s.targetX, s.targetZ);
            data.setDirty();
        }
        player.teleportTo(level, s.pos.getX() + 0.5, s.pos.getY() + 1, s.pos.getZ() + 4.5, 180, 0);
        ctx.getSource().sendSuccess(() -> Component.literal("Teleported to " + s.describe()), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        ServerLevel level = ctx.getSource().getServer().overworld();
        RadarsData data = RadarsData.get(level);
        RadarsData.Shrine s = data.get(n);
        if (s.bossId != null) { Entity e = level.getEntity(s.bossId); if (e != null) e.discard(); }
        if (s.catId != null) { Entity e = level.getEntity(s.catId); if (e != null) e.discard(); }
        s.bossId = null;
        s.catId = null;
        s.defeated = false;
        data.setDirty();
        ctx.getSource().sendSuccess(() -> Component.literal("Reset " + s.describe()), true);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RadarVariant variant = RadarVariant.byId(n);
        Vec3 look = player.getLookAngle();
        double distance = 3 + variant.scale * 1.5;
        BlockPos at = BlockPos.containing(player.getX() + look.x * distance, player.getY(), player.getZ() + look.z * distance);
        boolean tamed = variant == RadarVariant.RADAR;
        RadarEntity radar = RadarEntity.spawn(level, variant, tamed, at, tamed ? 1 : n);
        if (radar == null) {
            ctx.getSource().sendFailure(Component.literal("Could not spawn"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + variant.displayName + " at " + at.toShortString()), true);
        return 1;
    }
}
