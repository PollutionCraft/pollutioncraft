package io.github.pollutioncraft.mod.overlay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The server-to-client overlay payload (architecture 3.5): the formatted lines for one chunk. Layout on the
 * wire: {@code protocolVersion} (varint), the <em>body length in bytes</em> (varint), then the body. A client
 * that does not know the version skips exactly the body's bytes and gets {@link #unsupported}; it must not
 * throw, because an exception while decoding disconnects the player. A body that breaks the bounds (more than
 * {@value OverlayText#MAX_LINES} lines or {@value OverlayText#MAX_LINE_LENGTH} characters per line) is a
 * corrupt stream, and decoding throws.
 */
public record OverlayPayload(int protocolVersion, ResourceLocation dimension, int chunkX, int chunkZ, int intervalTicks, List<String> lines)
        implements CustomPacketPayload {
    /** The format version this build reads and writes; bump it for any change to the body. */
    public static final int VERSION = 1;

    public static final Type<OverlayPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("pollutioncraft", "overlay"));

    private static final StreamCodec<ByteBuf, OverlayPayload> BODY = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, OverlayPayload::dimension,
            ByteBufCodecs.VAR_INT, OverlayPayload::chunkX,
            ByteBufCodecs.VAR_INT, OverlayPayload::chunkZ,
            ByteBufCodecs.VAR_INT, OverlayPayload::intervalTicks,
            ByteBufCodecs.stringUtf8(OverlayText.MAX_LINE_LENGTH).apply(ByteBufCodecs.list(OverlayText.MAX_LINES)), OverlayPayload::lines,
            (dimension, x, z, interval, lines) -> new OverlayPayload(VERSION, dimension, x, z, interval, lines));

    public static final StreamCodec<RegistryFriendlyByteBuf, OverlayPayload> CODEC = new StreamCodec<>() {
        @Override
        public OverlayPayload decode(RegistryFriendlyByteBuf buf) {
            int version = buf.readVarInt();
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new DecoderException("overlay payload body length " + length + " exceeds the " + buf.readableBytes() + " bytes available");
            }
            if (version != VERSION) {
                buf.skipBytes(length);
                return unsupported(version);
            }
            FriendlyByteBuf body = new FriendlyByteBuf(buf.readSlice(length));
            OverlayPayload payload = BODY.decode(body);
            if (body.isReadable()) {
                throw new DecoderException("overlay payload body has " + body.readableBytes() + " unread bytes");
            }
            return payload;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, OverlayPayload payload) {
            if (payload.isUnsupported()) {
                throw new IllegalArgumentException("cannot encode an unsupported-version marker");
            }
            ByteBuf body = Unpooled.buffer();
            try {
                BODY.encode(body, payload);
                buf.writeVarInt(VERSION);
                buf.writeVarInt(body.readableBytes());
                buf.writeBytes(body);
            } finally {
                body.release();
            }
        }
    };

    /** What a client decodes when the server speaks a version it does not know: it shows {@code no data}. */
    public static OverlayPayload unsupported(int version) {
        return new OverlayPayload(version, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 0, 0, 0, List.of());
    }

    public boolean isUnsupported() {
        return protocolVersion != VERSION;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
