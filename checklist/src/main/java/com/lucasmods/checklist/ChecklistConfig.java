package com.lucasmods.checklist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads config/checklist-grid.json and config/checklist-upgrades.json, writing the bundled defaults
 * (the Skybound fork's real grid and catalog) when a file is missing. Both sides read the same files;
 * the modpack ships them to clients and server, so nothing needs to be synced.
 */
public final class ChecklistConfig {
    private static final String GRID_FILE = "checklist-grid.json";
    private static final String UPGRADES_FILE = "checklist-upgrades.json";

    private static GridConfig grid;
    private static UpgradeCatalog upgrades;
    private static long gridStamp = -1;
    private static long upgradesStamp = -1;

    private ChecklistConfig() {}

    public static synchronized GridConfig grid() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(GRID_FILE);
        long stamp = stamp(file);
        if (grid == null || stamp != gridStamp) {
            try {
                grid = GridConfig.parse(read(file));
                gridStamp = stamp(file);
                Checklist.LOGGER.info("Loaded {} ({} squares, {}x{})", GRID_FILE, grid.cells().size(), grid.width(), grid.height());
            } catch (Exception e) {
                Checklist.LOGGER.error("Could not read {}; using the bundled default", GRID_FILE, e);
                grid = GridConfig.parse(bundled(GRID_FILE));
                gridStamp = stamp;
            }
        }
        return grid;
    }

    public static synchronized UpgradeCatalog upgrades() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(UPGRADES_FILE);
        long stamp = stamp(file);
        if (upgrades == null || stamp != upgradesStamp) {
            try {
                upgrades = UpgradeCatalog.parse(read(file));
                upgradesStamp = stamp(file);
                Checklist.LOGGER.info("Loaded {} ({} upgrades, {} ranks)", UPGRADES_FILE, upgrades.all().size(), upgrades.totalRanks);
            } catch (Exception e) {
                Checklist.LOGGER.error("Could not read {}; using the bundled default", UPGRADES_FILE, e);
                upgrades = UpgradeCatalog.parse(bundled(UPGRADES_FILE));
                upgradesStamp = stamp;
            }
        }
        return upgrades;
    }

    /** Forces both files to be re-read on next access. */
    public static synchronized void invalidate() {
        gridStamp = -1;
        upgradesStamp = -1;
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream in = defaultStream(file.getFileName().toString())) {
                Files.copy(in, file);
            }
            Checklist.LOGGER.info("Wrote default {}", file);
        }
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject bundled(String name) {
        try (InputStream in = defaultStream(name)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("bundled default missing: " + name, e);
        }
    }

    private static InputStream defaultStream(String name) {
        InputStream in = ChecklistConfig.class.getResourceAsStream("/assets/checklist/defaults/" + name);
        if (in == null) throw new IllegalStateException("bundled default missing: " + name);
        return in;
    }

    private static long stamp(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
