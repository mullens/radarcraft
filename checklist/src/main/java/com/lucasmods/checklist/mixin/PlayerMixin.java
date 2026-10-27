package com.lucasmods.checklist.mixin;

import com.lucasmods.checklist.PlayerState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Hearty Appetite: every source of food exhaustion is scaled by (1 - rank x amount, default 0.15). No event exists for this. */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float checklist$heartyAppetite(float exhaustion) {
        if (!((Object) this instanceof ServerPlayer player)) return exhaustion;
        double less = com.lucasmods.checklist.Specials.total(player, "hearty_appetite", 0.15);
        return less > 0 ? exhaustion * (float) Math.max(0.0, 1.0 - less) : exhaustion;
    }
}
