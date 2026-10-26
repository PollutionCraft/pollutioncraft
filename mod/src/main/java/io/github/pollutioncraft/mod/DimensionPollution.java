package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.core.EncodedField;
import io.github.pollutioncraft.core.FieldCodec;
import io.github.pollutioncraft.core.IntervalScheduler;
import io.github.pollutioncraft.core.PollutionField;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pollution field of one dimension, saved with that dimension's data (ADR 0003). The scheduler is
 * transient: an interval in flight when the server stops is simply dropped, and the next one starts from
 * the saved field.
 */
public final class DimensionPollution extends SavedData {
    static final String STORAGE_KEY = "pollutioncraft_field";
    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPollution.class);

    private final PollutionField field;
    private final IntervalScheduler scheduler;
    /** The raw record when it could not be read (unknown or missing dataVersion); null otherwise. */
    private final CompoundTag preserved;
    /** True when the stored data is kept as is: either {@link #preserved} or an unparsable file. */
    private final boolean paused;

    public DimensionPollution() {
        this(new PollutionField(), null, false);
    }

    private DimensionPollution(PollutionField field, CompoundTag preserved, boolean paused) {
        this.field = field;
        this.preserved = preserved;
        this.paused = paused;
        this.scheduler = new IntervalScheduler(DimensionPollution::currentSettings);
    }

    /** Read from the server config once per interval (task 008), so overrides apply from the next interval. */
    static IntervalScheduler.Settings currentSettings() {
        ServerConfig config = PollutionCraftMod.config();
        return new IntervalScheduler.Settings(config.spreadIntervalTicks(), config.diffusionConfig());
    }

    /**
     * Minecraft calls the constructor only when there is no loaded record: either no file exists yet (start
     * fresh) or the file exists but could not be parsed. The second case must never be replaced by a fresh
     * record, or the next save would destroy the real file, so it gives a paused record that is never saved.
     */
    public static SavedData.Factory<DimensionPollution> factory(Path dataFile) {
        return new SavedData.Factory<>(() -> missing(dataFile), DimensionPollution::load, null);
    }

    private static DimensionPollution missing(Path dataFile) {
        if (Files.exists(dataFile)) {
            LOGGER.error("Pollution field file {} exists but could not be read; leaving it untouched and not "
                    + "simulating this dimension", dataFile);
            return new DimensionPollution(new PollutionField(), null, true);
        }
        return new DimensionPollution();
    }

    /**
     * Reads a saved record. One this mod cannot interpret (unknown or missing {@code dataVersion}, for
     * example written by a newer version) is kept as is and written back unchanged, so a downgrade never
     * destroys data; the dimension is then not simulated.
     */
    public static DimensionPollution load(CompoundTag tag, HolderLookup.Provider registries) {
        EncodedField encoded = FieldNbt.read(tag);
        if (encoded.dataVersion() != FieldCodec.DATA_VERSION) {
            LOGGER.error("Pollution field has unsupported dataVersion {} (this mod reads {}); keeping it unchanged "
                    + "and not simulating this dimension", encoded.dataVersion(), FieldCodec.DATA_VERSION);
            return new DimensionPollution(new PollutionField(), tag, true);
        }
        return new DimensionPollution(FieldCodec.decode(encoded, LOGGER::warn), null, false);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (paused) {
            if (preserved == null) {
                throw new IllegalStateException("an unreadable pollution file is never saved");
            }
            return preserved; // the very tag that was read: same content and same write order
        }
        return FieldNbt.write(FieldCodec.encode(field), tag);
    }

    /**
     * True when the stored record was unreadable and is being kept unchanged. Such a dimension is paused: not
     * simulated, never marked dirty, and {@link #field()} refuses access.
     */
    public boolean isPreserved() {
        return paused;
    }

    /** The field. Throws {@link IllegalStateException} for a paused record, so no write is silently lost. */
    public PollutionField field() {
        if (paused) {
            throw new IllegalStateException("pollution field is paused (unreadable stored record)");
        }
        return field;
    }

    @Override
    public void setDirty(boolean dirty) {
        if (!paused) {
            super.setDirty(dirty);
        }
    }

    @Override
    public boolean isDirty() {
        return !paused && super.isDirty();
    }

    public IntervalScheduler scheduler() {
        return scheduler;
    }
}
