package com.lucasmods.keyboardkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A physical keyboard described in "key units" (one unit = the width of a letter key).
 * Positions are the US ANSI layout. Key codes are GLFW codes, which identify the physical
 * position of a key regardless of the user's keyboard language, so on a non-US layout the
 * label of a printable key (taken from {@link InputConstants.Key#getDisplayName()}) shows
 * what that physical key actually types.
 */
public final class KeyboardLayout {

    /**
     * @param key            the input this cell represents
     * @param label          short fallback label
     * @param useDisplayName true for printable keys: show the OS-provided key name instead of {@code label}
     */
    public record Cell(InputConstants.Key key, String label, float x, float y, float w, float h, boolean useDisplayName) {
        public boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Cell> cells;
    private final float width;
    private final float height;

    private KeyboardLayout(List<Cell> cells, float width, float height) {
        this.cells = Collections.unmodifiableList(cells);
        this.width = width;
        this.height = height;
    }

    public List<Cell> cells() { return cells; }
    public float width() { return width; }
    public float height() { return height; }

    public Cell cellFor(InputConstants.Key key) {
        for (Cell cell : cells) {
            if (cell.key.equals(key)) return cell;
        }
        return null;
    }

    /** Full-size width in units including the numpad. */
    public static final float WIDTH_WITH_NUMPAD = 22.5f;
    /** Width in units with the numpad column removed. */
    public static final float WIDTH_WITHOUT_NUMPAD = 18.25f;
    public static final float HEIGHT = 6.5f;

    public static KeyboardLayout usAnsi(boolean numpad) {
        Builder b = new Builder();

        // Function row
        b.key(GLFW.GLFW_KEY_ESCAPE, "Esc", 0, 0);
        float fx = 2;
        for (int i = 0; i < 12; i++) {
            if (i == 4 || i == 8) fx += 0.5f;
            b.key(GLFW.GLFW_KEY_F1 + i, "F" + (i + 1), fx, 0);
            fx += 1;
        }
        b.key(GLFW.GLFW_KEY_PRINT_SCREEN, "PrtSc", 15.25f, 0);
        b.key(GLFW.GLFW_KEY_SCROLL_LOCK, "ScrLk", 16.25f, 0);
        b.key(GLFW.GLFW_KEY_PAUSE, "Pause", 17.25f, 0);

        // Mouse buttons live in the otherwise empty numpad slot of the function row.
        // Without a numpad they squeeze into the empty home-row slot of the nav cluster.
        float mx = numpad ? 18.5f : 15.25f;
        float my = numpad ? 0 : 3.5f;
        float mw = numpad ? 0.8f : 0.6f;
        for (int i = 0; i < 5; i++) {
            b.mouse(i, "M" + (i + 1), mx + i * mw, my, mw, 1);
        }

        // Number row
        float y = 1.5f;
        b.printable(GLFW.GLFW_KEY_GRAVE_ACCENT, "`", 0, y);
        for (int i = 1; i <= 9; i++) b.printable(GLFW.GLFW_KEY_0 + i, String.valueOf(i), i, y);
        b.printable(GLFW.GLFW_KEY_0, "0", 10, y);
        b.printable(GLFW.GLFW_KEY_MINUS, "-", 11, y);
        b.printable(GLFW.GLFW_KEY_EQUAL, "=", 12, y);
        b.key(GLFW.GLFW_KEY_BACKSPACE, "Bksp", 13, y, 2, 1);
        b.key(GLFW.GLFW_KEY_INSERT, "Ins", 15.25f, y);
        b.key(GLFW.GLFW_KEY_HOME, "Home", 16.25f, y);
        b.key(GLFW.GLFW_KEY_PAGE_UP, "PgUp", 17.25f, y);
        if (numpad) {
            b.key(GLFW.GLFW_KEY_NUM_LOCK, "NumLk", 18.5f, y);
            b.key(GLFW.GLFW_KEY_KP_DIVIDE, "/", 19.5f, y);
            b.key(GLFW.GLFW_KEY_KP_MULTIPLY, "*", 20.5f, y);
            b.key(GLFW.GLFW_KEY_KP_SUBTRACT, "-", 21.5f, y);
        }

        // Tab row
        y = 2.5f;
        b.key(GLFW.GLFW_KEY_TAB, "Tab", 0, y, 1.5f, 1);
        String top = "QWERTYUIOP";
        for (int i = 0; i < top.length(); i++) b.printable(letter(top.charAt(i)), String.valueOf(top.charAt(i)), 1.5f + i, y);
        b.printable(GLFW.GLFW_KEY_LEFT_BRACKET, "[", 11.5f, y);
        b.printable(GLFW.GLFW_KEY_RIGHT_BRACKET, "]", 12.5f, y);
        b.printable(GLFW.GLFW_KEY_BACKSLASH, "\\", 13.5f, y, 1.5f, 1);
        b.key(GLFW.GLFW_KEY_DELETE, "Del", 15.25f, y);
        b.key(GLFW.GLFW_KEY_END, "End", 16.25f, y);
        b.key(GLFW.GLFW_KEY_PAGE_DOWN, "PgDn", 17.25f, y);
        if (numpad) {
            b.key(GLFW.GLFW_KEY_KP_7, "7", 18.5f, y);
            b.key(GLFW.GLFW_KEY_KP_8, "8", 19.5f, y);
            b.key(GLFW.GLFW_KEY_KP_9, "9", 20.5f, y);
            b.key(GLFW.GLFW_KEY_KP_ADD, "+", 21.5f, y, 1, 2);
        }

        // Home row
        y = 3.5f;
        b.key(GLFW.GLFW_KEY_CAPS_LOCK, "Caps", 0, y, 1.75f, 1);
        String home = "ASDFGHJKL";
        for (int i = 0; i < home.length(); i++) b.printable(letter(home.charAt(i)), String.valueOf(home.charAt(i)), 1.75f + i, y);
        b.printable(GLFW.GLFW_KEY_SEMICOLON, ";", 10.75f, y);
        b.printable(GLFW.GLFW_KEY_APOSTROPHE, "'", 11.75f, y);
        b.key(GLFW.GLFW_KEY_ENTER, "Enter", 12.75f, y, 2.25f, 1);
        if (numpad) {
            b.key(GLFW.GLFW_KEY_KP_4, "4", 18.5f, y);
            b.key(GLFW.GLFW_KEY_KP_5, "5", 19.5f, y);
            b.key(GLFW.GLFW_KEY_KP_6, "6", 20.5f, y);
        }

        // Shift row
        y = 4.5f;
        b.key(GLFW.GLFW_KEY_LEFT_SHIFT, "Shift", 0, y, 2.25f, 1);
        String bottom = "ZXCVBNM";
        for (int i = 0; i < bottom.length(); i++) b.printable(letter(bottom.charAt(i)), String.valueOf(bottom.charAt(i)), 2.25f + i, y);
        b.printable(GLFW.GLFW_KEY_COMMA, ",", 9.25f, y);
        b.printable(GLFW.GLFW_KEY_PERIOD, ".", 10.25f, y);
        b.printable(GLFW.GLFW_KEY_SLASH, "/", 11.25f, y);
        b.key(GLFW.GLFW_KEY_RIGHT_SHIFT, "Shift", 12.25f, y, 2.75f, 1);
        b.key(GLFW.GLFW_KEY_UP, "Up", 16.25f, y);
        if (numpad) {
            b.key(GLFW.GLFW_KEY_KP_1, "1", 18.5f, y);
            b.key(GLFW.GLFW_KEY_KP_2, "2", 19.5f, y);
            b.key(GLFW.GLFW_KEY_KP_3, "3", 20.5f, y);
            b.key(GLFW.GLFW_KEY_KP_ENTER, "Ent", 21.5f, y, 1, 2);
        }

        // Bottom row
        y = 5.5f;
        b.key(GLFW.GLFW_KEY_LEFT_CONTROL, "Ctrl", 0, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_LEFT_SUPER, "Win", 1.25f, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_LEFT_ALT, "Alt", 2.5f, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_SPACE, "Space", 3.75f, y, 6.25f, 1);
        b.key(GLFW.GLFW_KEY_RIGHT_ALT, "Alt", 10, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_RIGHT_SUPER, "Win", 11.25f, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_MENU, "Menu", 12.5f, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_RIGHT_CONTROL, "Ctrl", 13.75f, y, 1.25f, 1);
        b.key(GLFW.GLFW_KEY_LEFT, "Left", 15.25f, y);
        b.key(GLFW.GLFW_KEY_DOWN, "Down", 16.25f, y);
        b.key(GLFW.GLFW_KEY_RIGHT, "Right", 17.25f, y);
        if (numpad) {
            b.key(GLFW.GLFW_KEY_KP_0, "0", 18.5f, y, 2, 1);
            b.key(GLFW.GLFW_KEY_KP_DECIMAL, ".", 20.5f, y);
        }

        return new KeyboardLayout(b.cells, numpad ? WIDTH_WITH_NUMPAD : WIDTH_WITHOUT_NUMPAD, HEIGHT);
    }

    /** GLFW letter codes are contiguous and alphabetical (they equal the ASCII upper-case code). */
    private static int letter(char c) {
        return GLFW.GLFW_KEY_A + (c - 'A');
    }

    private static final class Builder {
        final List<Cell> cells = new ArrayList<>();

        void key(int glfw, String label, float x, float y) { key(glfw, label, x, y, 1, 1); }

        void key(int glfw, String label, float x, float y, float w, float h) {
            cells.add(new Cell(InputConstants.Type.KEYSYM.getOrCreate(glfw), label, x, y, w, h, false));
        }

        void printable(int glfw, String label, float x, float y) { printable(glfw, label, x, y, 1, 1); }

        void printable(int glfw, String label, float x, float y, float w, float h) {
            cells.add(new Cell(InputConstants.Type.KEYSYM.getOrCreate(glfw), label, x, y, w, h, true));
        }

        void mouse(int button, String label, float x, float y, float w, float h) {
            cells.add(new Cell(InputConstants.Type.MOUSE.getOrCreate(button), label, x, y, w, h, false));
        }
    }
}
