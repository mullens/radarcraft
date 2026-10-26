package com.lucasmods.checklist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The challenge grid: rows of advancement ids. Loaded from config/checklist-grid.json on both sides.
 * <pre>
 * {
 *   "grid": [["minecraft:story/mine_stone", null, "create:..."], ...],   // null = empty square, never shown
 *   "start": [[x, y], ...],              // squares revealed from the beginning (default: the centre)
 *   "hearts_every_levels": 0,            // legacy: +1 heart per N levels; 0 disables
 *   "commands_on_level": {"25": ["give @s minecraft:diamond"]},   // run as the player when reaching that level
 *   "clear_vanilla_xp_rewards": false    // take the advancement's own XP reward away again
 * }
 * </pre>
 */
public final class GridConfig {
    public record Cell(int x, int y, ResourceLocation id) {}

    private final List<List<@Nullable ResourceLocation>> rows;
    private final List<int[]> start;
    public final int heartsEveryLevels;
    public final Map<Integer, List<String>> commandsOnLevel;
    public final boolean clearVanillaXpRewards;
    private final List<Cell> cells;
    private final Map<ResourceLocation, Cell> byId;

    private GridConfig(List<List<ResourceLocation>> rows, List<int[]> start, int heartsEveryLevels,
                       Map<Integer, List<String>> commandsOnLevel, boolean clearVanillaXpRewards) {
        this.rows = rows;
        this.heartsEveryLevels = heartsEveryLevels;
        this.commandsOnLevel = commandsOnLevel;
        this.clearVanillaXpRewards = clearVanillaXpRewards;
        List<Cell> cells = new ArrayList<>();
        Map<ResourceLocation, Cell> byId = new LinkedHashMap<>();
        for (int y = 0; y < rows.size(); y++) {
            List<ResourceLocation> row = rows.get(y);
            for (int x = 0; x < row.size(); x++) {
                ResourceLocation id = row.get(x);
                if (id != null && !byId.containsKey(id)) {
                    Cell c = new Cell(x, y, id);
                    cells.add(c);
                    byId.put(id, c);
                }
            }
        }
        this.cells = Collections.unmodifiableList(cells);
        this.byId = Collections.unmodifiableMap(byId);
        this.start = start.isEmpty() ? List.of(new int[]{width() / 2, height() / 2}) : start;
    }

    public static GridConfig parse(JsonObject root) {
        List<List<ResourceLocation>> rows = new ArrayList<>();
        int width = 0;
        for (JsonElement rowEl : root.getAsJsonArray("grid")) {
            List<ResourceLocation> row = new ArrayList<>();
            for (JsonElement cell : rowEl.getAsJsonArray()) {
                row.add(cell.isJsonNull() ? null : ResourceLocation.parse(cell.getAsString()));
            }
            width = Math.max(width, row.size());
            rows.add(row);
        }
        for (List<ResourceLocation> row : rows) while (row.size() < width) row.add(null);

        List<int[]> start = new ArrayList<>();
        if (root.has("start")) {
            for (JsonElement e : root.getAsJsonArray("start")) {
                JsonArray xy = e.getAsJsonArray();
                start.add(new int[]{xy.get(0).getAsInt(), xy.get(1).getAsInt()});
            }
        }
        int hearts = root.has("hearts_every_levels") ? root.get("hearts_every_levels").getAsInt() : 0;
        Map<Integer, List<String>> commands = new TreeMap<>();
        if (root.has("commands_on_level")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("commands_on_level").entrySet()) {
                List<String> list = new ArrayList<>();
                for (JsonElement c : e.getValue().getAsJsonArray()) list.add(c.getAsString());
                commands.put(Integer.parseInt(e.getKey().trim()), list);
            }
        }
        boolean clearXp = root.has("clear_vanilla_xp_rewards") && root.get("clear_vanilla_xp_rewards").getAsBoolean();
        return new GridConfig(rows, start, hearts, commands, clearXp);
    }

    public int width() { return rows.isEmpty() ? 0 : rows.get(0).size(); }
    public int height() { return rows.size(); }
    @Nullable public ResourceLocation at(int x, int y) {
        if (y < 0 || y >= rows.size() || x < 0 || x >= rows.get(y).size()) return null;
        return rows.get(y).get(x);
    }
    /** Every non-empty square, in reading order. */
    public List<Cell> cells() { return cells; }
    @Nullable public Cell cell(ResourceLocation id) { return byId.get(id); }
    public boolean contains(ResourceLocation id) { return byId.containsKey(id); }
    public boolean isStart(int x, int y) {
        for (int[] s : start) if (s[0] == x && s[1] == y) return true;
        return false;
    }
}
