package com.lucasmods.keyboardkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The visual Key Binds screen: a keyboard on top, details for the selected key bottom-left,
 * a searchable list of every action bottom-right.
 */
public final class KeyboardScreen extends Screen {
    /** Set right before opening the vanilla list so the Opening handler lets it through once. */

    private static final int FILL_UNBOUND = 0xFF2B2B2B;
    private static final int FILL_BOUND = 0xFF2E5C8A;
    private static final int FILL_SOFT = 0xFF8A6D1A;
    private static final int FILL_HARD = 0xFF9C2626;
    private static final int OUTLINE = 0xFF101010;
    private static final int OUTLINE_LISTENING = 0xFF8C8C50;
    private static final int OUTLINE_HOVER = 0xFFDDDDDD;
    private static final int OUTLINE_SELECTED = 0xFFFFFFFF;
    private static final int PANEL_BG = 0x90000000;
    private static final int PANEL_BORDER = 0xFF4A4A4A;
    private static final int ROW_FOCUS = 0x30FFFFFF;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int BTN = 0xFF3A3A3A;
    private static final int BTN_HOVER = 0xFF585858;
    private static final int BTN_DISABLED = 0xFF242424;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF9A9A9A;
    private static final int TEXT_DISABLED = 0xFF5A5A5A;
    private static final int TEXT_HARD = 0xFFFF6B6B;
    private static final int TEXT_SOFT = 0xFFFFD05A;
    private static final int TEXT_LISTEN = 0xFFFFFF66;
    private static final int TEXT_BOUND = 0xFF8AB8F0;

    private static final int MARGIN = 10;
    private static final int ROW_H = 24;
    private static final int LINE_H = 10;
    private static final int LIST_ROW_H = 11;
    private static final int LEFT_HEADER_H = 16;
    private static final int RIGHT_HEADER_H = 19;

    private final Screen lastScreen;
    private final Options options;
    private final IgnoredConflicts ignored = IgnoredConflicts.load();

    private KeyboardLayout layout;
    private int unit, kbX, kbY, kbW, kbH;
    private int panelTop, panelBottom, leftX, leftW, rightX, rightW;

    private BindingAnalysis analysis;
    @Nullable private InputConstants.Key selectedKey;
    @Nullable private KeyMapping focused;
    @Nullable private KeyMapping listening;

    // Press/release tracking while listening, same scheme as NeoForge's own KeyBindsScreen,
    // so that "hold Ctrl, press Q, release" yields Ctrl+Q.
    private InputConstants.Key lastPressedKey = InputConstants.UNKNOWN;
    private InputConstants.Key lastPressedModifier = InputConstants.UNKNOWN;
    private boolean keyHeld;
    private boolean modifierHeld;

    private EditBox searchBox;
    private Button resetAllButton;
    private final List<KeyMapping> searchResults = new ArrayList<>();
    private int leftScroll, rightScroll, leftContentH, rightContentH;

    /** Clickable regions registered while rendering, consumed by mouseClicked. */
    private final List<HitBox> hits = new ArrayList<>();

