package com.lucasmods.devdriver;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Executes a script of client actions, one per tick, so the game can be exercised and
 * screenshotted without a human (or an OS-level input injector) at the keyboard.
 *
 * <p>Enable with {@code -Ddevdriver.script=<file>}. One command per line, {@code #} comments:
 * <pre>
 * wait N                 pause N ticks (20 per second)
 * screenshot NAME        save run/screenshots/NAME.png with the game's own screenshot code
 * move X Y               put the (fake) mouse at GUI coordinates X,Y
 * click X Y [BUTTON]     move there and click (button 0 = left)
 * key NAME               press and release a key on the current screen, e.g. key ENTER, key W, key ESCAPE
 * type TEXT              type text into the current screen
 * screen title|keybinds|inventory|none
 * world NAME             create a fresh creative world with cheats on and join it
 * waitworld              block until the player is in a world and no screen is open
 * chat TEXT              send a chat message, or a command when TEXT starts with /
 * respawn                click the Respawn button on the death screen
 * log TEXT               log TEXT; placeholders {xp} {items} {screen} {health} {hotbar}
 * expect PLACEHOLDER V   log PASS/FAIL comparing a placeholder with V
 * quit                   close the game
 * </pre>
 */
@Mod(value = DevDriver.MODID, dist = Dist.CLIENT)
public final class DevDriver {
    public static final String MODID = "devdriver";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[DevDriver]";

    private final List<String> lines = new ArrayList<>();
    private int pc = 0;
    private int waitTicks = 60; // let the title screen settle first
    private Predicate<Minecraft> waitFor = null;
    private int waitForTimeout = 0;
    private boolean done = false;
    private int failures = 0;

    public DevDriver(IEventBus modBus, ModContainer container) {
        String path = System.getProperty("devdriver.script");
        if (path == null || path.isBlank()) {
            LOGGER.info("{} idle: no -Ddevdriver.script given", TAG);
            return;
        }
        try {
            for (String raw : Files.readAllLines(Path.of(path))) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                lines.add(line);
            }
        } catch (IOException e) {
            LOGGER.error("{} cannot read script {}", TAG, path, e);
            return;
        }
        LOGGER.info("{} loaded {} commands from {}", TAG, lines.size(), path);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(ClientTickEvent.Post event) {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (waitFor != null) {
            if (!waitFor.test(mc)) {
                if (--waitForTimeout <= 0) {
                    LOGGER.error("{} timed out waiting for condition before line {}", TAG, pc + 1);
                    failures++;
                    waitFor = null;
                }
                return;
            }
            waitFor = null;
        }
        if (pc >= lines.size()) {
            done = true;
            LOGGER.info("{} script finished with {} failure(s)", TAG, failures);
            return;
        }
        String line = lines.get(pc++);
        LOGGER.info("{} > {}", TAG, line);
        try {
            execute(mc, line);
        } catch (Exception e) {
            failures++;
            LOGGER.error("{} command failed: {}", TAG, line, e);
        }
    }

    private void execute(Minecraft mc, String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");

        switch (cmd) {
            case "wait" -> waitTicks = Integer.parseInt(args[0]);
            case "screenshot" -> Screenshot.grab(mc.gameDirectory, rest + ".png", mc.getMainRenderTarget(),
                    msg -> LOGGER.info("{} screenshot: {}", TAG, msg.getString()));
            case "move" -> move(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]));
            case "click" -> click(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 0);
            case "key" -> key(mc, args[0]);
            case "type" -> {
                Screen s = requireScreen(mc);
                for (char c : rest.toCharArray()) s.charTyped(c, 0);
            }
            case "screen" -> screen(mc, args[0]);
            case "world" -> {
                CreateWorldScreen.openFresh(mc, null);
                lines.add(pc, "_createworld " + rest);
                await(m -> m.screen instanceof CreateWorldScreen, 600);
            }
            case "_createworld" -> {
                CreateWorldScreen s = (CreateWorldScreen) requireScreen(mc);
                WorldCreationUiState ui = s.getUiState();
                ui.setName(rest);
                ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                ui.setAllowCommands(true);
                s.onCreate(); // made public by the access transformer
                lines.add(pc, "waitworld");
            }
            case "waitworld" -> await(m -> m.player != null && m.level != null && m.screen == null && m.player.tickCount > 20, 20 * 180);
            case "chat" -> {
                if (rest.startsWith("/")) mc.player.connection.sendCommand(rest.substring(1));
                else mc.player.connection.sendChat(rest);
            }
            case "respawn" -> {
                Screen s = requireScreen(mc);
                if (!(s instanceof DeathScreen)) throw new IllegalStateException("not on the death screen but on " + s.getClass().getSimpleName());
                click(mc, s.width / 2.0, s.height / 4.0 + 82, 0);
            }
            case "log" -> LOGGER.info("{} {}", TAG, expand(mc, rest));
            case "expect" -> {
                String actual = expand(mc, "{" + args[0] + "}");
                String expected = args[1];
                if (actual.equals(expected)) {
                    LOGGER.info("{} PASS {} == {}", TAG, args[0], expected);
                } else {
                    failures++;
                    LOGGER.error("{} FAIL {} expected {} but was {}", TAG, args[0], expected, actual);
                }
            }
            case "quit" -> {
                LOGGER.info("{} quitting with {} failure(s)", TAG, failures);
                mc.stop();
            }
            default -> throw new IllegalArgumentException("unknown command " + cmd);
        }
    }

    private void await(Predicate<Minecraft> condition, int timeoutTicks) {
        waitFor = condition;
        waitForTimeout = timeoutTicks;
    }

    private static Screen requireScreen(Minecraft mc) {
        if (mc.screen == null) throw new IllegalStateException("no screen is open");
        return mc.screen;
    }

    private static void move(Minecraft mc, double x, double y) {
        double scale = mc.getWindow().getGuiScale();
        mc.mouseHandler.xpos = x * scale; // made public by the access transformer
        mc.mouseHandler.ypos = y * scale;
        if (mc.screen != null) mc.screen.mouseMoved(x, y);
    }

    private static void click(Minecraft mc, double x, double y, int button) {
        move(mc, x, y);
        Screen s = requireScreen(mc);
        s.mouseClicked(x, y, button);
        s.mouseReleased(x, y, button);
    }

    private static void key(Minecraft mc, String name) {
        Screen s = requireScreen(mc);
        int code;
        try {
            code = Integer.parseInt(name);
        } catch (NumberFormatException e) {
            code = InputConstants.getKey("key.keyboard." + name.toLowerCase(Locale.ROOT).replace('_', '.')).getValue();
        }
        s.keyPressed(code, 0, 0);
        s.keyReleased(code, 0, 0);
    }

    private static void screen(Minecraft mc, String which) {
        switch (which.toLowerCase(Locale.ROOT)) {
            case "none" -> mc.setScreen(null);
            case "title" -> mc.setScreen(new TitleScreen());
            case "keybinds" -> mc.setScreen(new KeyBindsScreen(mc.screen, mc.options));
            case "inventory" -> mc.setScreen(new InventoryScreen(mc.player));
            default -> throw new IllegalArgumentException("unknown screen " + which);
        }
    }

    private static String expand(Minecraft mc, String text) {
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        text = text.replace("{screen}", screen);
        if (mc.player == null) return text.replace("{xp}", "?").replace("{items}", "?").replace("{health}", "?").replace("{hotbar}", "?");
        int items = 0;
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) items++;
            if (i < 9) hotbar.append(i > 0 ? "," : "").append(stack.isEmpty() ? "-" : stack.getCount() + "x" + stack.getItem());
        }
        return text.replace("{xp}", String.valueOf(mc.player.experienceLevel))
                .replace("{items}", String.valueOf(items))
                .replace("{health}", String.valueOf((int) mc.player.getHealth()))
                .replace("{hotbar}", hotbar.toString());
    }
}
