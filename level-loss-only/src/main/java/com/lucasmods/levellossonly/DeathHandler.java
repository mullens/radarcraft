package com.lucasmods.levellossonly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Stashes the dying player's inventory in the NeoForge "PlayerPersisted" NBT tag, which NeoForge
 * copies onto the respawned player and which is saved with the player file (so it survives a
 * server restart between dying and respawning). The vanilla death code then finds an empty
 * inventory and drops nothing, while experience is left alone and drops as usual.
 */
public final class DeathHandler {
    private static final String ITEMS_TAG = "levellossonly_items";
    private static final String SLOT_KEY = "Slot";
    private static final String ITEM_KEY = "Item";

    private DeathHandler() {}

    /**
     * Lowest priority: runs after every other listener, and not at all if one of them
     * cancelled the death (totem-style mods), so we never stash items for a player who lives.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator()) return;
        if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return; // vanilla already keeps everything

        Inventory inventory = player.getInventory();
        ListTag saved = new ListTag();
        int kept = 0;
        int vanished = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                // Curse of Vanishing keeps its meaning: the item is destroyed on death.
                inventory.setItem(slot, ItemStack.EMPTY);
                vanished++;
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt(SLOT_KEY, slot);
            entry.put(ITEM_KEY, stack.save(player.registryAccess()));
            saved.add(entry);
            inventory.setItem(slot, ItemStack.EMPTY); // nothing left for vanilla to drop
            kept++;
        }

        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(ITEMS_TAG, saved);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        LevelLossOnly.LOGGER.info("{} died: stashed {} stacks for respawn ({} vanished), XP level {} drops as normal",
                player.getGameProfile().getName(), kept, vanished, player.experienceLevel);
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CompoundTag persistedNew = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag persistedOld = event.getOriginal().getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag source = persistedOld.contains(ITEMS_TAG, Tag.TAG_LIST) ? persistedOld : persistedNew;
        if (!source.contains(ITEMS_TAG, Tag.TAG_LIST)) return;

        ListTag saved = source.getList(ITEMS_TAG, Tag.TAG_COMPOUND);
        Inventory inventory = player.getInventory();
        int restored = 0;
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            ItemStack stack = ItemStack.parseOptional(player.registryAccess(), entry.getCompound(ITEM_KEY));
            if (stack.isEmpty()) continue;
            int slot = entry.getInt(SLOT_KEY);
            if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack);
            } else if (!inventory.add(stack)) {
                player.drop(stack, false);
            }
            restored++;
        }

        persistedOld.remove(ITEMS_TAG);
        persistedNew.remove(ITEMS_TAG);
        event.getOriginal().getPersistentData().put(Player.PERSISTED_NBT_TAG, persistedOld);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persistedNew);
        LevelLossOnly.LOGGER.info("{} respawned: restored {} stacks, XP level is now {}",
                player.getGameProfile().getName(), restored, player.experienceLevel);
    }
}
