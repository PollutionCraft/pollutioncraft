package io.github.pollutioncraft.core;

/**
 * The soft cap of one absorber type in one chunk (ADR 0004 B): {@code cap * (1 - e^(-n / k))} PU per interval for
 * {@code n} absorbers. {@code cap} is the most the type can ever absorb in a chunk per interval; {@code k} sets how
 * fast the curve approaches it (at {@code n = k} it gives about 63 % of {@code cap}). Every extra absorber still
 * adds a little, so there is no cliff, but piling up one type gains less and less. Immutable.
 *
 * @param cap finite and {@code >= 0}
 * @param k finite and {@code > 0}
 */
public record AbsorberCurve(double cap, double k) {
    public AbsorberCurve {
        if (!Double.isFinite(cap) || cap < 0) {
            throw new IllegalArgumentException("cap must be finite and >= 0, was " + cap);
        }
        if (!Double.isFinite(k) || k <= 0) {
            throw new IllegalArgumentException("k must be finite and > 0, was " + k);
        }
    }

    /** The capacity {@code n} absorbers give, in PU per interval; finite, {@code >= 0} and never above {@link #cap}. */
    public double absorb(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, was " + n);
        }
        return cap * -Math.expm1(-n / k); // 1 - e^(-n/k), accurate for small n/k
    }
}
