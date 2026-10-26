package com.lucasmods.devdriver;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Dev-only harness. Never shipped.
 * <ul>
 *   <li>Client: {@code -Ddevdriver.script=<file>} drives the client (see {@link ClientDriver}); the game window is
 *       hidden while a script runs so it never takes focus, grabs the mouse or shows on screen.</li>
 *   <li>Server (dedicated, or the integrated one via {@code -Ddevdriver.serverScript=<file>} on the client):
 *       runs console commands and checks entities headlessly (see {@link ServerDriver}).</li>
 * </ul>
 */
@Mod(DevDriverMod.MODID)
public final class DevDriverMod {
    public static final String MODID = "devdriver";
    static final Logger LOGGER = LogUtils.getLogger();
    static final String TAG = "[DevDriver]";

    public DevDriverMod(IEventBus modBus, ModContainer container) {
        String script = System.getProperty("devdriver.script");
        String serverScript = System.getProperty("devdriver.serverScript");
        if (FMLEnvironment.dist.isClient()) {
            ClientDriver.register(script);
            ServerDriver.register(serverScript);
        } else {
            ServerDriver.register(script);
        }
    }
}