    private record HitBox(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public KeyboardScreen(Screen lastScreen, Options options) {
        super(Component.translatable("keyboardkeybinds.title"));
        this.lastScreen = lastScreen;
        this.options = options;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int avail = width - 2 * MARGIN;
        boolean numpad = true;
        unit = Math.min(22, (int) Math.floor(avail / KeyboardLayout.WIDTH_WITH_NUMPAD));
        if (unit < 14) {
            numpad = false;
            unit = Math.min(22, (int) Math.floor(avail / KeyboardLayout.WIDTH_WITHOUT_NUMPAD));
        }
        unit = Math.max(unit, 9);
        layout = KeyboardLayout.usAnsi(numpad);
        kbW = Math.round(layout.width() * unit);
        kbH = Math.round(layout.height() * unit);
        kbX = (width - kbW) / 2;
        kbY = 18;

        panelTop = kbY + kbH + 16;
        panelBottom = height - 30;
        leftX = MARGIN;
        leftW = (width - 3 * MARGIN) * 3 / 5;
        rightX = leftX + leftW + MARGIN;
        rightW = width - MARGIN - rightX;

        String previous = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(font, rightX + 2, panelTop + 2, rightW - 4, 14, Component.translatable("keyboardkeybinds.search"));
        searchBox.setHint(Component.translatable("keyboardkeybinds.search.hint"));
        searchBox.setMaxLength(64);
        searchBox.setValue(previous);
        searchBox.setResponder(s -> {
            rightScroll = 0;
            refreshSearch();
        });
        addWidget(searchBox); // receives input like any widget, but is rendered manually on top of the panel

        int bw = 80;
        int gap = 6;
        int by = height - 24;
        resetAllButton = addRenderableWidget(Button.builder(Component.translatable("keyboardkeybinds.button.resetAll"), b -> resetAll())
                .bounds(width / 2 - bw - bw / 2 - gap, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("keyboardkeybinds.button.classic"), b -> openClassic())
                .bounds(width / 2 - bw / 2, by, bw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + bw / 2 + gap, by, bw, 20).build());

        recompute();
        refreshSearch();
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Screen.render draws (and blurs) the background first, so everything of ours comes after it.
        resetAllButton.active = anyNonDefault();
        super.render(g, mouseX, mouseY, partialTick);

        hits.clear();
        g.drawCenteredString(font, title, width / 2, 6, TEXT);
        int hard = analysis.hardConflictCount();
        if (hard > 0) {
            Component c = Component.translatable(hard == 1 ? "keyboardkeybinds.legend.conflicts.one" : "keyboardkeybinds.legend.conflicts", hard);
            g.drawString(font, c, width - MARGIN - font.width(c), 6, TEXT_HARD);
        }
        renderKeyboard(g, mouseX, mouseY);
        renderLegend(g);
        renderLeftPanel(g, mouseX, mouseY);
        renderRightPanel(g, mouseX, mouseY);
        searchBox.render(g, mouseX, mouseY, partialTick); // sits inside the right panel, so drawn after it
        renderCellTooltip(g, mouseX, mouseY);
    }

    private void renderKeyboard(GuiGraphics g, int mouseX, int mouseY) {
        KeyboardLayout.Cell hovered = cellAt(mouseX, mouseY);
        InputConstants.Key focusKey = focused != null && !focused.isUnbound() ? focused.getKey() : null;
        for (KeyboardLayout.Cell cell : layout.cells()) {
            int x = kbX + Math.round(cell.x() * unit);
            int y = kbY + Math.round(cell.y() * unit);
            int w = Math.round(cell.w() * unit) - 1;
            int h = Math.round(cell.h() * unit) - 1;
            List<KeyMapping> on = analysis.bindingsOn(cell.key());
            int fill = on.isEmpty() ? FILL_UNBOUND : switch (analysis.severityOf(cell.key())) {
                case HARD -> FILL_HARD;
                case SOFT -> FILL_SOFT;
                default -> FILL_BOUND;
            };
            g.fill(x, y, x + w, y + h, fill);

            int outline = OUTLINE;
            if (cell.key().equals(selectedKey) || cell.key().equals(focusKey)) outline = OUTLINE_SELECTED;
            else if (cell == hovered) outline = OUTLINE_HOVER;
            else if (listening != null) outline = OUTLINE_LISTENING;
            g.renderOutline(x, y, w, h, outline);

            String label = cellLabel(cell);
            label = font.plainSubstrByWidth(label, Math.max(4, w - 3));
            g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, on.isEmpty() ? TEXT_DIM : TEXT);
            if (on.size() > 1) {
                String n = String.valueOf(on.size());
                g.drawString(font, n, x + w - font.width(n) - 1, y + 1, TEXT, false);
            }
        }
    }

    private String cellLabel(KeyboardLayout.Cell cell) {
        if (!cell.useDisplayName()) return cell.label();
        String name = cell.key().getDisplayName().getString();
        if (name.isEmpty() || name.startsWith("key.")) return cell.label();
        return name.toUpperCase(Locale.ROOT);
    }

