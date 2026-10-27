package com.lucasmods.checklist;

import com.lucasmods.checklist.net.GridSyncPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the server last told this client. Plain data, safe to reference from common code. */
public final class ClientState {
    /** Done grid squares, the party's included. */
    public static volatile int level = 0;
    public static volatile int total = 0;
    public static volatile boolean revealAll = false;
    public static volatile Map<String, Integer> ranks = new HashMap<>();
    public static volatile int renownLevel = 0;
    public static volatile long renownInto = 0;
    public static volatile long renownNeed = 0;
    /** FTB Teams party name; empty when solo. Squares are shared within the party. */
    public static volatile String team = "";
    /** The grid as the server last sent it: known squares with display data and done flags. */
    public static volatile List<GridSyncPayload.Square> squares = List.of();

    private ClientState() {}

    public static int rank(String id) { return ranks.getOrDefault(id, 0); }

    /** Points spent, using the catalog's cost per rank. */
    public static int spent() { return ChecklistConfig.upgrades().spent(ranks); }

    /** All points: squares plus Renown levels. */
    public static int points() { return level + renownLevel; }

    public static int available() { return Math.max(0, points() - spent()); }
}
