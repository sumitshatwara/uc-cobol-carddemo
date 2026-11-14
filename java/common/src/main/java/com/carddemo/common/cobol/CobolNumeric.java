package com.carddemo.common.cobol;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * COBOL numeric semantics that every slice must reproduce exactly.
 * <ul>
 *   <li>{@link #truncate} - {@code COMPUTE} / {@code MOVE} without {@code ROUNDED}
 *       drops excess decimals ({@link RoundingMode#DOWN}, toward zero), never half-up or half-even;</li>
 *   <li>{@link #rounded} - {@code COMPUTE ... ROUNDED} rounds half away from zero
 *       ({@link RoundingMode#HALF_UP});</li>
 *   <li>{@link #fit} - storing into a PIC with fewer integer digits silently drops the
 *       high-order digits (no ON SIZE ERROR).</li>
 * </ul>
 * Never use {@code double} for money: every amount is a {@link BigDecimal} at the PIC scale.
 */
public final class CobolNumeric {

    private CobolNumeric() {}

    /** Result of an un-ROUNDED COMPUTE/MOVE into a field with {@code scale} decimals. */
    public static BigDecimal truncate(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.DOWN);
    }

    /** Result of {@code COMPUTE ... ROUNDED} into a field with {@code scale} decimals. */
    public static BigDecimal rounded(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Store into {@code PIC S9(intDigits)V9(scale)}: truncate decimals, then keep only the
     * low-order {@code intDigits} integer digits (sign kept, as COBOL does for signed fields).
     */
    public static BigDecimal fit(BigDecimal value, int intDigits, int scale) {
        BigDecimal t = truncate(value, scale);
        BigDecimal modulus = BigDecimal.TEN.pow(intDigits);
        BigDecimal abs = t.abs().remainder(modulus);
        return (t.signum() < 0 ? abs.negate() : abs).setScale(scale, RoundingMode.UNNECESSARY);
    }

    /** Store into an unsigned PIC: the sign is lost, as in COBOL. */
    public static BigDecimal fitUnsigned(BigDecimal value, int intDigits, int scale) {
        return fit(value, intDigits, scale).abs();
    }

    /**
     * Value of a numeric {@code DISPLAY} item as GnuCOBOL's {@code DISPLAY} statement prints it
     * when the item is a plain zoned field: the raw storage bytes, i.e. digits with the sign
     * overpunched on the last digit (EBCDIC convention, {@code -fsign=EBCDIC}).
     */
    public static String zonedStorage(BigDecimal value, int intDigits, int scale, boolean signed) {
        return new String(com.carddemo.common.codec.RecordCodec.encodeDisplay(fit(value, intDigits, scale), intDigits + scale, scale, signed),
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
