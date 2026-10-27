package com.lucasmods.checklist.net;

import com.lucasmods.checklist.Checklist;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client tells the server it used its air jump (the client already moved itself; the server validates and clears fall damage). */
public record DoubleJumpPayload() implements CustomPacketPayload {
    public static final DoubleJumpPayload INSTANCE = new DoubleJumpPayload();
    public static final Type<DoubleJumpPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Checklist.MODID, "double_jump"));
    public static final StreamCodec<ByteBuf, DoubleJumpPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
