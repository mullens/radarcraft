package com.lucasmods.checklist;

import com.lucasmods.checklist.net.GridSyncPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the server last told this client. Plain data, safe to reference from common code. */
public final class ClientState {
    public static volatile int level = 0;
    public static volatile int total = 0;
    public static volatile boolean revealAll = false;
    public static volatile Map<String, Integer> ranks = new HashMap<>();
    /** The grid as the server last sent it: known squares with display data and done flags. */
    public static volatile List<GridSyncPayload.Square> squares = List.of();

    private ClientState() {}

    public static int rank(String id) { return ranks.getOrDefault(id, 0); }

    public static int spent() {
        int n = 0;
        for (int v : ranks.values()) n += v;
        return n;
    }

    public static int available() { return Math.max(0, level - spent()); }
}
