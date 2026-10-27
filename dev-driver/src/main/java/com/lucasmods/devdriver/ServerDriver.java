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
 * quit                   stop the server (dedicated) or do nothing (integrated: let the client script quit)
 * </pre>
 */
public final class ServerDriver {
    private final List<String> lines = new ArrayList<>();
    private int pc = 0;
    private int waitTicks = 40;
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
