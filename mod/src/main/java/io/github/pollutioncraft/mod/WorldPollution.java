package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.core.WorldRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The per-world global record, saved with the overworld's data. */
public final class WorldPollution extends SavedData {
    static final String STORAGE_KEY = "pollutioncraft_world";
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldPollution.class);

    private final WorldRecord record;
    /** The raw record when it could not be read; null otherwise. */
    private final CompoundTag preserved;
    /** True when the stored data is kept as is: either {@link #preserved} or an unparsable file. */
    private final boolean paused;

    public WorldPollution() {
        this(new WorldRecord(), null, false);
    }

    private WorldPollution(WorldRecord record, CompoundTag preserved, boolean paused) {
        this.record = record;
        this.preserved = preserved;
        this.paused = paused;
    }

    /**
     * Minecraft calls the constructor only when there is no loaded record: either no file exists yet (start
     * fresh) or the file exists but could not be parsed. The second case must never be replaced by a fresh
     * record (activeTicks 0), so it gives a paused record that is never saved.
     */
    public static SavedData.Factory<WorldPollution> factory(Path dataFile) {
        return new SavedData.Factory<>(() -> missing(dataFile), WorldPollution::load, null);
    }

    private static WorldPollution missing(Path dataFile) {
        if (Files.exists(dataFile)) {
            LOGGER.error("Pollution world record file {} exists but could not be read; leaving it untouched and "
                    + "pausing the simulation", dataFile);
            return new WorldPollution(new WorldRecord(), null, true);
        }
        return new WorldPollution();
    }

    /**
     * Reads the record. If it cannot be interpreted (unknown or missing {@code dataVersion}, missing or
     * invalid values), it is kept as is and written back unchanged: {@code activeTicks} and the pressure
     * inputs must never be reset. The simulation then does not run (see {@link #isPreserved()}).
     */
    public static WorldPollution load(CompoundTag tag, HolderLookup.Provider registries) {
        boolean readable = tag.contains("dataVersion", Tag.TAG_INT)
                && tag.getInt("dataVersion") == WorldRecord.DATA_VERSION
                && tag.contains("activeTicks", Tag.TAG_LONG)
                && tag.contains("grossEmitted", Tag.TAG_DOUBLE);
        if (readable) {
            try {
                return new WorldPollution(new WorldRecord(tag.getLong("activeTicks"), tag.getDouble("grossEmitted")), null, false);
            } catch (IllegalArgumentException e) {
                LOGGER.error("Pollution world record is corrupt ({}); keeping it unchanged and pausing the simulation", e.getMessage());
                return new WorldPollution(new WorldRecord(), tag, true);
            }
        }
        LOGGER.error("Pollution world record is unreadable or of an unsupported dataVersion (this mod reads {}); "
                + "keeping it unchanged and pausing the simulation", WorldRecord.DATA_VERSION);
        return new WorldPollution(new WorldRecord(), tag, true);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (paused) {
            if (preserved == null) {
                throw new IllegalStateException("an unreadable pollution file is never saved");
            }
            return preserved; // the very tag that was read: same content and same write order
        }
        tag.putInt("dataVersion", WorldRecord.DATA_VERSION);
        tag.putLong("activeTicks", record.activeTicks());
        tag.putDouble("grossEmitted", record.grossEmitted());
        return tag;
    }

    /**
     * True when the stored record was unreadable and is being kept unchanged. The world is then paused:
     * nothing is counted or simulated, it is never marked dirty, and {@link #record()} refuses access.
     */
    public boolean isPreserved() {
        return paused;
    }

    /** The record. Throws {@link IllegalStateException} for a paused record, so no write is silently lost. */
    public WorldRecord record() {
        if (paused) {
            throw new IllegalStateException("pollution world record is paused (unreadable stored record)");
        }
        return record;
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
}
