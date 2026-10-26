package com.lucasmods.keyboardkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out, for every key mapping, which other mappings share its key and whether that sharing
 * is a real problem.
 *
 * <p>NeoForge already knows a lot about intent: every mapping has a {@link IKeyConflictContext}
 * (in-game, in menus, or everywhere) and an optional {@link KeyModifier} (Ctrl/Shift/Alt).
 * {@link KeyMapping#same(KeyMapping)} combines both, so two mappings on the same key that only
 * fire in different situations (say, a JEI menu key and a movement key) are not conflicts.
 * We build on that and sort every overlap into one of two tiers:</p>
 * <ul>
 *   <li><b>HARD</b>: NeoForge says both mappings can fire from the same key press in the same
 *       situation. Shown red, unless the user marked the pair as intentional.</li>
 *   <li><b>SOFT</b>: the key is shared, but the game keeps the two apart (different context,
 *       different modifier, or one mapping's key is merely the other's modifier). Shown yellow
 *       so the overlap is visible but not alarming.</li>
 * </ul>
 */
public final class BindingAnalysis {

    public enum Severity {
        NONE, SOFT, HARD;

        Severity max(Severity other) { return this.ordinal() >= other.ordinal() ? this : other; }
    }

    /**
     * @param other     the mapping sharing the key
     * @param severity  effective severity (an ignored HARD conflict is reported as SOFT)
     * @param ignorable true when this is a HARD conflict the user may mark as intentional
     * @param ignored   true when the user has marked it as intentional
     * @param reason    short human explanation
     */
    public record Overlap(KeyMapping other, Severity severity, boolean ignorable, boolean ignored, Component reason) {}

    private final Map<InputConstants.Key, List<KeyMapping>> byKey = new HashMap<>();
    private final Map<KeyMapping, List<Overlap>> overlaps = new IdentityHashMap<>();
    private final Map<KeyMapping, Severity> mappingSeverity = new IdentityHashMap<>();
    private final Map<InputConstants.Key, Severity> keySeverity = new HashMap<>();
    private final List<KeyMapping> sorted;

    private BindingAnalysis(List<KeyMapping> sorted) {
        this.sorted = sorted;
    }

    public static BindingAnalysis compute(KeyMapping[] mappings, IgnoredConflicts ignored) {
        KeyMapping[] copy = mappings.clone();
        Arrays.sort(copy);
        BindingAnalysis analysis = new BindingAnalysis(List.of(copy));

        for (KeyMapping mapping : copy) {
            analysis.overlaps.put(mapping, new ArrayList<>());
            analysis.mappingSeverity.put(mapping, Severity.NONE);
            if (!mapping.isUnbound()) {
                analysis.byKey.computeIfAbsent(mapping.getKey(), k -> new ArrayList<>()).add(mapping);
                analysis.keySeverity.putIfAbsent(mapping.getKey(), Severity.NONE);
            }
        }

        for (int i = 0; i < copy.length; i++) {
            KeyMapping a = copy[i];
            if (a.isUnbound()) continue;
            for (int j = i + 1; j < copy.length; j++) {
                KeyMapping b = copy[j];
                if (b.isUnbound()) continue;
                Overlap ab = classify(a, b, ignored);
                if (ab == null) continue;
                Overlap ba = new Overlap(a, ab.severity(), ab.ignorable(), ab.ignored(), ab.reason());
                analysis.overlaps.get(a).add(ab);
                analysis.overlaps.get(b).add(ba);
                analysis.mappingSeverity.merge(a, ab.severity(), Severity::max);
                analysis.mappingSeverity.merge(b, ab.severity(), Severity::max);
                analysis.keySeverity.merge(a.getKey(), ab.severity(), Severity::max);
                analysis.keySeverity.merge(b.getKey(), ab.severity(), Severity::max);
            }
        }
        return analysis;
    }

    /**
     * Mirrors the rules inside NeoForge's {@link KeyMapping#same(KeyMapping)}, with one deliberate
     * difference: NeoForge still flags two mappings on the same key as a conflict when their
     * contexts never overlap (a menu-only key versus an in-game key). The game itself keeps those
     * apart when dispatching input, so here they are a SOFT overlap, not a conflict.
     */
    private static Overlap classify(KeyMapping a, KeyMapping b, IgnoredConflicts ignored) {
        boolean sameKey = a.getKey().equals(b.getKey());
        IKeyConflictContext ca = a.getKeyConflictContext();
        IKeyConflictContext cb = b.getKeyConflictContext();
        boolean contextsOverlap = ca.conflicts(cb) || cb.conflicts(ca);
        KeyModifier ma = a.getKeyModifier();
        KeyModifier mb = b.getKeyModifier();

        if (!contextsOverlap) {
            if (!sameKey) return null;
            return new Overlap(b, Severity.SOFT, false, false,
                    Component.translatable("keyboardkeybinds.reason.context", contextName(ca), contextName(cb)));
        }

        if (!sameKey) {
            // e.g. Sneak = Shift and something = Shift + R: holding Shift to press R also sneaks.
            // NeoForge shows these in gold rather than red; so do we.
            if (ma.matches(b.getKey()) || mb.matches(a.getKey())) {
                KeyMapping modifierOwner = ma.matches(b.getKey()) ? a : b;
                KeyMapping bare = modifierOwner == a ? b : a;
                return new Overlap(b, Severity.SOFT, false, false,
                        Component.translatable("keyboardkeybinds.reason.modifier_key",
                                bare.getDisplayName(), modifierOwner.getDisplayName()));
            }
            return null;
        }

        // Same key, and both can be live at the same time.
        boolean isIgnored = ignored.isIgnored(a, b);
        if (ma == mb) {
            return new Overlap(b, isIgnored ? Severity.SOFT : Severity.HARD, true, isIgnored,
                    Component.translatable("keyboardkeybinds.reason.both", sharedContextName(a, b)));
        }
        boolean inGame = ca.conflicts(KeyConflictContext.IN_GAME) || cb.conflicts(KeyConflictContext.IN_GAME);
        if (inGame && (ma == KeyModifier.NONE || mb == KeyModifier.NONE)) {
            // In-game, holding Ctrl and pressing Q also presses plain Q.
            return new Overlap(b, isIgnored ? Severity.SOFT : Severity.HARD, true, isIgnored,
                    Component.translatable("keyboardkeybinds.reason.modifier_in_game"));
        }
        return new Overlap(b, Severity.SOFT, false, false, Component.translatable("keyboardkeybinds.reason.modifier"));
    }

    /** The narrower of the two contexts, which is where both mappings are live at once. */
    private static Component sharedContextName(KeyMapping a, KeyMapping b) {
        IKeyConflictContext ca = a.getKeyConflictContext();
        IKeyConflictContext cb = b.getKeyConflictContext();
        if (ca == KeyConflictContext.UNIVERSAL) return contextName(cb);
        return contextName(ca);
    }

    public static Component contextName(IKeyConflictContext context) {
        if (context instanceof KeyConflictContext known) {
            return switch (known) {
                case IN_GAME -> Component.translatable("keyboardkeybinds.context.in_game");
                case GUI -> Component.translatable("keyboardkeybinds.context.gui");
                case UNIVERSAL -> Component.translatable("keyboardkeybinds.context.universal");
            };
        }
        return Component.translatable("keyboardkeybinds.context.custom");
    }

    public List<KeyMapping> all() { return sorted; }

    public List<KeyMapping> bindingsOn(InputConstants.Key key) {
        return byKey.getOrDefault(key, Collections.emptyList());
    }

    public List<Overlap> overlapsOf(KeyMapping mapping) {
        return overlaps.getOrDefault(mapping, Collections.emptyList());
    }

    public Severity severityOf(KeyMapping mapping) {
        return mappingSeverity.getOrDefault(mapping, Severity.NONE);
    }

    public Severity severityOf(InputConstants.Key key) {
        return keySeverity.getOrDefault(key, Severity.NONE);
    }

    public int hardConflictCount() {
        int n = 0;
        for (Map.Entry<KeyMapping, List<Overlap>> e : overlaps.entrySet()) {
            for (Overlap o : e.getValue()) if (o.severity() == Severity.HARD) n++;
        }
        return n / 2; // each pair is recorded on both mappings
    }
}
