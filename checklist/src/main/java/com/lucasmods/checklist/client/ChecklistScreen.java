package com.lucasmods.checklist.client;

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
 * The Checklist screen: a "Grid" tab (challenge grid with adjacency reveal) and an "Upgrades" tab
 * (spend and refund points). Grid contents and done flags come from the server's grid sync; level,
 * total and ranks from the small sync packet; the catalog from the local config file.
 */
public final class ChecklistScreen extends Screen {
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 30;
    private static final int MAX_CELL = 26;
    private static final int MIN_CELL = 12;

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
    private static final int PIP_OFF = 0xFF444444;

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
    private int panX, panY;
    private boolean dragging;
    private int upgradesScroll, upgradesContentH;
    private final List<HitBox> hits = new ArrayList<>();
    private Button gridTab, upgradesTab;
    @Nullable private UpgradeCatalog.Upgrade hoveredUpgrade;

    public ChecklistScreen(@Nullable Screen lastScreen) {
        super(Component.literal("Checklist"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        grid = ChecklistConfig.grid();
        catalog = ChecklistConfig.upgrades();
        rebuildCells();
        int avail = width - 20;
        cell = Mth.clamp(Math.min(avail / Math.max(1, grid.width()), (height - HEADER_H - FOOTER_H - 10) / Math.max(1, grid.height())), MIN_CELL, MAX_CELL);
        gridW = cell * grid.width();
        gridH = cell * grid.height();
        gridX = (width - gridW) / 2;
        gridY = HEADER_H + (height - HEADER_H - FOOTER_H - gridH) / 2;

        int bw = 70;
        gridTab = addRenderableWidget(Button.builder(Component.literal("Grid"), b -> switchTab(Tab.GRID)).bounds(8, 6, bw, 20).build());
        upgradesTab = addRenderableWidget(Button.builder(Component.literal("Upgrades"), b -> switchTab(Tab.UPGRADES)).bounds(8 + bw + 4, 6, bw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 40, height - 24, 80, 20).build());
        switchTab(tab);
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
        rebuildCells();
        renderHeader(g);
        if (tab == Tab.GRID) renderGrid(g, mouseX, mouseY);
        else renderUpgrades(g, mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, BG);
    }

    private void renderHeader(GuiGraphics g) {
        int level = ClientState.level;
        int total = Math.max(ClientState.total, 1);
        String text = tab == Tab.GRID
                ? String.format("Level %d / %d", level, ClientState.total)
                : String.format("Points: %d available / %d spent / %d total", ClientState.available(), ClientState.spent(), level);
        g.drawCenteredString(font, text, width / 2, 8, TEXT);
        int barW = Math.min(240, width - 180);
        int barX = (width - barW) / 2;
        g.fill(barX, 20, barX + barW, 26, BAR_BG);
        g.fill(barX, 20, barX + (int) (barW * (level / (double) total)), 26, BAR_FILL);
        g.renderOutline(barX, 20, barW, 6, 0xFF000000);
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        int top = HEADER_H;
        int bottom = height - FOOTER_H;
        g.enableScissor(0, top, width, bottom);
        Cell hovered = null;
        for (Cell c : cells) {
            int x = gridX + panX + c.square().x() * cell;
            int y = gridY + panY + c.square().y() * cell;
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
            g.drawCenteredString(font, "?", x + cell / 2, y + (cell - 8) / 2, TEXT_DIM);
            return;
        }
        int border = c.state() == CellState.DONE ? frameColor(s.frame()) : LOCKED_BORDER;
        g.fill(x + 1, y + 1, x + 1 + inner, y + 1 + inner, PANEL);
        if (!s.icon().isEmpty() && cell >= 20) {
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
            lines.add(Component.literal("Complete a neighbouring square to reveal this one").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        GridSyncPayload.Square s = c.square();
        lines.add(s.title().copy().withStyle(c.state() == CellState.DONE ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        if (!s.description().getString().isEmpty()) lines.add(s.description().copy().withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(modName(ResourceLocation.parse(s.id()).getNamespace())).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        lines.add(Component.literal(c.state() == CellState.DONE ? "Done" : "Not yet").withStyle(c.state() == CellState.DONE ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return lines;
    }

    private static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace).map(c -> c.getModInfo().getDisplayName()).orElse(namespace);
    }

    private void renderUpgrades(GuiGraphics g, int mouseX, int mouseY) {
        int top = HEADER_H;
        int bottom = height - FOOTER_H;
        int left = Math.max(10, (width - 420) / 2);
        int right = Math.min(width - 10, left + 420);
        int rowW = right - left;
        g.enableScissor(0, top, width, bottom);
        int y = top - upgradesScroll;
        for (String category : catalog.categories()) {
            if (y + 12 > top && y < bottom) g.drawString(font, category, left, y + 2, DONE_CHALLENGE, false);
            y += 14;
            for (UpgradeCatalog.Upgrade u : catalog.all().values()) {
                if (!u.category.equals(category)) continue;
                if (y + 22 > top && y < bottom) renderUpgradeRow(g, u, left, y, rowW, mouseX, mouseY, top, bottom);
                y += 24;
            }
            y += 4;
        }
        g.disableScissor();
        upgradesContentH = y + upgradesScroll - top;
        if (hoveredUpgrade != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hoveredUpgrade.name).withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal(hoveredUpgrade.description).withStyle(ChatFormatting.GRAY));
            if (!hoveredUpgrade.excludes.isEmpty()) lines.add(Component.literal("Excludes: " + String.join(", ", hoveredUpgrade.excludes)).withStyle(ChatFormatting.RED));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void renderUpgradeRow(GuiGraphics g, UpgradeCatalog.Upgrade u, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        int rank = ClientState.rank(u.id);
        boolean excluded = false;
        for (String other : u.excludes) if (ClientState.rank(other) > 0) excluded = true;
        for (UpgradeCatalog.Upgrade o : catalog.all().values()) if (o.excludes.contains(u.id) && ClientState.rank(o.id) > 0) excluded = true;
        boolean canAdd = rank < u.ranks && ClientState.available() > 0 && !excluded;
        boolean canRemove = rank > 0;

        g.fill(x, y, x + w, y + 22, rank > 0 ? 0x30FFFFFF : 0x18FFFFFF);
        g.drawString(font, u.name, x + 4, y + 2, rank > 0 ? TEXT : 0xFFDDDDDD, false);
        String desc = excluded ? "Excluded by " + String.join(", ", u.excludes) : u.description;
        int textW = w - 160;
        g.drawString(font, font.width(desc) > textW ? font.plainSubstrByWidth(desc, textW - 6) + ".." : desc, x + 4, y + 12, TEXT_DIM, false);
        if (mouseX >= x && mouseX < x + w - 160 && mouseY >= Math.max(y, clipTop) && mouseY < Math.min(y + 22, clipBottom)) hoveredUpgrade = u;

        int pipX = x + w - 150;
        int pipW = Math.min(8, 60 / Math.max(1, u.ranks));
        for (int i = 0; i < u.ranks; i++) {
            int px = pipX + i * (pipW + 1);
            g.fill(px, y + 7, px + pipW, y + 15, i < rank ? PIP_ON : PIP_OFF);
        }
        g.drawString(font, rank + "/" + u.ranks, x + w - 78, y + 7, TEXT_DIM, false);

        int bx = x + w - 48;
        int finalRank = rank;
        pseudoButton(g, bx, y + 4, 20, 14, "-", canRemove, mouseX, mouseY, clipTop, clipBottom, () -> setRank(u, finalRank - 1));
        pseudoButton(g, bx + 24, y + 4, 20, 14, "+", canAdd, mouseX, mouseY, clipTop, clipBottom, () -> setRank(u, finalRank + 1));
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
        if (dragging && tab == Tab.GRID && (gridW > width - 20 || gridH > height - HEADER_H - FOOTER_H)) {
            panX = Mth.clamp(panX + (int) dragX, Math.min(0, width - 20 - gridW), Math.max(0, width - 20 - gridW) / 2);
            panY = Mth.clamp(panY + (int) dragY, Math.min(0, height - HEADER_H - FOOTER_H - gridH), 0);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.UPGRADES) {
            int view = height - FOOTER_H - HEADER_H;
            upgradesScroll = Mth.clamp(upgradesScroll - (int) (scrollY * 16), 0, Math.max(0, upgradesContentH - view));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(lastScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
