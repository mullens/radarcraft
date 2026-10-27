package com.lucasmods.checklist.client;

import com.lucasmods.checklist.Checklist;
import com.lucasmods.checklist.ChecklistConfig;
import com.lucasmods.checklist.ClientState;
import com.lucasmods.checklist.GridConfig;
import com.lucasmods.checklist.UpgradeCatalog;
import com.lucasmods.checklist.net.GridSyncPayload;
import com.lucasmods.checklist.net.SetRankPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The Radarcraft screen: a "Grid" tab (challenge grid with adjacency reveal) and an "Upgrades" tab
 * (spend and refund points). Grid contents and done flags come from the server's grid sync; squares, Renown
 * and ranks from the small sync packet; the catalog (names, costs) from the local config file.
 * Points = done squares + Renown levels. Upgrades with one rank that cost more than one point are on/off switches.
 */
public final class ChecklistScreen extends Screen {
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 30;
    private static final int MAX_CELL = 32;
    private static final int MIN_CELL = 8;
    /** Big grids open at this size (icons visible) on the first start square; drag to move, scroll to zoom. */
    private static final int DEFAULT_CELL = 22;
    /** Where the grid view was when the screen last closed, so reopening it keeps zoom and position (-1: not yet). */
    private static int savedCell = -1, savedX, savedY;

    private static final int BG = 0xC0101010;
    private static final int PANEL = 0xFF2A2A2A;
    private static final int HIDDEN = 0xFF1B1B1B;
    private static final int HIDDEN_BORDER = 0xFF343434;
    private static final int LOCKED_BORDER = 0xFF6A6A6A;
    private static final int DONE_TASK = 0xFF55FF55;
    private static final int DONE_GOAL = 0xFF55FFFF;
    private static final int DONE_CHALLENGE = 0xFFFFAA00;
    private static final int DIM_OVERLAY = 0x99000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int BAR_BG = 0xFF3A3A3A;
    private static final int BAR_FILL = 0xFF55FF55;
    private static final int BTN = 0xFF3A3A3A;
    private static final int BTN_HOVER = 0xFF585858;
    private static final int BTN_DISABLED = 0xFF242424;
    private static final int PIP_ON = 0xFF55FF55;
    private static final int PIP_ON_BORDER = 0xFF1E5E1E;
    private static final int PIP_OFF = 0xFF262626;
    private static final int PIP_OFF_BORDER = 0xFFA0A0A0;
    /** Row heights: name + one description line, or name + two. */
    private static final int ROW_1 = 22, ROW_2 = 32;
    private static final int TEXT_RESERVE = 160;
    private static final int RENOWN_FILL = 0xFFC77DFF;
    private static final int TOGGLE_ON = 0xFF2E7D32;
    private static final int TOGGLE_ON_HOVER = 0xFF43A047;
    private static final int BADGE = 0xFFFFD54F;
    private static final int WARN = 0xFFFF7070;
    private static final int TABS_RIGHT = 8 + 70 + 4 + 70;

    private enum Tab { GRID, UPGRADES }
    private enum CellState { HIDDEN, REVEALED, DONE }

