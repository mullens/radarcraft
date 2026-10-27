package com.lucasmods.devdriver;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless script runner for the logical server (dedicated server, or the integrated server of a
 * scripted client). Commands run from the server console position (world spawn, overworld).
 * <pre>
 * wait N                 pause N ticks
 * cmd TEXT               run a server command (no leading slash needed), output goes to the log
 * log TEXT               log TEXT; placeholders {named} {namedcount} {tick}
 * expect PLACEHOLDER V   log PASS/FAIL comparing a placeholder with V
 * dumpchannels FILE      write every registered network payload channel to FILE (relative to the server dir)
 * fakejoin NAME          a FakePlayer NAME "logs in" (login event only; it is not in the player list, so it counts as offline)
 * as NAME COMMAND        run a command as player NAME (online, or a fakejoin'ed one)
 * award NAME ADVANCEMENT give player NAME (online or fake) every criterion of an advancement
 * quit                   stop the server (dedicated) or do nothing (integrated: let the client script quit)
 * </pre>
 */
public final class ServerDriver {
    private final List<String> lines = new ArrayList<>();
    private int pc = 0;
    private int waitTicks = 40;
    private java.nio.file.Path waitFile;
    private int waitFileTimeout;
    private boolean done = false;
    private int failures = 0;

    static void register(String path) {
        if (path == null || path.isBlank()) return;
        ServerDriver driver = new ServerDriver();
        try {
            for (String raw : Files.readAllLines(Path.of(path))) {
                String line = raw.strip();
                if (!line.isEmpty() && !line.startsWith("#")) driver.lines.add(line);
            }
        } catch (IOException e) {
            DevDriverMod.LOGGER.error("{} cannot read server script {}", DevDriverMod.TAG, path, e);
            return;
        }
        DevDriverMod.LOGGER.info("{} server driver loaded {} commands from {}", DevDriverMod.TAG, driver.lines.size(), path);
        NeoForge.EVENT_BUS.addListener(driver::onTick);
    }

    private void onTick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer();
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (waitFile != null) {
            if (!java.nio.file.Files.exists(waitFile)) {
                if (--waitFileTimeout <= 0) {
                    DevDriverMod.LOGGER.error("{} [server] timed out waiting for {}", DevDriverMod.TAG, waitFile);
                    failures++;
                    waitFile = null;
                }
                return;
            }
            DevDriverMod.LOGGER.info("{} [server] found {}", DevDriverMod.TAG, waitFile);
            waitFile = null;
        }
        if (pc >= lines.size()) {
            done = true;
            DevDriverMod.LOGGER.info("{} server script finished with {} failure(s)", DevDriverMod.TAG, failures);
            return;
        }
        String line = lines.get(pc++);
        DevDriverMod.LOGGER.info("{} [server] > {}", DevDriverMod.TAG, line);
        try {
            execute(server, line);
        } catch (Exception e) {
            failures++;
            DevDriverMod.LOGGER.error("{} server command failed: {}", DevDriverMod.TAG, line, e);
        }
    }

    private void execute(MinecraftServer server, String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        switch (cmd) {
            case "wait" -> waitTicks = Integer.parseInt(rest.trim());
            case "waitfile" -> { // waitfile PATH [maxTicks]: block until the file exists (an outside signal, e.g. "the client has logged out")
                String[] a = rest.trim().split("\s+");
                waitFile = java.nio.file.Path.of(a[0]);
                waitFileTimeout = a.length > 1 ? Integer.parseInt(a[1]) : 20 * 1800;
            }
            case "cmd" -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), rest);
            case "log" -> DevDriverMod.LOGGER.info("{} [server] {}", DevDriverMod.TAG, expand(server, rest));
            case "expect" -> {
                String[] args = rest.split("\\s+", 2);
                String actual = expand(server, "{" + args[0] + "}");
                if (actual.equals(args[1].trim())) {
                    DevDriverMod.LOGGER.info("{} [server] PASS {} == {}", DevDriverMod.TAG, args[0], args[1].trim());
                } else {
                    failures++;
                    DevDriverMod.LOGGER.error("{} [server] FAIL {} expected {} but was {}", DevDriverMod.TAG, args[0], args[1].trim(), actual);
                }
            }
            case "fakejoin" -> {
                String name = rest.trim();
                var profile = new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
                var fake = net.neoforged.neoforge.common.util.FakePlayerFactory.get(server.overworld(), profile);
                fakes.put(name, fake);
                try { // FakePlayer's Connection has no netty channel; mods that ask "does this client have channel X" then crash
                    for (var f : net.minecraft.network.Connection.class.getDeclaredFields()) {
                        if (f.getType() == io.netty.channel.Channel.class) {
                            f.setAccessible(true);
                            f.set(fake.connection.getConnection(), new io.netty.channel.embedded.EmbeddedChannel());
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(fake));
            }
            case "as" -> {
                String[] a = rest.split("\s+", 2);
                var player = player(server, a[0]);
                String command = a[1];
                // {party:NAME}: the FTB Teams short name of NAME's party ("Display_Name#1a2b3c4d"), which party commands take
                var m = java.util.regex.Pattern.compile("\\{party:(\\w+)}").matcher(command);
                if (m.find()) {
                    var party = com.lucasmods.checklist.Teams.party(player(server, m.group(1))).orElseThrow();
                    command = m.replaceFirst(java.util.regex.Matcher.quoteReplacement(party.name().replaceAll("\\W", "_") + "#" + party.id().toString().substring(0, 8)));
                }
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
            }
            case "award" -> {
                String[] a = rest.trim().split("\s+");
                var player = player(server, a[0]);
                var holder = server.getAdvancements().get(net.minecraft.resources.ResourceLocation.parse(a[1]));
                if (player instanceof net.neoforged.neoforge.common.util.FakePlayer) {
                    // NeoForge never grants advancements to fake players: put it in Radarcraft's record of offline players instead
                    var ledger = com.lucasmods.checklist.SquareLedger.get(server);
                    var done = new java.util.LinkedHashSet<>(ledger.entry(server, player.getUUID()).done());
                    done.add(holder.id().toString());
                    ledger.record(player.getUUID(), player.getGameProfile().getName(), done);
                } else {
                    for (String criterion : holder.value().criteria().keySet()) player.getAdvancements().award(holder, criterion);
                }
            }
            case "dumpworld" -> { // dumpworld FILE: every biome (with its dimension guess and climate) and every mob type, as JSON, for grid generation
                java.nio.file.Path out = server.getServerDirectory().resolve(rest.trim());
                int n = WorldDump.dump(server, out);
                DevDriverMod.LOGGER.info("{} [server] dumped {} biomes and mobs to {}", DevDriverMod.TAG, n, out);
            }
            case "dumpchannels" -> {
                java.nio.file.Path out = server.getServerDirectory().resolve(rest.trim());
                int n = ChannelDump.dump(out);
                DevDriverMod.LOGGER.info("{} [server] dumped {} payload channels to {}", DevDriverMod.TAG, n, out);
            }
            case "quit" -> {
                DevDriverMod.LOGGER.info("{} [server] quitting with {} failure(s)", DevDriverMod.TAG, failures);
                done = true;
                if (server.isDedicatedServer()) server.halt(false);
            }
            default -> throw new IllegalArgumentException("unknown server command " + cmd);
        }
    }

    private final java.util.Map<String, net.minecraft.server.level.ServerPlayer> fakes = new java.util.HashMap<>();

    private net.minecraft.server.level.ServerPlayer player(MinecraftServer server, String name) {
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online;
        var fake = fakes.get(name);
        if (fake == null) throw new IllegalArgumentException("no player " + name);
        return fake;
    }

    private static String expand(MinecraftServer server, String text) {
        ServerLevel level = server.overworld();
        if (text.contains("{named}") || text.contains("{namedcount}")) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof LivingEntity le) || le instanceof Player || !le.hasCustomName()) continue;
                count++;
                if (sb.length() > 0) sb.append(", ");
                sb.append(le.getCustomName().getString()).append("(").append(le.getType().toShortString()).append("):")
                        .append(String.format(Locale.ROOT, "%.1f/%.0f", le.getHealth(), le.getMaxHealth()))
                        .append("@y").append((int) le.getY());
            }
            text = text.replace("{named}", sb.length() == 0 ? "none" : sb.toString()).replace("{namedcount}", String.valueOf(count));
        }
        return text.replace("{tick}", String.valueOf(server.getTickCount()));
    }
}