    private void renderLegend(GuiGraphics g) {
        int y = kbY + kbH + 4;
        int x = kbX;
        x = legendItem(g, x, y, FILL_UNBOUND, "keyboardkeybinds.legend.unbound");
        x = legendItem(g, x, y, FILL_BOUND, "keyboardkeybinds.legend.bound");
        x = legendItem(g, x, y, FILL_SOFT, "keyboardkeybinds.legend.overlap");
        legendItem(g, x, y, FILL_HARD, "keyboardkeybinds.legend.conflict");
    }

    private int legendItem(GuiGraphics g, int x, int y, int color, String key) {
        g.fill(x, y + 1, x + 7, y + 8, color);
        g.renderOutline(x, y + 1, 7, 7, OUTLINE);
        Component c = Component.translatable(key);
        g.drawString(font, c, x + 10, y, TEXT_DIM, false);
        return x + 10 + font.width(c) + 10;
    }

    private void renderLeftPanel(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(leftX, panelTop, leftX + leftW, panelBottom, PANEL_BG);
        g.renderOutline(leftX, panelTop, leftW, panelBottom - panelTop, PANEL_BORDER);

        Component header;
        List<KeyMapping> rows;
        if (focused != null && focused.isUnbound()) {
            header = Component.translatable("keyboardkeybinds.panel.unbound", focused.getDisplayName());
            rows = List.of(focused);
        } else if (selectedKey != null) {
            header = Component.translatable("keyboardkeybinds.panel.key", selectedKey.getDisplayName());
            rows = analysis.bindingsOn(selectedKey);
        } else {
            header = Component.translatable("keyboardkeybinds.hint");
            rows = List.of();
        }
        g.drawString(font, truncate(header.getString(), leftW - 8), leftX + 4, panelTop + 4, TEXT, false);

        int top = panelTop + LEFT_HEADER_H;
        int bottom = panelBottom - 1;
        if (rows.isEmpty() && selectedKey != null) {
            g.drawString(font, Component.translatable("keyboardkeybinds.panel.nothing"), leftX + 4, top + 2, TEXT_DIM, false);
        }
        g.enableScissor(leftX + 1, top, leftX + leftW - 1, bottom);
        int y = top - leftScroll;
        for (KeyMapping km : rows) {
            y = renderBindingRow(g, km, leftX + 4, y, leftW - 8, mouseX, mouseY, top, bottom);
        }
        g.disableScissor();
        leftContentH = y + leftScroll - top;
    }

    private int renderBindingRow(GuiGraphics g, KeyMapping km, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        List<BindingAnalysis.Overlap> overlaps = analysis.overlapsOf(km);
        int h = ROW_H + overlaps.size() * LINE_H;
        if (km == focused) g.fill(x - 2, y, x + w + 2, y + h - 2, ROW_FOCUS);

        int bx = x + w;
        bx = pseudoButtonRight(g, bx, y + 2, Component.translatable("keyboardkeybinds.unbind"), !km.isUnbound(), mouseX, mouseY, clipTop, clipBottom, () -> unbind(km));
        bx = pseudoButtonRight(g, bx - 3, y + 2, Component.translatable("keyboardkeybinds.reset"), !km.isDefault(), mouseX, mouseY, clipTop, clipBottom, () -> reset(km));
        bx = pseudoButtonRight(g, bx - 3, y + 2, Component.translatable("keyboardkeybinds.change"), listening != km, mouseX, mouseY, clipTop, clipBottom, () -> startListening(km));
        int textW = bx - x - 4;

        if (listening == km) {
            g.drawString(font, truncate(Component.translatable("keyboardkeybinds.listening").getString(), textW), x, y + 2, TEXT_LISTEN, false);
        } else {
            g.drawString(font, truncate(km.getDisplayName().getString(), textW), x, y + 2, TEXT, false);
        }
        String info = (km.isUnbound() ? Component.translatable("keyboardkeybinds.unbound") : km.getTranslatedKeyMessage()).getString()
                + "  |  " + Component.translatable(km.getCategory()).getString()
                + "  |  " + BindingAnalysis.contextName(km.getKeyConflictContext()).getString();
        g.drawString(font, truncate(info, w), x, y + 12, TEXT_DIM, false);

        int ly = y + ROW_H - 2;
        for (BindingAnalysis.Overlap o : overlaps) {
            boolean hard = o.severity() == BindingAnalysis.Severity.HARD;
            int lineW = w;
            if (o.ignorable()) {
                Component label = Component.translatable(o.ignored() ? "keyboardkeybinds.intentional.unmark" : "keyboardkeybinds.intentional.mark");
                int left = pseudoButtonRight(g, x + w, ly - 1, label, true, mouseX, mouseY, clipTop, clipBottom, () -> toggleIgnore(km, o.other()));
                lineW = left - x - 6;
            }
            Component line = Component.translatable(hard ? "keyboardkeybinds.conflict" : "keyboardkeybinds.overlap", o.other().getDisplayName(), o.reason());
            g.drawString(font, truncate(line.getString(), lineW), x + 4, ly, hard ? TEXT_HARD : TEXT_SOFT, false);
            ly += LINE_H;
        }
        return y + h + 2;
    }