    private record Cell(GridSyncPayload.Square square, CellState state) {}
    private record HitBox(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    @Nullable private final Screen lastScreen;
    private Tab tab = Tab.GRID;
    private GridConfig grid;
    private UpgradeCatalog catalog;
    private List<Cell> cells = new ArrayList<>();
    private List<GridSyncPayload.Square> cellsSource = List.of();
    private int cell, gridX, gridY, gridW, gridH;
    private boolean dragging;
    private double dragAccX, dragAccY;
    private int upgradesScroll, upgradesContentH;
    private final List<HitBox> hits = new ArrayList<>();
    private Button gridTab, upgradesTab;
    @Nullable private UpgradeCatalog.Upgrade hoveredUpgrade;
    @Nullable private List<Component> headerTooltip;

    public ChecklistScreen(@Nullable Screen lastScreen) {
        super(Component.literal("Radarcraft"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        grid = ChecklistConfig.grid();
        catalog = ChecklistConfig.upgrades();
        rebuildCells();
        int avail = width - 20;
        int fit = Math.min(avail / Math.max(1, grid.width()), (height - HEADER_H - FOOTER_H - 10) / Math.max(1, grid.height()));
        if (savedCell > 0) {
            setCell(savedCell);
            gridX = savedX;
            gridY = savedY;
        } else if (fit >= DEFAULT_CELL) { // small grid: fit it, centred
            setCell(Math.min(fit, MAX_CELL));
            gridX = (width - gridW) / 2;
            gridY = HEADER_H + (height - HEADER_H - FOOTER_H - gridH) / 2;
        } else { // big grid: centre the first start square
            setCell(DEFAULT_CELL);
            int[] focus = grid.focus();
            gridX = width / 2 - focus[0] * cell - cell / 2;
            gridY = HEADER_H + (height - HEADER_H - FOOTER_H) / 2 - focus[1] * cell - cell / 2;
        }
        clampView();

        int bw = 70;
        gridTab = addRenderableWidget(Button.builder(Component.literal("Grid"), b -> switchTab(Tab.GRID)).bounds(8, 6, bw, 20).build());
        upgradesTab = addRenderableWidget(Button.builder(Component.literal("Upgrades"), b -> switchTab(Tab.UPGRADES)).bounds(8 + bw + 4, 6, bw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 40, height - 24, 80, 20).build());
        switchTab(tab);
    }

    private void setCell(int size) {
        cell = Mth.clamp(size, MIN_CELL, MAX_CELL);
        gridW = cell * grid.width();
        gridH = cell * grid.height();
    }

    /** Keeps at least a few squares of the grid inside the view. */
    private void clampView() {
        int keep = cell * 3;
        gridX = Mth.clamp(gridX, Math.min(10, width - 10 - gridW), Math.max(10, width - 10 - keep));
        gridX = Math.max(gridX, 10 + keep - gridW);
        gridY = Mth.clamp(gridY, HEADER_H + keep - gridH, height - FOOTER_H - keep);
    }

    private void switchTab(Tab t) {
        tab = t;
        gridTab.active = t != Tab.GRID;
        upgradesTab.active = t != Tab.UPGRADES;
    }

    // ------------------------------------------------------------------ grid data

    /** Recomputes reveal states from the synced squares (cheap; only when the sync changed). */
    private void rebuildCells() {
        List<GridSyncPayload.Square> source = ClientState.squares;
        if (source == cellsSource && !cells.isEmpty()) return;
        cellsSource = source;
        boolean[][] done = new boolean[grid.height()][grid.width()];
        for (GridSyncPayload.Square s : source) {
            if (s.y() >= 0 && s.y() < grid.height() && s.x() >= 0 && s.x() < grid.width()) done[s.y()][s.x()] = s.done();
        }
        List<Cell> list = new ArrayList<>();
        for (GridSyncPayload.Square s : source) {
            CellState state;
            if (s.done()) state = CellState.DONE;
            else if (ClientState.revealAll || grid.isStart(s.x(), s.y()) || adjacentDone(done, s.x(), s.y())) state = CellState.REVEALED;
            else state = CellState.HIDDEN;
            list.add(new Cell(s, state));
        }
        cells = list;
    }

    private static boolean adjacentDone(boolean[][] done, int x, int y) {
        return isDone(done, x - 1, y) || isDone(done, x + 1, y) || isDone(done, x, y - 1) || isDone(done, x, y + 1);
    }

    private static boolean isDone(boolean[][] done, int x, int y) {
        return y >= 0 && y < done.length && x >= 0 && x < done[y].length && done[y][x];
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hits.clear();
        hoveredUpgrade = null;
        headerTooltip = null;
        rebuildCells();
        renderHeader(g, mouseX, mouseY);
        if (tab == Tab.GRID) renderGrid(g, mouseX, mouseY);
        else renderUpgrades(g, mouseX, mouseY);
        if (headerTooltip != null) g.renderComponentTooltip(font, headerTooltip, mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, BG);
    }

    /**
     * Two rows right of the tabs: squares and Renown with thin progress bars, then the points line.
     * Hovering anywhere on it explains where points come from.
     */
    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int left = TABS_RIGHT + 12;
        int right = width - 8;
        int half = (right - left) / 2;
        int level = ClientState.level;
        int total = Math.max(ClientState.total, 1);
        g.drawString(font, String.format("Squares %d / %d", level, ClientState.total), left, 3, TEXT, false);
        bar(g, left, 13, half - 10, level / (double) total, BAR_FILL);
        int rx = left + half;
        g.drawString(font, String.format("Renown Lv %d", ClientState.renownLevel), rx, 3, TEXT, false);
        double renownFrac = ClientState.renownNeed <= 0 ? 1.0 : ClientState.renownInto / (double) ClientState.renownNeed;
        bar(g, rx, 13, half - 10, renownFrac, RENOWN_FILL);
        String points = String.format("Points: %d available \u00b7 %d spent \u00b7 %d total", ClientState.available(), ClientState.spent(), ClientState.points());
        g.drawString(font, points, left, 21, ClientState.available() > 0 ? BADGE : TEXT_DIM, false);
        if (mouseX >= left && mouseX < right && mouseY >= 0 && mouseY < HEADER_H - 2) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Points come from two places").withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Squares: each done grid square is 1 point (" + level + ")").withStyle(ChatFormatting.GREEN));
            if (!ClientState.team.isEmpty()) {
                lines.add(Component.literal("Team " + ClientState.team + " shares squares: one done by anyone counts for all.").withStyle(ChatFormatting.AQUA));
                lines.add(Component.literal("Upgrades and Renown stay your own.").withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Renown: each Renown level is 1 point (" + ClientState.renownLevel + ")").withStyle(ChatFormatting.LIGHT_PURPLE));
            lines.add(Component.literal("Renown grows with every bit of XP you earn: kills, bosses,").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("mining, smelting, breeding, fishing. It is never lost.").withStyle(ChatFormatting.GRAY));
            if (ClientState.renownNeed > 0) {
                lines.add(Component.literal(String.format("Next Renown level: %d / %d XP", ClientState.renownInto, ClientState.renownNeed)).withStyle(ChatFormatting.DARK_PURPLE));
            }
            lines.add(Component.literal("Upgrades can be removed any time for a full refund.").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("Shift-click - or + to change 10 ranks at a time.").withStyle(ChatFormatting.DARK_GRAY));
            headerTooltip = lines;
        }
    }

    private static void bar(GuiGraphics g, int x, int y, int w, double frac, int color) {
        if (w <= 0) return;
        g.fill(x, y, x + w, y + 3, BAR_BG);
        g.fill(x, y, x + (int) (w * Math.max(0, Math.min(1, frac))), y + 3, color);
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        int top = HEADER_H;
        int bottom = height - FOOTER_H;
        g.enableScissor(0, top, width, bottom);
        Cell hovered = null;
        for (Cell c : cells) {
            int x = gridX + c.square().x() * cell;
            int y = gridY + c.square().y() * cell;
            if (x + cell < 0 || x > width || y + cell < top || y > bottom) continue;
            boolean hover = mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell && mouseY >= top && mouseY < bottom;
            if (hover) hovered = c;
            renderCell(g, c, x, y, hover);
        }
        if (cells.isEmpty()) {
            g.drawCenteredString(font, "No grid squares known yet (waiting for the server, or every id in the grid is unknown).", width / 2, top + 20, TEXT_DIM);
        }
        g.disableScissor();
        if (hovered != null) g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
    }

    private void renderCell(GuiGraphics g, Cell c, int x, int y, boolean hover) {
        int inner = cell - 2;
        GridSyncPayload.Square s = c.square();
        if (c.state() == CellState.HIDDEN) {
            g.fill(x + 1, y + 1, x + 1 + inner, y + 1 + inner, HIDDEN);
            g.renderOutline(x + 1, y + 1, inner, inner, hover ? LOCKED_BORDER : HIDDEN_BORDER);
            if (cell >= 14) g.drawCenteredString(font, "?", x + cell / 2, y + (cell - 8) / 2, TEXT_DIM);
            return;
        }
        int border = c.state() == CellState.DONE ? frameColor(s.frame()) : LOCKED_BORDER;
        g.fill(x + 1, y + 1, x + 1 + inner, y + 1 + inner, PANEL);
        if (cell < 14) { // zoomed out: no text, done squares filled with their frame colour
            if (c.state() == CellState.DONE) g.fill(x + 2, y + 2, x + inner, y + inner, (border & 0x00FFFFFF) | 0x99000000);
        } else if (!s.icon().isEmpty() && cell >= 18) {
            int ix = x + (cell - 16) / 2;
            int iy = y + (cell - 16) / 2;
            g.renderItem(s.icon(), ix, iy);
            if (c.state() != CellState.DONE) g.fill(ix - 1, iy - 1, ix + 17, iy + 17, DIM_OVERLAY);
        } else {
            String initial = s.title().getString();
            g.drawCenteredString(font, initial.isEmpty() ? "*" : initial.substring(0, 1), x + cell / 2, y + (cell - 8) / 2, c.state() == CellState.DONE ? TEXT : TEXT_DIM);
        }
        g.renderOutline(x + 1, y + 1, inner, inner, border);
        if (hover) g.renderOutline(x, y, cell, cell, TEXT);
    }

    /** 0 = task, 1 = goal, 2 = challenge (AdvancementType ordinal). */
    private static int frameColor(int frame) {
        return switch (frame) {
            case 1 -> DONE_GOAL;
            case 2 -> DONE_CHALLENGE;
            default -> DONE_TASK;
        };
    }

    private List<Component> tooltip(Cell c) {
        List<Component> lines = new ArrayList<>();
        if (c.state() == CellState.HIDDEN) {
            lines.add(Component.literal("???").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal(kind(ResourceLocation.parse(c.square().id())) + " · " + modName(sourceMod(ResourceLocation.parse(c.square().id()))))
                    .withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
            lines.add(Component.literal("Complete a neighbouring square to reveal this one").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        GridSyncPayload.Square s = c.square();
        lines.add(s.title().copy().withStyle(c.state() == CellState.DONE ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        if (!s.description().getString().isEmpty()) lines.add(s.description().copy().withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(modName(sourceMod(ResourceLocation.parse(s.id())))).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        lines.add(Component.literal(c.state() != CellState.DONE ? "Not yet" : s.doneBy().isEmpty() ? "Done" : "Done by " + s.doneBy())
                .withStyle(c.state() == CellState.DONE ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return lines;
    }

    private static String kind(ResourceLocation id) {
        if (!id.getNamespace().equals(Checklist.MODID)) return "Achievement";
        return id.getPath().startsWith("biome/") ? "Biome" : id.getPath().startsWith("photo/") ? "Photo" : "Square";
    }

    /** The mod a square is about: Radarcraft's own biome and photo squares ({@code checklist:biome/<mod>/...}) name it in the path. */
    private static String sourceMod(ResourceLocation id) {
        String[] parts = id.getPath().split("/");
        return id.getNamespace().equals(Checklist.MODID) && parts.length >= 3 ? parts[1] : id.getNamespace();
    }

    private static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace).map(c -> c.getModInfo().getDisplayName()).orElse(namespace);
    }

    private void renderUpgrades(GuiGraphics g, int mouseX, int mouseY) {
        int top = HEADER_H;
        int bottom = height - FOOTER_H;
        int left = Math.max(10, (width - 480) / 2);
        int right = Math.min(width - 10, left + 480);
        int rowW = right - left;
        g.enableScissor(0, top, width, bottom);
        int y = top - upgradesScroll;
        for (String category : catalog.categories()) {
            if (y + 12 > top && y < bottom) g.drawString(font, category, left, y + 2, DONE_CHALLENGE, false);
            y += 14;
            for (UpgradeCatalog.Upgrade u : catalog.all().values()) {
                if (!u.category.equals(category)) continue;
                int rowH = descLines(u, rowW).size() > 1 ? ROW_2 : ROW_1;
                if (y + rowH > top && y < bottom) renderUpgradeRow(g, u, left, y, rowW, rowH, mouseX, mouseY, top, bottom);
                y += rowH + 2;
            }
            y += 4;
        }
        g.disableScissor();
        upgradesContentH = y + upgradesScroll - top;
        if (hoveredUpgrade != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hoveredUpgrade.name).withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal(hoveredUpgrade.description).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal(hoveredUpgrade.isToggle() ? hoveredUpgrade.cost + " points"
                    : hoveredUpgrade.cost + (hoveredUpgrade.cost == 1 ? " point" : " points") + " per rank, " + hoveredUpgrade.ranks + (hoveredUpgrade.ranks == 1 ? " rank" : " ranks"))
                    .withStyle(ChatFormatting.YELLOW));
            if (!hoveredUpgrade.excludes.isEmpty()) lines.add(Component.literal("Excludes: " + String.join(", ", hoveredUpgrade.excludes)).withStyle(ChatFormatting.RED));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** The description wrapped to the text column: at most two lines, the second cut with "..." only if it still does not fit. */
    private List<String> descLines(UpgradeCatalog.Upgrade u, int rowW) {
        int textW = rowW - TEXT_RESERVE - 6;
        List<String> out = new ArrayList<>();
        for (var seq : font.split(Component.literal(u.description), textW)) {
            StringBuilder sb = new StringBuilder();
            seq.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
            out.add(sb.toString());
        }
        if (out.size() > 2) {
            String second = String.join(" ", out.subList(1, out.size()));
            out = new ArrayList<>(List.of(out.get(0), font.plainSubstrByWidth(second, textW - font.width("...")).stripTrailing() + "..."));
        }
        return out;
    }

    private void renderUpgradeRow(GuiGraphics g, UpgradeCatalog.Upgrade u, int x, int y, int w, int h, int mouseX, int mouseY, int clipTop, int clipBottom) {
        int rank = ClientState.rank(u.id);
        String excludedBy = null;
        for (String other : u.excludes) if (ClientState.rank(other) > 0) excludedBy = nameOf(other);
        for (UpgradeCatalog.Upgrade o : catalog.all().values()) if (o.excludes.contains(u.id) && ClientState.rank(o.id) > 0) excludedBy = o.name;
        boolean affordable = ClientState.available() >= u.cost;
        boolean canAdd = rank < u.ranks && affordable && excludedBy == null;
        boolean canRemove = rank > 0;

        g.fill(x, y, x + w, y + h, rank > 0 ? 0x30FFFFFF : 0x18FFFFFF);
        int textW = w - TEXT_RESERVE;
        g.drawString(font, u.name, x + 4, y + 2, rank > 0 ? TEXT : 0xFFDDDDDD, false);
        // an exclusion goes after the name; not enough points just shows as a red cost
        String warn = null;
        if (rank < u.ranks && excludedBy != null) warn = "not with " + excludedBy;
        if (warn != null) {
            int wx = x + 4 + font.width(u.name) + 6;
            int room = x + textW - wx;
            if (room > 20) g.drawString(font, font.width(warn) > room ? font.plainSubstrByWidth(warn, room - 6) + ".." : warn, wx, y + 2, WARN, false);
        }
        List<String> lines = descLines(u, w);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 4, y + 12 + i * 10, TEXT_DIM, false);
        if (mouseX >= x && mouseX < x + textW && mouseY >= Math.max(y, clipTop) && mouseY < Math.min(y + h, clipBottom)) hoveredUpgrade = u;
        int mid = y + (h - 14) / 2; // controls are centred vertically in the row

        if (u.isToggle()) {
            // on/off switch with a cost badge
            String badge = u.cost + " pts";
            g.drawString(font, badge, x + w - 64 - font.width(badge) - 6, mid + 3, affordable || rank > 0 ? BADGE : WARN, false);
            boolean on = rank > 0;
            boolean enabled = on || canAdd;
            toggleButton(g, x + w - 64, mid, 58, 14, on, enabled, mouseX, mouseY, clipTop, clipBottom, () -> setRank(u, on ? 0 : 1));
            return;
        }

        // rank squares: each one outlined, so empty ones stay visible on the translucent row
        int pipX = x + w - 152;
        int pipY = u.cost > 1 ? mid - 2 : mid + 3;
        if (u.ranks <= 12) {
            int pipW = Math.min(8, (64 - u.ranks) / Math.max(1, u.ranks));
            for (int i = 0; i < u.ranks; i++) {
                int px = pipX + i * (pipW + 1);
                boolean filled = i < rank;
                g.fill(px, pipY, px + pipW, pipY + 8, filled ? PIP_ON : PIP_OFF);
                g.renderOutline(px, pipY, pipW, 8, filled ? PIP_ON_BORDER : PIP_OFF_BORDER);
            }
        } else {
            // many small ranks: one outlined bar, with a faint notch every 4 ranks
            int barW = 64;
            g.fill(pipX, pipY, pipX + barW, pipY + 8, PIP_OFF);
            int fillW = (int) Math.round((barW - 2) * rank / (double) u.ranks);
            if (fillW > 0) g.fill(pipX + 1, pipY + 1, pipX + 1 + fillW, pipY + 7, PIP_ON);
            for (int i = 4; i < u.ranks; i += 4) {
                int nx = pipX + 1 + (int) Math.round((barW - 2) * i / (double) u.ranks);
                g.fill(nx, pipY + 1, nx + 1, pipY + 7, i <= rank ? PIP_ON_BORDER : 0xFF3C3C3C);
            }
            g.renderOutline(pipX, pipY, barW, 8, rank > 0 ? PIP_ON_BORDER : PIP_OFF_BORDER);
        }
        if (u.cost > 1) g.drawString(font, u.cost + " pts / rank", pipX, mid + 8, affordable || rank > 0 ? BADGE : WARN, false);
        // points in this upgrade out of its maximum (3 pts / rank, 3 ranks: 0/9)
        g.drawString(font, rank * u.cost + "/" + u.ranks * u.cost, x + w - 78, mid + 3, TEXT_DIM, false);

        int bx = x + w - 48;
        int finalRank = rank;
        // shift-click: 10 ranks at a time (adding stops at what the points cover)
        pseudoButton(g, bx, mid, 20, 14, "-", canRemove, mouseX, mouseY, clipTop, clipBottom, () -> setRank(u, finalRank - step()));
        pseudoButton(g, bx + 24, mid, 20, 14, "+", canAdd, mouseX, mouseY, clipTop, clipBottom,
                () -> setRank(u, finalRank + Math.max(1, Math.min(step(), ClientState.available() / Math.max(1, u.cost)))));
    }

    private String nameOf(String id) {
        UpgradeCatalog.Upgrade u = catalog.get(id);
        return u == null ? id : u.name;
    }

    private void toggleButton(GuiGraphics g, int x, int y, int w, int h, boolean on, boolean enabled, int mouseX, int mouseY, int clipTop, int clipBottom, Runnable action) {
        int visTop = Math.max(y, clipTop);
        int visBottom = Math.min(y + h, clipBottom);
        boolean visible = visBottom > visTop;
        boolean hover = enabled && visible && mouseX >= x && mouseX < x + w && mouseY >= visTop && mouseY < visBottom;
        int fill = !enabled ? BTN_DISABLED : on ? (hover ? TOGGLE_ON_HOVER : TOGGLE_ON) : (hover ? BTN_HOVER : BTN);
        g.fill(x, y, x + w, y + h, fill);
        g.renderOutline(x, y, w, h, 0xFF101010);
        // the knob sits right when on, left when off
        int knobX = on ? x + w - 12 : x + 2;
        g.fill(knobX, y + 2, knobX + 10, y + h - 2, enabled ? 0xFFE0E0E0 : 0xFF5A5A5A);
        String label = on ? "ON" : "OFF";
        int labelX = on ? x + (w - 12) / 2 : x + 12 + (w - 12) / 2;
        g.drawCenteredString(font, label, labelX, y + (h - 8) / 2, enabled ? TEXT : 0xFF5A5A5A);
        if (enabled && visible) hits.add(new HitBox(x, visTop, w, visBottom - visTop, action));
    }

    private void pseudoButton(GuiGraphics g, int x, int y, int w, int h, String label, boolean enabled, int mouseX, int mouseY, int clipTop, int clipBottom, Runnable action) {
        int visTop = Math.max(y, clipTop);
        int visBottom = Math.min(y + h, clipBottom);
        boolean visible = visBottom > visTop;
        boolean hover = enabled && visible && mouseX >= x && mouseX < x + w && mouseY >= visTop && mouseY < visBottom;
        g.fill(x, y, x + w, y + h, !enabled ? BTN_DISABLED : hover ? BTN_HOVER : BTN);
        g.renderOutline(x, y, w, h, 0xFF101010);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, enabled ? TEXT : 0xFF5A5A5A);
        if (enabled && visible) hits.add(new HitBox(x, visTop, w, visBottom - visTop, action));
    }

    private static int step() { return hasShiftDown() ? 10 : 1; }

    private void setRank(UpgradeCatalog.Upgrade u, int rank) {
        rank = Mth.clamp(rank, 0, u.ranks);
        // optimistic local update so the screen reacts immediately; the server's sync corrects it if refused
        var ranks = new HashMap<>(ClientState.ranks);
        if (rank == 0) ranks.remove(u.id); else ranks.put(u.id, rank);
        ClientState.ranks = ranks;
        PacketDistributor.sendToServer(new SetRankPayload(u.id, rank));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        for (HitBox hit : hits) {
            if (hit.contains(mouseX, mouseY)) {
                hit.action().run();
                return true;
            }
        }
        if (tab == Tab.GRID && mouseY >= HEADER_H && mouseY < height - FOOTER_H) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && tab == Tab.GRID) {
            dragAccX += dragX;
            dragAccY += dragY;
            gridX += (int) dragAccX;
            gridY += (int) dragAccY;
            dragAccX -= (int) dragAccX;
            dragAccY -= (int) dragAccY;
            clampView();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.GRID && scrollY != 0) { // zoom around the mouse
            double gx = (mouseX - gridX) / cell, gy = (mouseY - gridY) / cell;
            setCell(cell + (scrollY > 0 ? 2 : -2));
            gridX = (int) Math.round(mouseX - gx * cell);
            gridY = (int) Math.round(mouseY - gy * cell);
            clampView();
            return true;
        }
        if (tab == Tab.UPGRADES) {
            int view = height - FOOTER_H - HEADER_H;
            upgradesScroll = Mth.clamp(upgradesScroll - (int) (scrollY * 16), 0, Math.max(0, upgradesContentH - view));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        savedCell = cell;
        savedX = gridX;
        savedY = gridY;
        minecraft.setScreen(lastScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
