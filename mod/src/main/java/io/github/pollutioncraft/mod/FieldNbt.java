package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.core.EncodedField;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Maps {@link EncodedField} columns to {@code long[]} tags and back. A missing or mistyped column reads as empty. */
final class FieldNbt {
    private static final String DATA_VERSION = "dataVersion";
    private static final String KEYS = "keys";
    private static final String ABSORBABLE = "absorbable";
    private static final String PERSISTENT = "persistent";
    private static final String EMITTED = "emitted";
    private static final String DOMINANT_POS = "dominantPos";
    private static final String DOMINANT_RATE = "dominantRate";

    private FieldNbt() {}

    static CompoundTag write(EncodedField field, CompoundTag tag) {
        tag.putInt(DATA_VERSION, field.dataVersion());
        tag.putLongArray(KEYS, field.keys());
        tag.putLongArray(ABSORBABLE, field.absorbable());
        tag.putLongArray(PERSISTENT, field.persistent());
        tag.putLongArray(EMITTED, field.emitted());
        tag.putLongArray(DOMINANT_POS, field.dominantPos());
        tag.putLongArray(DOMINANT_RATE, field.dominantRate());
        return tag;
    }

    static EncodedField read(CompoundTag tag) {
        int version = tag.contains(DATA_VERSION, Tag.TAG_INT) ? tag.getInt(DATA_VERSION) : -1;
        return new EncodedField(
                version,
                column(tag, KEYS),
                column(tag, ABSORBABLE),
                column(tag, PERSISTENT),
                column(tag, EMITTED),
                column(tag, DOMINANT_POS),
                column(tag, DOMINANT_RATE));
    }

    private static long[] column(CompoundTag tag, String name) {
        return tag.contains(name, Tag.TAG_LONG_ARRAY) ? tag.getLongArray(name) : new long[0];
    }
}