    /** Draws a small button whose right edge is at {@code right}; returns its left edge. */
    private int pseudoButtonRight(GuiGraphics g, int right, int y, Component label, boolean enabled, int mouseX, int mouseY, int clipTop, int clipBottom, Runnable action) {
        int w = font.width(label) + 8;
        int h = 11;
        int x = right - w;
        int visTop = Math.max(y, clipTop);
        int visBottom = Math.min(y + h, clipBottom);
        boolean visible = visBottom > visTop;
        boolean hover = enabled && visible && mouseX >= x && mouseX < x + w && mouseY >= visTop && mouseY < visBottom;
        g.fill(x, y, x + w, y + h, !enabled ? BTN_DISABLED : hover ? BTN_HOVER : BTN);
        g.renderOutline(x, y, w, h, OUTLINE);
        g.drawCenteredString(font, label, x + w / 2, y + 2, enabled ? TEXT : TEXT_DISABLED);
        if (enabled && visible) hits.add(new HitBox(x, visTop, w, visBottom - visTop, action));
        return x;
    }

    private void renderRightPanel(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(rightX, panelTop, rightX + rightW, panelBottom, PANEL_BG);
        g.renderOutline(rightX, panelTop, rightW, panelBottom - panelTop, PANEL_BORDER);

        int top = panelTop + RIGHT_HEADER_H;
        int bottom = panelBottom - 1;
        g.enableScissor(rightX + 1, top, rightX + rightW - 1, bottom);
        int y = top - rightScroll;
        String lastCategory = null;
        for (KeyMapping km : searchResults) {
            if (!km.getCategory().equals(lastCategory)) {
                lastCategory = km.getCategory();
                if (y + LIST_ROW_H > top && y < bottom) {
                    g.drawString(font, truncate(Component.translatable(lastCategory).getString(), rightW - 8), rightX + 4, y + 1, TEXT_DIM, false);
                }
                y += LIST_ROW_H;
            }
            if (y + LIST_ROW_H > top && y < bottom) {
                int visTop = Math.max(y, top);
                int visBottom = Math.min(y + LIST_ROW_H, bottom);
                boolean hover = mouseX >= rightX && mouseX < rightX + rightW && mouseY >= visTop && mouseY < visBottom;
                if (km == focused) g.fill(rightX + 2, y, rightX + rightW - 2, y + LIST_ROW_H, ROW_FOCUS);
                else if (hover) g.fill(rightX + 2, y, rightX + rightW - 2, y + LIST_ROW_H, ROW_HOVER);

                String keyText = km.isUnbound() ? Component.translatable("keyboardkeybinds.unbound").getString() : km.getTranslatedKeyMessage().getString();
                int keyColor = km.isUnbound() ? TEXT_DISABLED : switch (analysis.severityOf(km)) {
                    case HARD -> TEXT_HARD;
                    case SOFT -> TEXT_SOFT;
                    default -> TEXT_BOUND;
                };
                int kw = font.width(keyText);
                g.drawString(font, keyText, rightX + rightW - 6 - kw, y + 1, keyColor, false);
                g.drawString(font, truncate(km.getDisplayName().getString(), rightW - 16 - kw), rightX + 8, y + 1, TEXT, false);
                hits.add(new HitBox(rightX + 2, visTop, rightW - 4, visBottom - visTop, () -> select(km)));
            }
            y += LIST_ROW_H;
        }
        g.disableScissor();
        rightContentH = y + rightScroll - top;
    }

