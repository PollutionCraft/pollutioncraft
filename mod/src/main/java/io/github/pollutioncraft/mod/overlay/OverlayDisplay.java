package io.github.pollutioncraft.mod.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * What the client shows in F3 and when it must show {@code no data} instead (binding decision 5): nothing
 * received yet, an unsupported protocol version, a payload for another chunk or dimension, or one older than
 * {@value #STALE_AFTER_INTERVALS} intervals. Old numbers are never shown. Plain Java, so it is unit-tested; the
 * client class only feeds it game time and the player's position.
 */
public final class OverlayDisplay {
    static final int STALE_AFTER_INTERVALS = 3;

    /** The part of a payload the display needs. */
    public record Shown(String dimension, int chunkX, int chunkZ, int intervalTicks, List<String> lines) {}

    private Shown last;
    private long receivedAt;
    private boolean unsupportedReported;

    public void receive(Shown shown, long gameTime) {
        last = shown;
        receivedAt = gameTime;
    }

    /** @return true the first time, so the caller logs once */
    public boolean receiveUnsupported(int version) {
        last = null;
        boolean first = !unsupportedReported;
        unsupportedReported = true;
        return first;
    }

    public void clear() {
        last = null;
    }

    /** The F3 lines to add, title first. */
    public List<String> render(String dimension, int chunkX, int chunkZ, long gameTime) {
        List<String> out = new ArrayList<>();
        out.add(OverlayText.TITLE);
        if (isFresh(dimension, chunkX, chunkZ, gameTime)) {
            out.addAll(last.lines());
        } else {
            out.add(OverlayText.NO_DATA);
        }
        return out;
    }

    private boolean isFresh(String dimension, int chunkX, int chunkZ, long gameTime) {
        if (last == null || !last.dimension().equals(dimension) || last.chunkX() != chunkX || last.chunkZ() != chunkZ) {
            return false;
        }
        long age = gameTime - receivedAt;
        return age >= 0 && age <= (long) STALE_AFTER_INTERVALS * last.intervalTicks();
    }
}
