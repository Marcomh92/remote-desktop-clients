/**
 * Copyright (C) 2021- Iordan Iordanov
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.undatech.opaque.input;

/**
 * Velocity-based gain curve for touchpad pointer movement.
 * <p>
 * Input deltas are in dp (density-normalized raw finger deltas), time is in ms;
 * speed is therefore measured in dp/s. The returned gain multiplies the caller's
 * own base sensitivity, so callers express base speed on their side.
 * <p>
 * The curve ramps from {@code gainLow} at slow speeds to {@code gainHigh} at fast
 * speeds, with the knee at {@link #V_REF_DP_PER_S}:
 * <pre>gain = gainLow + (gainHigh - gainLow) * (v^EXPONENT / (v^EXPONENT + V_REF^EXPONENT))</pre>
 * where {@code v} is an EMA-smoothed speed (see {@link #EMA_ALPHA}). Disabled means
 * a flat gain of 1.0.
 * <p>
 * This class is pure Java: it never reads a clock, the caller supplies event times.
 */
public class PointerAccelerationCurve {
    /** Weight of the newest speed sample in the exponential moving average. */
    public static final float EMA_ALPHA = 0.4f;
    /** Exponent shaping the speed-to-gain ramp. */
    public static final float EXPONENT = 2f;
    /** Speed in dp/s at which the curve reaches the midpoint between gainLow and gainHigh. */
    public static final float V_REF_DP_PER_S = 500f;

    private static final float MIN_GAIN = 0.05f;
    private static final float MAX_GAIN = 20f;
    private static final long DEFAULT_DT_MS = 16L;
    private static final long MIN_DT_MS = 5L;
    private static final long MAX_DT_MS = 100L;

    private boolean enabled;
    private float gainLow;
    private float gainHigh;
    private long lastEventTimeMs;
    private float vSmoothed;
    private int lastDxSign;
    private int lastDySign;

    /**
     * Configures the curve and resets all tracking state.
     *
     * @param enabled  false makes {@link #gain(float, float, long)} return a flat 1.0
     * @param gainLow  gain at zero speed (values below 0 are clamped to 0)
     * @param gainHigh gain at high speed (values below gainLow are raised to gainLow)
     */
    public void setConfig(boolean enabled, float gainLow, float gainHigh) {
        this.enabled = enabled;
        if (!Float.isFinite(gainLow) || gainLow < 0f) {
            gainLow = 0f;
        }
        if (!Float.isFinite(gainHigh) || gainHigh < gainLow) {
            gainHigh = gainLow;
        }
        this.gainLow = gainLow;
        this.gainHigh = gainHigh;
        reset();
    }

    /**
     * Returns the multiplicative gain for this event's movement.
     *
     * @param dxDp        horizontal finger delta in dp (sign is direction, NaN/inf treated as 0)
     * @param dyDp        vertical finger delta in dp (sign is direction, NaN/inf treated as 0)
     * @param eventTimeMs event timestamp in ms; the first event after reset assumes a 16 ms step
     * @return gain in [0.05, 20], or 1.0 when disabled
     */
    public float gain(float dxDp, float dyDp, long eventTimeMs) {
        if (!enabled) {
            return 1.0f;
        }
        if (!Float.isFinite(dxDp)) {
            dxDp = 0f;
        }
        if (!Float.isFinite(dyDp)) {
            dyDp = 0f;
        }

        long dtMs = lastEventTimeMs <= 0L
                ? DEFAULT_DT_MS
                : Math.max(MIN_DT_MS, Math.min(MAX_DT_MS, eventTimeMs - lastEventTimeMs));
        lastEventTimeMs = eventTimeMs;

        float v = (float) (Math.hypot(dxDp, dyDp) / (dtMs / 1000.0));

        int dxSign = (dxDp > 0f) ? 1 : (dxDp < 0f ? -1 : 0);
        int dySign = (dyDp > 0f) ? 1 : (dyDp < 0f ? -1 : 0);
        if ((dxSign != 0 && lastDxSign != 0 && dxSign != lastDxSign)
                || (dySign != 0 && lastDySign != 0 && dySign != lastDySign)) {
            vSmoothed = 0f;
        }
        vSmoothed = EMA_ALPHA * v + (1f - EMA_ALPHA) * vSmoothed;
        if (dxSign != 0) {
            lastDxSign = dxSign;
        }
        if (dySign != 0) {
            lastDySign = dySign;
        }

        double vPow = Math.pow(vSmoothed, EXPONENT);
        double vRefPow = Math.pow(V_REF_DP_PER_S, EXPONENT);
        float result = (float) (gainLow + (gainHigh - gainLow) * (vPow / (vPow + vRefPow)));
        if (!Float.isFinite(result)) {
            result = 1.0f;
        }
        return Math.max(MIN_GAIN, Math.min(MAX_GAIN, result));
    }

    /** Clears last event time, smoothed velocity and axis signs, so the next gesture starts fresh. */
    public void reset() {
        lastEventTimeMs = 0L;
        vSmoothed = 0f;
        lastDxSign = 0;
        lastDySign = 0;
    }
}
