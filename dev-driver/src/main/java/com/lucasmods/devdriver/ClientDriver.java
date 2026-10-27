package com.lucasmods.devdriver;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

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
 * <p>While a script runs the game window is hidden (unless {@code -Ddevdriver.visible=true}), so it
 * never takes keyboard focus, never grabs the mouse and never appears on screen; screenshots are
 * taken from the game's own framebuffer. The window is shown again when the script finishes.
 *
 * <p>Enable with {@code -Ddevdriver.script=<file>}. One command per line, {@code #} comments:
 * <pre>
 * wait N                 pause N ticks (20 per second)
 * screenshot NAME        save run/screenshots/NAME.png with the game's own screenshot code
 * move X Y               put the (fake) mouse at GUI coordinates X,Y
 * click X Y [BUTTON]     move there and click (button 0 = left)
 * key NAME               press and release a key on the current screen, e.g. key ENTER, key W, key ESCAPE
 * type TEXT              type text into the current screen
 * screen title|keybinds|inventory|advancements|checklist|none
 * scroll X Y AMOUNT      mouse-wheel at X,Y (negative = down)
 * waittitle              block until mod loading is complete and the title screen is up (big packs take minutes)
 * world NAME             create a fresh creative world with cheats on and join it
 * connect HOST:PORT      join a multiplayer server and wait until in-world
 * waitworld              block until the player is in a world and no screen is open
 * chat TEXT              send a chat message, or a command when TEXT starts with /
 * respawn                click the Respawn button on the death screen
 * log TEXT               log TEXT; placeholders {xp} {items} {screen} {health} {hotbar} {entities} {maxhealth} {speed}
 * expect PLACEHOLDER V   log PASS/FAIL comparing a placeholder with V
 * disconnect             leave the server/world (pause-menu Disconnect)
 * openaccessories        open the Accessories mod's screen
 * slots                  log the open container's non-inventory slots (Curios/Accessories names, items)
 * clickslot inv N | clickslot name NAME [k]   left-click a slot through the server (pick up / place)
 * waitfile PATH [ticks]  wait until a file exists (outside signal)
 * widgets                log every widget on the current screen
 * clickwidget TEXT       click the first widget whose class/message/tooltip contains TEXT
 * gochest                /tp beside the nearest chest within 32 blocks
 * usechest               right-click the nearest chest within 6 blocks (opens it)
 * show / hide            show or hide the game window
 * dumpchannels FILE      write every registered network payload channel to FILE (relative to the game dir)
 * quit                   close the game
 * </pre>
 */
public final class ClientDriver {
    private final List<String> lines = new ArrayList<>();
    private int pc = 0;
    private int waitTicks = 60; // let the title screen settle first
    private Predicate<Minecraft> waitFor = null;
    private int waitForTimeout = 0;
    private boolean done = false;
    private int failures = 0;
    private boolean parked = false;
    private final boolean keepVisible = Boolean.getBoolean("devdriver.visible");

    static void register(String path) {
        if (path == null || path.isBlank()) {
            DevDriverMod.LOGGER.info("{} idle: no -Ddevdriver.script given", DevDriverMod.TAG);
            return;
        }
        ClientDriver driver = new ClientDriver();
        try {
            for (String raw : Files.readAllLines(Path.of(path))) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                driver.lines.add(line);
            }
        } catch (IOException e) {
            DevDriverMod.LOGGER.error("{} cannot read script {}", DevDriverMod.TAG, path, e);
            return;
        }
        DevDriverMod.LOGGER.info("{} loaded {} commands from {}", DevDriverMod.TAG, driver.lines.size(), path);
        NeoForge.EVENT_BUS.addListener(driver::onTick);
    }

    private void onTick(ClientTickEvent.Post event) {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (!parked) {
            parked = true;
            if (!keepVisible) setWindowVisible(mc, false);
        }
        // Never trap the real cursor while a script is running, whatever focus the window has.
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (waitFor != null) {
            if (!waitFor.test(mc)) {
                if (--waitForTimeout <= 0) {
                    DevDriverMod.LOGGER.error("{} timed out waiting for condition before line {}", DevDriverMod.TAG, pc + 1);
                    failures++;
                    waitFor = null;
                }
                return;
            }
            waitFor = null;
        }
        if (pc >= lines.size()) {
            done = true;
            DevDriverMod.LOGGER.info("{} script finished with {} failure(s)", DevDriverMod.TAG, failures);
            setWindowVisible(mc, true);
            return;
        }
        String line = lines.get(pc++);
        DevDriverMod.LOGGER.info("{} > {}", DevDriverMod.TAG, line);
        try {
            execute(mc, line);
        } catch (Exception e) {
            failures++;
            DevDriverMod.LOGGER.error("{} command failed: {}", DevDriverMod.TAG, line, e);
        }
    }

    private static void setWindowVisible(Minecraft mc, boolean visible) {
        long handle = mc.getWindow().getWindow();
        if (visible) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwShowWindow(handle);
        } else {
            GLFW.glfwHideWindow(handle);
        }
        DevDriverMod.LOGGER.info("{} game window {}", DevDriverMod.TAG, visible ? "shown" : "hidden (background mode)");
    }

    private void execute(Minecraft mc, String line) throws Exception {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");

        switch (cmd) {
            case "wait" -> waitTicks = Integer.parseInt(args[0]);
            case "screenshot" -> Screenshot.grab(mc.gameDirectory, rest + ".png", mc.getMainRenderTarget(),
                    msg -> DevDriverMod.LOGGER.info("{} screenshot: {}", DevDriverMod.TAG, msg.getString()));
            case "move" -> move(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]));
            case "click" -> click(mc, Double.parseDouble(args[0]), Double.parseDouble(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 0);
            case "key" -> key(mc, args[0]);
            case "type" -> {
                Screen s = requireScreen(mc);
                for (char c : rest.toCharArray()) s.charTyped(c, 0);
            }
            case "screen" -> screen(mc, args[0]);
            case "connect" -> {
                String address = args[0];
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                        new net.minecraft.client.multiplayer.ServerData("dev test server", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
                lines.add(pc, "waitworld");
            }
            case "world" -> {
                CreateWorldScreen.openFresh(mc, null);
                lines.add(pc, "_createworld " + rest);
                await(m -> m.screen instanceof CreateWorldScreen, 20 * 300);
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
            case "waittitle" -> await(m -> !net.neoforged.neoforge.client.loading.ClientModLoader.isLoading() && m.getOverlay() == null && m.screen != null, 20 * 900); // packs replace the title screen class
            case "waitworld" -> await(m -> m.player != null && m.level != null && m.screen == null && m.player.tickCount > 20, 20 * 900);
            case "chat" -> {
                if (rest.startsWith("/")) mc.player.connection.sendCommand(rest.substring(1));
                else mc.player.connection.sendChat(rest);
            }
            case "respawn" -> {
                Screen s = requireScreen(mc);
                if (!(s instanceof DeathScreen)) throw new IllegalStateException("not on the death screen but on " + s.getClass().getSimpleName());
                click(mc, s.width / 2.0, s.height / 4.0 + 82, 0);
            }
            case "log" -> DevDriverMod.LOGGER.info("{} {}", DevDriverMod.TAG, expand(mc, rest));
            case "expect" -> {
                String actual = expand(mc, "{" + args[0] + "}");
                String expected = args[1];
                if (actual.equals(expected)) {
                    DevDriverMod.LOGGER.info("{} PASS {} == {}", DevDriverMod.TAG, args[0], expected);
                } else {
                    failures++;
                    DevDriverMod.LOGGER.error("{} FAIL {} expected {} but was {}", DevDriverMod.TAG, args[0], expected, actual);
                }
            }
            case "scroll" -> {
                Screen s = requireScreen(mc);
                double sx = Double.parseDouble(args[0]), sy = Double.parseDouble(args[1]);
                move(mc, sx, sy);
                s.mouseScrolled(sx, sy, 0, Double.parseDouble(args[2]));
            }
            case "dumpchannels" -> {
                java.nio.file.Path out = mc.gameDirectory.toPath().resolve(rest.trim());
                int n = ChannelDump.dump(out);
                DevDriverMod.LOGGER.info("{} dumped {} payload channels to {}", DevDriverMod.TAG, n, out);
            }
            case "gochest" -> {
                // teleport (via /tp, needs cheats) to the block south of the nearest chest within 32 blocks
                net.minecraft.core.BlockPos chest = nearestChest(mc, 32);
                DevDriverMod.LOGGER.info("{} going to chest at {}", DevDriverMod.TAG, chest.toShortString());
                mc.player.connection.sendCommand("tp @s " + (chest.getX() + 0.5) + " " + chest.getY() + " " + (chest.getZ() + 1.5) + " 180 30");
            }
            case "usechest" -> {
                // right-click the nearest chest within 6 blocks (opens its container screen)
                net.minecraft.core.BlockPos chest = nearestChest(mc, 6);
                DevDriverMod.LOGGER.info("{} using chest at {}", DevDriverMod.TAG, chest.toShortString());
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(chest), net.minecraft.core.Direction.UP, chest, false));
            }
            case "waitfile" -> { // waitfile PATH [maxTicks]
                java.nio.file.Path path = java.nio.file.Path.of(args[0]);
                await(m -> java.nio.file.Files.exists(path), args.length > 1 ? Integer.parseInt(args[1]) : 20 * 1800);
            }
            case "widgets" -> { // log every widget of the current screen: class, bounds, message
                Screen screen = requireScreen(mc);
                List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
                collectWidgets(screen, widgets);
                DevDriverMod.LOGGER.info("{} {} has {} widget(s)", DevDriverMod.TAG, screen.getClass().getSimpleName(), widgets.size());
                for (var w : widgets) {
                    DevDriverMod.LOGGER.info("{}   {} at {},{} {}x{} '{}' tooltip='{}'", DevDriverMod.TAG, w.getClass().getName(),
                            w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.getMessage().getString(), widgetTooltip(w));
                }
            }
            case "clickwidget" -> { // clickwidget SUBSTRING: click the centre of the first widget whose class name, message or tooltip contains it
                Screen screen = requireScreen(mc);
                List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
                collectWidgets(screen, widgets);
                String needle = rest.trim().toLowerCase(java.util.Locale.ROOT);
                var hit = widgets.stream().filter(w -> (w.getClass().getName() + " " + w.getMessage().getString() + " " + widgetTooltip(w))
                        .toLowerCase(java.util.Locale.ROOT).contains(needle)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("no widget matching '" + needle + "' on " + screen.getClass().getSimpleName()));
                DevDriverMod.LOGGER.info("{} clicking {} '{}' at {},{}", DevDriverMod.TAG, hit.getClass().getSimpleName(), hit.getMessage().getString(),
                        hit.getX() + hit.getWidth() / 2.0, hit.getY() + hit.getHeight() / 2.0);
                click(mc, hit.getX() + hit.getWidth() / 2.0, hit.getY() + hit.getHeight() / 2.0, 0);
            }
            case "openaccessories" -> { // open the Accessories mod's own screen (reflection, so the driver has no dependency)
                Object ok = Class.forName("io.wispforest.accessories.client.AccessoriesClient").getMethod("attemptToOpenScreen").invoke(null);
                DevDriverMod.LOGGER.info("{} AccessoriesClient.attemptToOpenScreen() -> {}", DevDriverMod.TAG, ok);
            }
            case "slots" -> { // log every slot of the open container menu that is not the player's own 36-slot inventory
                var screen = requireScreen(mc);
                if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs)) throw new IllegalStateException("not a container screen");
                var menu = cs.getMenu();
                int shown = 0;
                for (var slot : menu.slots) {
                    if (slot.container == mc.player.getInventory() && slot.getContainerSlot() < 36) continue;
                    ItemStackInfo info = new ItemStackInfo(slot);
                    DevDriverMod.LOGGER.info("{}   slot #{} {} name={} active={} item={}", DevDriverMod.TAG, slot.index, slot.getClass().getSimpleName(),
                            info.name, slot.isActive(), info.item);
                    shown++;
                }
                DevDriverMod.LOGGER.info("{} {} on {}: {} non-inventory slot(s)", DevDriverMod.TAG, menu.getClass().getSimpleName(), screen.getClass().getSimpleName(), shown);
            }
            case "clickslot" -> { // clickslot inv N | clickslot name NAME [k]: left-click (pick up / place) a slot through the server
                var screen = requireScreen(mc);
                if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs)) throw new IllegalStateException("not a container screen");
                var menu = cs.getMenu();
                net.minecraft.world.inventory.Slot target = null;
                if (args[0].equals("inv")) {
                    int n = Integer.parseInt(args[1]);
                    for (var slot : menu.slots) if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == n) { target = slot; break; }
                } else {
                    String name = args[1];
                    int k = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                    for (var slot : menu.slots) {
                        if (!slot.isActive()) continue;
                        ItemStackInfo info = new ItemStackInfo(slot);
                        if (name.equals(info.name) && !info.cosmetic && k-- == 0) { target = slot; break; }
                    }
                }
                if (target == null) throw new IllegalStateException("no slot " + String.join(" ", args));
                DevDriverMod.LOGGER.info("{} clicking slot #{} {} (carried before: {})", DevDriverMod.TAG, target.index, target.getClass().getSimpleName(), menu.getCarried());
                mc.gameMode.handleInventoryMouseClick(menu.containerId, target.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc.player);
            }
            case "disconnect" -> { // leave the current server/world like the pause menu's Disconnect button
                if (mc.level != null) mc.level.disconnect();
                mc.disconnect();
                mc.setScreen(new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen()));
            }
            case "show" -> setWindowVisible(mc, true);
            case "hide" -> setWindowVisible(mc, false);
            case "quit" -> {
                DevDriverMod.LOGGER.info("{} quitting with {} failure(s)", DevDriverMod.TAG, failures);
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
            case "advancements" -> mc.setScreen(new AdvancementsScreen(mc.player.connection.getAdvancements(), mc.screen));
            case "checklist" -> com.lucasmods.checklist.client.ChecklistClient.open();
            default -> throw new IllegalArgumentException("unknown screen " + which);
        }
    }

    private static String expand(Minecraft mc, String text) {
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        text = text.replace("{screen}", screen);
        if (mc.player == null) {
            for (String k : new String[]{"xp", "items", "health", "hotbar", "entities", "maxhealth", "speed"}) text = text.replace("{" + k + "}", "?");
            return text;
        }
        if (text.contains("{entities}")) {
            // every living non-player entity within 32 blocks horizontally (any height), with health and y
            StringBuilder sb = new StringBuilder();
            for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(32, 512, 32), e -> e instanceof LivingEntity && !(e instanceof Player))) {
                LivingEntity le = (LivingEntity) e;
                if (sb.length() > 0) sb.append(", ");
                sb.append(le.getType().toShortString());
                if (le.hasCustomName()) sb.append("(").append(le.getCustomName().getString()).append(")");
                sb.append(":").append(String.format(Locale.ROOT, "%.1f/%.0f", le.getHealth(), le.getMaxHealth()));
                sb.append("@y").append((int) le.getY());
            }
            text = text.replace("{entities}", sb.length() == 0 ? "none" : sb.toString());
        }
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
                .replace("{maxhealth}", String.format(Locale.ROOT, "%.1f", mc.player.getMaxHealth()))
                .replace("{speed}", String.format(Locale.ROOT, "%.4f", mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)))
                .replace("{hotbar}", hotbar.toString());
    }

    private static net.minecraft.core.BlockPos nearestChest(Minecraft mc, int radius) {
        net.minecraft.core.BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        net.minecraft.core.BlockPos origin = mc.player.blockPosition();
        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(origin.offset(-radius, -6, -radius), origin.offset(radius, 6, radius))) {
            if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CHEST)) {
                double d = p.distSqr(origin);
                if (d < bestD) { bestD = d; best = p.immutable(); }
            }
        }
        if (best == null) throw new IllegalStateException("no chest within " + radius + " blocks");
        return best;
    }

    private static void collectWidgets(net.minecraft.client.gui.components.events.ContainerEventHandler container,
                                       List<net.minecraft.client.gui.components.AbstractWidget> out) {
        for (var child : container.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) out.add(w);
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) collectWidgets(c, out);
        }
    }

    private static String widgetTooltip(net.minecraft.client.gui.components.AbstractWidget w) {
        try {
            var t = w.getTooltip();
            if (t == null) return "";
            StringBuilder sb = new StringBuilder();
            for (var seq : t.toCharSequence(Minecraft.getInstance())) {
                if (sb.length() > 0) sb.append(" | ");
                seq.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Slot name (Curios getIdentifier / Accessories slotName), cosmetic flag and item, found by reflection. */
    private static final class ItemStackInfo {
        final String name;
        final boolean cosmetic;
        final String item;

        ItemStackInfo(net.minecraft.world.inventory.Slot slot) {
            String n = "";
            boolean c = false;
            for (String m : new String[]{"getIdentifier", "slotName"}) {
                try {
                    Object v = slot.getClass().getMethod(m).invoke(slot);
                    if (v != null) { n = v.toString(); break; }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            for (String m : new String[]{"isCosmeticSlot", "isCosmetic"}) {
                try {
                    c = (Boolean) slot.getClass().getMethod(m).invoke(slot);
                    break;
                } catch (ReflectiveOperationException | ClassCastException ignored) {
                }
            }
            if (slot.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("cosmetic")) c = true;
            name = n;
            cosmetic = c;
            var stack = slot.getItem();
            item = stack.isEmpty() ? "-" : stack.getCount() + "x" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
    }
}
