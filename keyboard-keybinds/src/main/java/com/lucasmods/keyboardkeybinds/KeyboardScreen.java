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
    private static final int LEFT_HEADER_H = 22;
    private static final int HEADER_BAND = 0xE0141414;
    private static final int RIGHT_HEADER_H = 19;

    private final Screen lastScreen;
    private final Options options;

    private KeyboardLayout layout;
    private int unit, kbX, kbY, kbW, kbH;
    private int panelTop, panelBottom, leftX, leftW, rightX, rightW;

    private BindingAnalysis analysis;
    @Nullable private InputConstants.Key selectedKey;
    @Nullable private KeyMapping focused;
    @Nullable private KeyMapping listening;
    /**
     * The bindings that were on {@link #selectedKey} when it was selected. Bindings changed or unbound from this panel
     * stay listed (dimmed, with their new key) until another key is selected, so a crowded key can be worked down.
     */
    private final List<KeyMapping> snapshot = new ArrayList<>();
    @Nullable private List<Component> pendingTooltip;

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
        pendingTooltip = null;
        renderLeftPanel(g, mouseX, mouseY);
        renderRightPanel(g, mouseX, mouseY);
        searchBox.render(g, mouseX, mouseY, partialTick); // sits inside the right panel, so drawn after it
        renderCellTooltip(g, mouseX, mouseY);
        if (pendingTooltip != null) g.renderComponentTooltip(font, pendingTooltip, mouseX, mouseY);
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

        List<KeyMapping> rows = panelRows();
        // title bar: a darker band, the key as a key-cap chip in its keyboard colour, then count and state
        g.fill(leftX + 1, panelTop + 1, leftX + leftW - 1, panelTop + LEFT_HEADER_H - 2, HEADER_BAND);
        g.fill(leftX + 1, panelTop + LEFT_HEADER_H - 2, leftX + leftW - 1, panelTop + LEFT_HEADER_H - 1, PANEL_BORDER);
        int hx = leftX + 5;
        int hy = panelTop + 4;
        if (selectedKey != null) {
            List<KeyMapping> live = analysis.bindingsOn(selectedKey);
            int conflicts = 0;
            boolean overlap = false;
            for (KeyMapping km : live) {
                BindingAnalysis.Severity s = analysis.severityOf(km);
                if (s == BindingAnalysis.Severity.HARD) conflicts++;
                else if (s == BindingAnalysis.Severity.SOFT) overlap = true;
            }
            int chipFill = live.isEmpty() ? FILL_UNBOUND : conflicts > 0 ? FILL_HARD : overlap ? FILL_SOFT : FILL_BOUND;
            Component keyName = Component.literal(selectedKey.getDisplayName().getString().toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD);
            int chipW = Math.max(18, font.width(keyName) + 10);
            g.fill(hx, hy - 1, hx + chipW, hy + 13, chipFill);
            g.renderOutline(hx, hy - 1, chipW, 14, OUTLINE_SELECTED);
            g.drawCenteredString(font, keyName, hx + chipW / 2, hy + 2, TEXT);
            hx += chipW + 7;

            Component count = live.size() == 1 ? Component.translatable("keyboardkeybinds.panel.bindings.one")
                    : Component.translatable("keyboardkeybinds.panel.bindings", live.size());
            g.drawString(font, count.copy().withStyle(ChatFormatting.BOLD), hx, hy + 2, TEXT, true);
            hx += font.width(count.copy().withStyle(ChatFormatting.BOLD)) + 8;
            if (conflicts > 0) {
                Component c = Component.translatable("keyboardkeybinds.panel.conflict", conflicts).withStyle(ChatFormatting.BOLD);
                g.drawString(font, c, hx, hy + 2, TEXT_HARD, true);
            } else if (overlap) {
                g.drawString(font, Component.translatable("keyboardkeybinds.panel.overlap").withStyle(ChatFormatting.BOLD), hx, hy + 2, TEXT_SOFT, true);
            }
        } else if (!rows.isEmpty()) {
            Component h = Component.translatable("keyboardkeybinds.panel.unbound", rows.get(0).getDisplayName()).withStyle(ChatFormatting.BOLD);
            g.drawString(font, truncate(h.getString(), leftW - 10), hx, hy + 2, TEXT, true);
        } else {
            g.drawString(font, truncate(Component.translatable("keyboardkeybinds.hint").getString(), leftW - 10), hx, hy + 2, TEXT_DIM, false);
        }

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

    /** Snapshot rows first (in their original order), then anything that has since arrived on the selected key. */
    private List<KeyMapping> panelRows() {
        List<KeyMapping> rows = new ArrayList<>(snapshot);
        if (selectedKey != null) {
            for (KeyMapping km : analysis.bindingsOn(selectedKey)) if (!rows.contains(km)) rows.add(km);
        }
        return rows;
    }

    /** True while the binding is still on the selected key (false once it has been moved away or unbound here). */
    private boolean stillHere(KeyMapping km) {
        return selectedKey == null ? km.isUnbound() || km == focused : !km.isUnbound() && km.getKey().equals(selectedKey);
    }

    private int renderBindingRow(GuiGraphics g, KeyMapping km, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        boolean here = stillHere(km);
        int bx = x + w;
        bx = pseudoButtonRight(g, bx, y + 2, Component.translatable("keyboardkeybinds.only"), here && othersHere(km),
                mouseX, mouseY, clipTop, clipBottom, () -> keepOnly(km));
        bx = pseudoButtonRight(g, bx - 3, y + 2, Component.translatable("keyboardkeybinds.unbind"), !km.isUnbound(),
                mouseX, mouseY, clipTop, clipBottom, () -> unbind(km));
        bx = pseudoButtonRight(g, bx - 3, y + 2, Component.translatable("keyboardkeybinds.reset"), !km.isDefault(),
                mouseX, mouseY, clipTop, clipBottom, () -> reset(km));
        bx = pseudoButtonRight(g, bx - 3, y + 2, Component.translatable("keyboardkeybinds.change"), listening != km,
                mouseX, mouseY, clipTop, clipBottom, () -> startListening(km));

        int dotX = x;
        int textX = x + 9;
        int textW = bx - textX - 4;
        Component name = listening == km ? Component.translatable("keyboardkeybinds.listening") : km.getDisplayName();
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(name, Math.max(20, textW));
        int nameH = Math.max(1, lines.size()) * LINE_H;
        int h = Math.max(ROW_H, nameH + LINE_H + 4);
        if (km == focused) g.fill(x - 2, y, x + w + 2, y + h - 2, ROW_FOCUS);

        // status dot: red = conflicts with another binding, yellow = shares the key harmlessly
        BindingAnalysis.Severity sev = here ? analysis.severityOf(km) : BindingAnalysis.Severity.NONE;
        if (sev != BindingAnalysis.Severity.NONE) {
            int c = sev == BindingAnalysis.Severity.HARD ? FILL_HARD : FILL_SOFT;
            g.fill(dotX, y + 3, dotX + 6, y + 9, c);
            g.renderOutline(dotX, y + 3, 6, 6, OUTLINE);
        }
        int color = listening == km ? TEXT_LISTEN : here ? TEXT : TEXT_DIM;
        int ly = y + 2;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawString(font, line, textX, ly, color, false);
            ly += LINE_H;
        }
        String keyText = km.isUnbound() ? Component.translatable("keyboardkeybinds.unbound").getString() : km.getTranslatedKeyMessage().getString();
        String info = (here ? keyText : "→ " + keyText)
                + "  |  " + Component.translatable(km.getCategory()).getString()
                + "  |  " + BindingAnalysis.contextName(km.getKeyConflictContext()).getString();
        g.drawString(font, truncate(info, w - 9), textX, y + 2 + nameH, here ? TEXT_DIM : TEXT_BOUND, false);

        // hover: everything about this binding, including why it shares the key with the others
        boolean hover = mouseX >= x && mouseX < bx - 2 && mouseY >= Math.max(y, clipTop) && mouseY < Math.min(y + h - 2, clipBottom);
        if (hover && listening == null) {
            List<Component> tip = new ArrayList<>();
            tip.add(km.getDisplayName().copy().withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal(keyText + "  |  " + Component.translatable(km.getCategory()).getString()
                    + "  |  " + BindingAnalysis.contextName(km.getKeyConflictContext()).getString()).withStyle(ChatFormatting.GRAY));
            if (here) {
                for (BindingAnalysis.Overlap o : analysis.overlapsOf(km)) {
                    ChatFormatting f = o.severity() == BindingAnalysis.Severity.HARD ? ChatFormatting.RED : ChatFormatting.YELLOW;
                    tip.add(Component.literal("• ").append(o.other().getDisplayName()).append(": ").append(o.reason()).withStyle(f));
                }
            }
            pendingTooltip = tip;
        }
        return y + h + 2;
    }

    private boolean othersHere(KeyMapping km) {
        if (selectedKey == null) return false;
        for (KeyMapping other : analysis.bindingsOn(selectedKey)) if (other != km) return true;
        return false;
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
            focused = null;
            selectKey(cell.key());
            return true;
        }
        return false;
    }

    /** Select a key and freeze its current bindings as the panel's list. */
    private void selectKey(@Nullable InputConstants.Key key) {
        selectedKey = key;
        snapshot.clear();
        if (key != null) snapshot.addAll(analysis.bindingsOn(key));
        leftScroll = 0;
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
        if (km.isUnbound()) {
            selectKey(null);
            snapshot.add(km);
        } else {
            selectKey(km.getKey());
        }
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
        focused = km;   // the panel stays on the selected key; the row now shows "-> new key"
        changed();
    }

    private void reset(KeyMapping km) {
        km.setToDefault();
        options.setKey(km, km.getKey());
        focused = km;
        changed();
    }

    /** Keep this binding on the selected key and unbind every other binding there. */
    private void keepOnly(KeyMapping keep) {
        if (selectedKey == null) return;
        for (KeyMapping other : new ArrayList<>(analysis.bindingsOn(selectedKey))) {
            if (other == keep) continue;
            other.setKeyModifierAndCode(KeyModifier.NONE, InputConstants.UNKNOWN);
            options.setKey(other, InputConstants.UNKNOWN);
        }
        focused = keep;
        changed();
    }

    private void unbind(KeyMapping km) {
        km.setKeyModifierAndCode(KeyModifier.NONE, InputConstants.UNKNOWN);
        options.setKey(km, InputConstants.UNKNOWN);
        focused = km;
        changed();
    }

    private void resetAll() {
        for (KeyMapping km : options.keyMappings) {
            km.setToDefault();
            options.setKey(km, km.getKey());
        }
        listening = null;
        changed();
        selectKey(selectedKey); // the whole layout changed: start the panel's list over
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
        analysis = BindingAnalysis.compute(options.keyMappings);
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
