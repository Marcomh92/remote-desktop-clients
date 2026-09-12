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
 * Detects double-tap gestures from raw DOWN/UP event pairs supplied by the caller.
 * <p>
 * Positions, {@code slopPx} and {@code tapRegionPx} are in pixels; all times
 * ({@code eventTimeMs}, {@code timeoutMs}) are in milliseconds and are supplied
 * by the caller (this class never reads a clock).
 * <p>
 * Matching mirrors stock {@code GestureDetector} double-tap semantics, but the
 * thresholds are caller-controlled:
 * <ul>
 *     <li>A DOWN completes a double-tap if a previously recorded tap (whose DOWN
 *     and UP were both seen) has its DOWN within {@code slopPx} of this DOWN and
 *     this DOWN occurs no later than {@code timeoutMs} after that tap's UP.</li>
 *     <li>An UP is only recorded when it stays within {@code tapRegionPx} of the
 *     pending DOWN; a longer move means the gesture is a drag and the pending tap
 *     is discarded.</li>
 * </ul>
 * Callers must invoke {@link #reset()} on ACTION_CANCEL, long-press detection and
 * any multi-touch transition.
 */
public class DoubleTapPairTracker {
    private int slopPx;
    private long timeoutMs;
    private int tapRegionPx;

    private boolean hasPendingTap;
    private float downX;
    private float downY;
    private boolean hasUp;
    private long upTimeMs;

    /**
     * @param slopPx      maximum distance in pixels between the two DOWN positions of a double tap (clamped to >= 0)
     * @param timeoutMs   maximum time in ms between the first UP and the second DOWN (clamped to >= 0)
     * @param tapRegionPx maximum distance in pixels a finger may wander between DOWN and UP to still count as a tap (clamped to >= 0)
     */
    public DoubleTapPairTracker(int slopPx, long timeoutMs, int tapRegionPx) {
        this.slopPx = Math.max(0, slopPx);
        this.timeoutMs = Math.max(0L, timeoutMs);
        this.tapRegionPx = Math.max(0, tapRegionPx);
    }

    /**
     * Called for every ACTION_DOWN.
     *
     * @return true iff this DOWN completes a double-tap pair with the previously recorded tap,
     *         in which case the recorded tap is consumed and no new pending tap is kept.
     */
    public boolean onActionDown(float x, float y, long eventTimeMs) {
        if (hasPendingTap && hasUp
                && withinSlop(downX, downY, x, y)
                && eventTimeMs - upTimeMs <= timeoutMs) {
            reset();
            return true;
        }
        hasPendingTap = true;
        downX = x;
        downY = y;
        hasUp = false;
        upTimeMs = 0L;
        return false;
    }

    /**
     * Called for every ACTION_UP. Records this UP for the pending tap only when it is
     * within {@code tapRegionPx} of the DOWN; otherwise the pending tap is cleared.
     * Ignored when there is no pending DOWN.
     */
    public void onActionUp(float x, float y, long eventTimeMs) {
        if (!hasPendingTap) {
            return;
        }
        if (withinTapRegion(downX, downY, x, y)) {
            hasUp = true;
            upTimeMs = eventTimeMs;
        } else {
            reset();
        }
    }

    /** Clears all tracked state. Does not change the configured thresholds. */
    public void reset() {
        hasPendingTap = false;
        hasUp = false;
        upTimeMs = 0L;
        downX = 0f;
        downY = 0f;
    }

    /** Sets the double-tap slop in pixels (clamped to >= 0) and resets all state. */
    public void setSlopPx(int slopPx) {
        this.slopPx = Math.max(0, slopPx);
        reset();
    }

    /** Sets the double-tap timeout in milliseconds (clamped to >= 0) and resets all state. */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = Math.max(0L, timeoutMs);
        reset();
    }

    private boolean withinSlop(float x0, float y0, float x1, float y1) {
        double dx = (double) x1 - x0;
        double dy = (double) y1 - y0;
        return dx * dx + dy * dy <= (double) slopPx * slopPx;
    }

    private boolean withinTapRegion(float x0, float y0, float x1, float y1) {
        double dx = (double) x1 - x0;
        double dy = (double) y1 - y0;
        return dx * dx + dy * dy <= (double) tapRegionPx * tapRegionPx;
    }
}