    private void renderCellTooltip(GuiGraphics g, int mouseX, int mouseY) {
        KeyboardLayout.Cell cell = cellAt(mouseX, mouseY);
        if (cell == null) return;
        List<Component> lines = new ArrayList<>();
        lines.add(cell.key().getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        List<KeyMapping> on = analysis.bindingsOn(cell.key());
        if (on.isEmpty()) {
            lines.add(Component.translatable("keyboardkeybinds.tooltip.unbound").withStyle(ChatFormatting.GRAY));
        }
        for (KeyMapping km : on) {
            ChatFormatting color = switch (analysis.severityOf(km)) {
                case HARD -> ChatFormatting.RED;
                case SOFT -> ChatFormatting.YELLOW;
                default -> ChatFormatting.AQUA;
            };
            String prefix = km.getKeyModifier() != KeyModifier.NONE ? km.getTranslatedKeyMessage().getString() + ": " : "";
            lines.add(Component.literal(prefix).append(km.getDisplayName()).withStyle(color));
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null) {
            KeyboardLayout.Cell cell = cellAt(mouseX, mouseY);
            InputConstants.Key key = cell != null ? cell.key() : InputConstants.Type.MOUSE.getOrCreate(button);
            applyBinding(listening, KeyModifier.getActiveModifier(), key);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (HitBox hit : hits) {
            if (hit.contains(mouseX, mouseY)) {
                unfocusSearch();
                hit.action().run();
                return true;
            }
        }
        KeyboardLayout.Cell cell = cellAt(mouseX, mouseY);
        if (cell != null) {
            unfocusSearch();
            selectedKey = cell.key();
            focused = null;
            leftScroll = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening == null) return super.keyPressed(keyCode, scanCode, modifiers);
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (lastPressedModifier.equals(InputConstants.UNKNOWN) && KeyModifier.isKeyCodeModifier(key)) {
            lastPressedModifier = key;
            modifierHeld = true;
        } else {
            lastPressedKey = key;
            keyHeld = true;
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // Mac emits a RELEASE with scan code 63 for Fn; ignore it like NeoForge does.
        if (listening == null || (Minecraft.ON_OSX && scanCode == 63)) return super.keyReleased(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            applyBinding(listening, KeyModifier.NONE, InputConstants.UNKNOWN);
            return true;
        }
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (lastPressedKey.equals(key)) keyHeld = false;
        else if (lastPressedModifier.equals(key)) modifierHeld = false;
        if (keyHeld || modifierHeld) return true;

        if (!lastPressedKey.equals(InputConstants.UNKNOWN)) {
            applyBinding(listening, KeyModifier.getKeyModifier(lastPressedModifier), lastPressedKey);
        } else if (!lastPressedModifier.equals(InputConstants.UNKNOWN)) {
            applyBinding(listening, KeyModifier.NONE, lastPressedModifier);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inRect(mouseX, mouseY, leftX, panelTop, leftW, panelBottom - panelTop)) {
            int view = panelBottom - 1 - (panelTop + LEFT_HEADER_H);
            leftScroll = Mth.clamp(leftScroll - (int) (scrollY * 16), 0, Math.max(0, leftContentH - view));
            return true;
        }
        if (inRect(mouseX, mouseY, rightX, panelTop, rightW, panelBottom - panelTop)) {
            int view = panelBottom - 1 - (panelTop + RIGHT_HEADER_H);
            rightScroll = Mth.clamp(rightScroll - (int) (scrollY * 16), 0, Math.max(0, rightContentH - view));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        options.save();
        minecraft.setScreen(lastScreen);
        // Back on a key binds list (vanilla or Controlling): rebuild it so its rows show the binds changed here.
        if (lastScreen != null && KeyboardKeybinds.isKeyBindsScreen(lastScreen)) {
            lastScreen.resize(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        }
    }

    // ------------------------------------------------------------------ actions

    private void select(KeyMapping km) {
        focused = km;
        selectedKey = km.isUnbound() ? selectedKey : km.getKey();
        leftScroll = 0;
    }

    private void startListening(KeyMapping km) {
        listening = km;
        focused = km;
        lastPressedKey = InputConstants.UNKNOWN;
        lastPressedModifier = InputConstants.UNKNOWN;
        keyHeld = false;
        modifierHeld = false;
    }

    private void applyBinding(KeyMapping km, KeyModifier modifier, InputConstants.Key key) {
        km.setKeyModifierAndCode(modifier, key);
        options.setKey(km, key); // also calls KeyMapping.resetMapping()
        listening = null;
        lastPressedKey = InputConstants.UNKNOWN;
        lastPressedModifier = InputConstants.UNKNOWN;
        keyHeld = false;
        modifierHeld = false;
        focused = km;
        if (!key.equals(InputConstants.UNKNOWN)) selectedKey = key;
        changed();
    }

    private void reset(KeyMapping km) {
        km.setToDefault();
        focused = km;
        if (!km.isUnbound()) selectedKey = km.getKey();
        changed();
    }

    private void unbind(KeyMapping km) {
        km.setKeyModifierAndCode(KeyModifier.NONE, InputConstants.UNKNOWN);
        options.setKey(km, InputConstants.UNKNOWN);
        focused = km;
        changed();
    }

    private void resetAll() {
        for (KeyMapping km : options.keyMappings) km.setToDefault();
        listening = null;
        changed();
    }

    private void toggleIgnore(KeyMapping a, KeyMapping b) {
        ignored.toggle(a, b);
        recompute();
    }

    /** "List View": back to the list this was opened from; opened directly (key, dev flag), open a fresh list instead. */
    private void openClassic() {
        if (lastScreen != null && KeyboardKeybinds.isKeyBindsScreen(lastScreen)) {
            onClose();
            return;
        }
        options.save();
        minecraft.setScreen(new KeyBindsScreen(lastScreen, options)); // Controlling swaps in its own list here if installed
    }

    private void changed() {
        KeyMapping.resetMapping();
        recompute();
        refreshSearch();
    }

    private void recompute() {
        analysis = BindingAnalysis.compute(options.keyMappings, ignored);
    }

    private void refreshSearch() {
        String q = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        searchResults.clear();
        for (KeyMapping km : analysis.all()) {
            if (q.isEmpty()
                    || km.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || Component.translatable(km.getCategory()).getString().toLowerCase(Locale.ROOT).contains(q)
                    || km.getTranslatedKeyMessage().getString().toLowerCase(Locale.ROOT).contains(q)) {
                searchResults.add(km);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean anyNonDefault() {
        for (KeyMapping km : options.keyMappings) if (!km.isDefault()) return true;
        return false;
    }

    private void unfocusSearch() {
        if (searchBox != null) searchBox.setFocused(false);
        setFocused(null);
    }

    @Nullable
    private KeyboardLayout.Cell cellAt(double mouseX, double mouseY) {
        if (layout == null) return null;
        float ux = (float) (mouseX - kbX) / unit;
        float uy = (float) (mouseY - kbY) / unit;
        for (KeyboardLayout.Cell cell : layout.cells()) {
            if (cell.contains(ux, uy)) return cell;
        }
        return null;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String truncate(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width(".."))) + "..";
    }
}
