package com.lucasmods.checklist.net;

import com.lucasmods.checklist.Checklist;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server tells the client everything it needs to draw the grid. Vanilla only sends a client the
 * advancements near the ones it has completed, so the client cannot know on its own which squares
 * exist or what a revealed-but-distant square looks like.
 */
public record GridSyncPayload(List<Square> squares) implements CustomPacketPayload {
    public record Square(int x, int y, String id, Component title, Component description, ItemStack icon, int frame, boolean done, String doneBy) {}

    public static final Type<GridSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Checklist.MODID, "grid"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GridSyncPayload> CODEC = StreamCodec.of(GridSyncPayload::write, GridSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, GridSyncPayload payload) {
        buf.writeVarInt(payload.squares.size());
        for (Square s : payload.squares) {
            buf.writeVarInt(s.x);
            buf.writeVarInt(s.y);
            buf.writeUtf(s.id);
            ComponentSerialization.STREAM_CODEC.encode(buf, s.title);
            ComponentSerialization.STREAM_CODEC.encode(buf, s.description);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.icon);
            buf.writeByte(s.frame);
            buf.writeBoolean(s.done);
            buf.writeUtf(s.doneBy);
        }
    }

    private static GridSyncPayload read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Square> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Square(buf.readVarInt(), buf.readVarInt(), buf.readUtf(),
                    ComponentSerialization.STREAM_CODEC.decode(buf), ComponentSerialization.STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readByte(), buf.readBoolean(), buf.readUtf()));
        }
        return new GridSyncPayload(list);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
