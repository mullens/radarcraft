package com.lucasmods.devdriver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes every payload channel the local NeoForge NetworkRegistry knows about, one per line:
 * {@code protocol id version optional flow}. Reflection on the private PAYLOAD_REGISTRATIONS map
 * (Map&lt;ConnectionProtocol, Map&lt;ResourceLocation, PayloadRegistration&gt;&gt;), so the same code
 * works on the client and on a dedicated server; diffing the two files shows negotiation problems.
 */
final class ChannelDump {
    private ChannelDump() {}

    static int dump(Path file) {
        try {
            return dumpInner(file);
        } catch (Exception e) {
            throw new RuntimeException("channel dump failed", e);
        }
    }

    private static int dumpInner(Path file) throws Exception {
        Class<?> registry = Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry");
        Field field = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        Map<?, ?> byProtocol = (Map<?, ?>) field.get(null);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<?, ?> protocolEntry : byProtocol.entrySet()) {
            String protocol = String.valueOf(protocolEntry.getKey());
            for (Map.Entry<?, ?> e : ((Map<?, ?>) protocolEntry.getValue()).entrySet()) {
                Object reg = e.getValue();
                String version = String.valueOf(call(reg, "version"));
                String optional = String.valueOf(call(reg, "optional"));
                String flow = String.valueOf(call(reg, "flow"));
                lines.add(protocol + " " + e.getKey() + " version=" + (version.isEmpty() ? "-" : version) + " optional=" + optional + " flow=" + flow);
            }
        }
        lines.sort(String::compareTo);
        Field setup = registry.getDeclaredField("setup");
        setup.setAccessible(true);
        lines.add(0, "# NetworkRegistry.setup=" + setup.get(null) + " entries=" + lines.size());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
        return lines.size();
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
