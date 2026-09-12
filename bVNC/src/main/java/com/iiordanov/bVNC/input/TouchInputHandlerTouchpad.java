/**
 * Copyright (C) 2013- Iordan Iordanov
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


package com.iiordanov.bVNC.input;

import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.undatech.opaque.InputCarriable;
import com.undatech.opaque.Viewable;
import com.undatech.opaque.input.PointerAccelerationCurve;
import com.undatech.opaque.util.GeneralUtils;
import com.undatech.remoteClientUi.R;

public class TouchInputHandlerTouchpad extends TouchInputHandlerGeneric {
    public static final String ID = "TOUCHPAD_MODE";
    static final String TAG = "InputHandlerTouchpad";

    /**
     * RDP-style gestures (fling, long-press=right-click, adaptive double-tap=drag-or-double-click)
     * only apply to RDP touchpad sessions. Non-RDP touchpad sessions keep the legacy behaviour.
     */
    private boolean isRdp = false;

    /**
     * RDP-only precise finger tracking: when enabled, the remote cursor moves
     * exactly 1:1 with on-screen finger movement
     * ({@code remoteDelta = viewDelta / viewable.getZoomFactor()}), bypassing
     * the sensitivity multiplier, display density and the acceleration curve.
     * Disabled by default; non-RDP sessions never read it.
     */
    private boolean rdpPreciseTracking = false;

    /** Tick interval (ms) and per-tick damping factor for the fling deceleration. */
    private static final int FLING_TICK_MS = 20;
    private static final float FLING_DAMP = 0.86f;
    /** Below this speed (px/s in either axis) the fling stops to avoid jitter. */
    private static final float FLING_NOISE_PX_PER_S = 200f;
    /**
     * Density-independent movement threshold past which the second tap commits
     * to a drag (rather than a double-click). 8 dp is the platform touch-slop
     * convention, chosen so a normal finger's jitter during an intended
     * double-click does not get misread as a drag; the previous 2 dp was below
     * typical touch noise.
     */
    private static final float DRAG_THRESHOLD_DP = 8f;
    /** Finger-edge band (dp): within this distance of a canvas edge the drag keeps moving the cursor. */
    private static final float EDGE_PIN_BAND_DP = 24f;
    /** Slow drag-hold cursor speed, in density-independent units (dp/s of screen motion at sensitivity 1). */
    private static final float EDGE_PIN_SPEED_DP_PER_S = 100f;

    private final Flinger flinger;
    /**
     * Per-tick damping factor for the fling deceleration, configured at runtime
     * (defaulting to {@link #FLING_DAMP}). Pushed from the activity's Fling
     * Resistance setting via {@link #setFlingDamp(float)}.
     */
    private float flingDamp = FLING_DAMP;

    /**
     * Adaptive double-tap state: the second tap's DOWN was seen (via
     * {@link #onDoubleTap(MotionEvent)}) but NOTHING has been sent to the server yet.
     * We wait to learn the user's intent: movement => drag, lift => double-click.
     */
    private boolean rdpDoubleTapPending = false;
    /** Movement passed the slop while PENDING: LEFT is held down and {@code dragMode} is armed. */
    private boolean rdpDoubleTapDragging = false;
    /** Touch coordinates of the second tap's DOWN, used as the movement-slop origin. */
    private float rdpDoubleTapDownX = 0f;
    private float rdpDoubleTapDownY = 0f;
    /**
     * Fixed density-independent movement threshold ({@link #DRAG_THRESHOLD_DP})
     * the finger must exceed to commit the second tap to a drag, so a true
     * double-click survives normal finger jitter.
     */
    private final int rdpTouchSlop;

    /**
     * Slow-cursor drag-hold helper for committed double-click+drag: while the
     * finger is pinned in the {@link #EDGE_PIN_BAND_DP} band at the canvas edge,
     * keeps nudging the cursor (with LEFT held) so the user can keep dragging
     * past the local screen edge without lifting the finger.
     */
    private final EdgePinRepeater edgePinRepeater;
    /** {@link #EDGE_PIN_BAND_DP} pre-converted to pixels for the current display. */
    private final int edgePinBandPx;

    /**
     * RDP-only velocity-dependent cursor acceleration curve (configured via
     * {@link #setPointerAccel(boolean, float, float)}). Disabled by default,
     * in which case its gain is exactly 1.0f and the RDP math reduces to the
     * legacy zoom-only scaling.
     */
    private final PointerAccelerationCurve pointerAccelCurve = new PointerAccelerationCurve();
    // Sub-pixel remainders for the RDP path: slow movements below 1 px/event
    // accumulate here instead of being truncated away every event.
    private float carryX = 0f;
    private float carryY = 0f;
    // Per-event curve-gain cache: gain() must be evaluated once per MotionEvent
    // (its EMA state would otherwise advance once per axis). Both getX and
    // getY for the same event reuse the cached value.
    private long lastCurveEventTime = Long.MIN_VALUE;
    private float lastCurveGain = 1f;

    public TouchInputHandlerTouchpad(TouchInputDelegate touchInputDelegate, Viewable viewable,
                                     InputCarriable remoteInput, boolean debugLogging,
                                     float scrollRate) {
        super(touchInputDelegate, viewable, remoteInput, debugLogging, scrollRate);
        this.flinger = new Flinger(viewable.getHandler());
        this.edgePinRepeater = new EdgePinRepeater(viewable.getHandler());
        // 2 dp floor so the gesture still triggers on very low-density displays;
        // pre-multiplied by displayDensity (inherited, set by the super ctor).
        this.rdpTouchSlop = Math.max(2, (int) (DRAG_THRESHOLD_DP * displayDensity + 0.5f));
        this.edgePinBandPx = (int) (EDGE_PIN_BAND_DP * displayDensity + 0.5f);
    }

    /**
     * Updates the per-tick fling damping factor (default {@link #FLING_DAMP}).
     * Called by the activity when the Fling Resistance setting changes.
     */
    public void setFlingDamp(float flingDamp) {
        this.flingDamp = flingDamp;
    }

    /**
     * Set by the integration pass to enable Microsoft-RDP-style touchpad gestures
     * (fling / long-press=right-click / adaptive double-tap=left-drag-or-double-click) for this instance.
     */
    public void setRdp(boolean isRdp) {
        this.isRdp = isRdp;
        if (!isRdp) {
            // Cancel any in-flight fling to avoid leaving the state machine
            // half-initialized when the flag flips off mid-gesture.
            flinger.stop();
            edgePinRepeater.stop();
            // Defensive release: if a drag/right-drag/middle-drag is in flight we
            // must release the held button at the current pointer position. Without
            // this the Generic UP path would either skip the release (flags cleared
            // before UP) or release against an RDP-only button mask against a
            // non-RDP session. Callers are expected to only invoke this on RDP ->
            // non-RDP transitions (typically connection teardown), but the
            // released-button snapshot keeps a mid-flight flip harmless too.
            if (dragMode || rightDragMode || middleDragMode) {
                int releaseX = Math.round(remoteInput.getPointer().getX());
                int releaseY = Math.round(remoteInput.getPointer().getY());
                remoteInput.getPointer().releaseButton(releaseX, releaseY, 0);
            }
            dragMode = false;
            rightDragMode = false;
            // Drop any half-armed adaptive double-tap state with it.
            rdpDoubleTapPending = false;
            rdpDoubleTapDragging = false;
            // No RDP-only pointer-acceleration state may survive the teardown.
            resetPointerCurveState();
        }
    }

    /**
     * Enables/disables and configures the RDP-only cursor acceleration curve.
     * Called by the activity when the Pointer Acceleration setting changes.
     * Disabling it applies a flat gain of 1.0 on the RDP path (the legacy
     * {@code computeAcceleration} math is never used there); the base
     * sensitivity multiplier still applies unchanged.
     */
    public void setPointerAccel(boolean enabled, float gainLow, float gainHigh) {
        pointerAccelCurve.setConfig(enabled, gainLow, gainHigh);
        resetPointerCurveState();
    }

    /**
     * Enables/disables the RDP-only precise finger tracking (default disabled).
     * When on, cursor movement is exactly 1:1 with on-screen finger movement
     * ({@code remoteDelta = viewDelta / viewable.getZoomFactor()}) with no
     * sensitivity multiplier, density normalization or acceleration-curve gain,
     * so the on-screen finger-to-cursor offset stays constant at any finger speed.
     * Called by the activity when the Precise Tracking setting changes.
     *
     * <p>Toggling always drops the curve EMA state and the sub-pixel carry so
     * stale state cannot cause a one-shot cursor jump; enabling also stops any
     * in-flight fling / edge-pin repeaters so a legacy-gain tick cannot leak
     * into precise mode (mirrors the stop calls in {@link #setRdp(boolean)}).
     */
    public void setPointerPreciseTracking(boolean enabled) {
        this.rdpPreciseTracking = enabled;
        resetPointerCurveState();
        if (enabled) {
            flinger.stop();
            edgePinRepeater.stop();
        }
    }

    /** Drops the curve EMA state and the sub-pixel carry (new config / teardown). */
    private void resetPointerCurveState() {
        pointerAccelCurve.reset();
        carryX = 0f;
        carryY = 0f;
        lastCurveEventTime = Long.MIN_VALUE;
        lastCurveGain = 1f;
    }

    /**
     * Returns the zoom factor to divide by on the precise-tracking path.
     * {@code FitToScreenScaling.scaling} initialises to 0 before scale setup, so a
     * non-positive zoom would turn the divisions into NaN/Infinity cursor
     * coordinates; treat that degenerate state as 1:1.
     */
    private float getPreciseZoom() {
        float zoom = viewable.getZoomFactor();
        return zoom > 0f ? zoom : 1f;
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return viewable.getResources().getString(R.string.input_method_touchpad_description);
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandler#getId()
     */
    @Override
    public String getId() {
        return ID;
    }

    /*
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onScroll(android.view.MotionEvent, android.view.MotionEvent, float, float)
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScroll, e1: " + e1 + ", e2:" + e2);

        // If we are scaling, allow panning around by moving two fingers around the screen
        if (inScaling) {
            float scale = viewable.getZoomFactor();
            touchInputDelegate.showActionBar();
            viewable.relativePan(Math.round(distanceX * scale), Math.round(distanceY * scale));
        } else {
            boolean twoFingers = false;
            int meta = 0;
            if (e1 != null) {
                twoFingers = (e1.getPointerCount() > 1);
            }
            if (e2 != null) {
                twoFingers = twoFingers || (e2.getPointerCount() > 1);
                meta = e2.getMetaState();
            }

            // onScroll called while scaling/swiping gesture is in effect. We ignore the event and pretend it was
            // consumed. This prevents the mouse pointer from flailing around while we are scaling.
            // Also, if one releases one finger slightly earlier than the other when scaling, it causes Android
            // to stick a spiteful onScroll with a MASSIVE delta here.
            // This would cause the mouse pointer to jump to another place suddenly.
            // Hence, we ignore onScroll after scaling until we lift all pointers up.
            // While a right-button drag is held (long-press or two-finger-tap path),
            // do not let incremental finger jitter translate into cursor drift —
            // the right click should land at the press position, not chase a
            // moving cursor.
            if (twoFingers || inSwiping || rightDragMode) {
                return true;
            }

            touchInputDelegate.showActionBar();

            // If the gesture has just began, then don't allow a big delta to prevent
            // pointer jumps at the start of scrolling.
            if (!inScrolling) {
                inScrolling = true;
                distanceX = getSign(distanceX);
                distanceY = getSign(distanceY);
                distXQueue.clear();
                distYQueue.clear();
            }

            distXQueue.add(distanceX);
            distYQueue.add(distanceY);

            // Only after the first two events have arrived do we start using distanceX and Y
            // In order to effectively discard the last two events (which are typically unreliable
            // because of the finger lifting off).
            if (distXQueue.size() > 2) {
                distanceX = distXQueue.poll();
                distanceY = distYQueue.poll();
            } else {
                return true;
            }

            float deltaX;
            float deltaY;
            if (isRdp && rdpPreciseTracking) {
                // Precise tracking: cursor follows the finger 1:1 — view px
                // divided by the zoom, with no sensitivity, density or curve
                // gain. The negation matches the getDelta(-distanceX, ...) call
                // below (GestureDetector's distance is the inverse of the
                // finger's movement direction).
                float zoom = getPreciseZoom();
                deltaX = -distanceX / zoom;
                deltaY = -distanceY / zoom;
            } else {
                // Raw finger delta before the sensitivity multiplier, density
                // normalized — the input the RDP acceleration curve expects.
                float rawDx = distanceX / displayDensity;
                float rawDy = distanceY / displayDensity;

                // Make distanceX/Y display density independent.
                float sensitivity = remoteInput.getPointer().getSensitivity();
                distanceX = sensitivity * distanceX / displayDensity;
                distanceY = sensitivity * distanceY / displayDensity;

                // Compute the absolute new mouse position. Both axes pass the same
                // event time so the RDP curve's gain is evaluated exactly once.
                // The sub-pixel carry is RDP-only; non-RDP keeps the legacy math.
                deltaX = getDelta(-distanceX, rawDx, rawDy, e2.getEventTime());
                deltaY = getDelta(-distanceY, rawDx, rawDy, e2.getEventTime());
            }
            if (isRdp) {
                deltaX = carryFor(deltaX, true);
                deltaY = carryFor(deltaY, false);
            }
            int newX = Math.round(remoteInput.getPointer().getX() + deltaX);
            int newY = Math.round(remoteInput.getPointer().getY() + deltaY);

            remoteInput.getPointer().moveMouse(newX, newY, meta);
        }
        viewable.movePanToMakePointerVisible();
        return true;
    }

    /**
     * RDP-only cursor fling: take the finger's release velocity, decay it on a tick, and keep
     * moving the pointer until the speed drops below the noise floor or a new touch starts.
     */
    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (!isRdp) {
            return false;
        }
        // Mirror the gating style of onScroll: bail out while scaling, swiping, or with
        // two fingers (those flows are owned by other code paths).
        if (inScaling || inSwiping) {
            return false;
        }
        boolean twoFingers = false;
        if (e1 != null) {
            twoFingers = (e1.getPointerCount() > 1);
        }
        if (e2 != null) {
            twoFingers = twoFingers || (e2.getPointerCount() > 1);
        }
        if (twoFingers) {
            return false;
        }
        // A double-tap gesture (pending or committed to a drag) must never turn into a
        // cursor fling on release — the user was dragging a window, not throwing the cursor.
        if (rdpDoubleTapPending || rdpDoubleTapDragging) {
            return false;
        }

        int meta = (e2 != null) ? e2.getMetaState() : 0;
        int startX = Math.round(remoteInput.getPointer().getX());
        int startY = Math.round(remoteInput.getPointer().getY());
        GeneralUtils.debugLog(debugLogging, TAG,
                "onFling: vx=" + velocityX + ", vy=" + velocityY
                        + ", startX=" + startX + ", startY=" + startY);
        flinger.start(velocityX, velocityY, startX, startY, meta);
        return true;
    }

    /*
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onDown(android.view.MotionEvent)
     */
    @Override
    public boolean onDown(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onDown, e: " + e);
        panRepeater.stop();
        // A new touch down interrupts any in-flight fling so the user can land precisely.
        // NOTE: the adaptive double-tap state is deliberately NOT reset here — onDown() is
        // invoked by GestureDetector *after* onDoubleTap() within the same second-tap DOWN
        // dispatch, so a reset here would wipe the state onDoubleTap just armed. The reset
        // lives in onTouchEvent()'s ACTION_DOWN branch, which runs before the detector feed.
        flinger.stop();
        // A new touch starts a fresh pointer motion: drop the curve EMA state
        // and any sub-pixel carry left over from the previous gesture.
        resetPointerCurveState();
        return true;
    }

    /**
     * RDP-only long-press fires a FULL right mouse-button click (down + up ~40ms
     * apart) at the long-press instant, while the finger is still down — matching
     * the Microsoft RDP touchpad semantics. Holding the right button until finger
     * lift (the legacy behaviour) was rejected by Windows as a deferred right-click.
     *
     * <p>{@code rightDragMode} is intentionally NOT set: after the auto-released
     * right click, no drag-cursor should follow. The parent's UP branch
     * {@code releaseButton} on finger lift is idempotent when no button is held
     * (see {@link RemoteRdpPointer#releaseButton}).
     */
    @Override
    public void onLongPress(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onLongPress, e: " + e);

        if (!isRdp) {
            super.onLongPress(e);
            return;
        }
        doubleTapTracker.reset();
        if (dragMode || rightDragMode || middleDragMode) {
            // Already mid-drag (e.g. double-tap-and-hold commit ran). Don't switch.
            return;
        }
        if (secondPointerWasDown || thirdPointerWasDown) {
            // Multi-touch gestures (two/three-finger tap) own long-press in legacy; no-op.
            return;
        }

        int metaState = e.getMetaState();
        touchInputDelegate.sendShortVibration();

        final int x = getX(e);
        final int y = getY(e);
        final int m = metaState;
        remoteInput.getPointer().rightButtonDown(x, y, m);
        // Fire the matching up ~40ms later. Even if the user keeps holding past
        // 40ms, the parent's UP branch releaseButton is idempotent (no button held
        // → only MOVE-only telemetry is sent).
        viewable.getHandler().postDelayed(() -> remoteInput.getPointer().releaseButton(x, y, m), 40);
        // A long press (right-click) is mutually exclusive with the adaptive
        // double-tap gesture: clear any pending/dragging state so the next
        // double-tap isn't armed by a phantom continuation of this gesture.
        rdpDoubleTapPending = false;
        rdpDoubleTapDragging = false;
        edgePinRepeater.stop();
    }

    /**
     * RDP-only adaptive double-tap: the second tap sends NOTHING to the server yet.
     * We only record the touch origin and arm the PENDING state, then let
     * {@link #onTouchEvent(MotionEvent)} decide from the user's next move:
     * <ul>
     *   <li>finger MOVES past half the touch slop → plain press-and-drag (no preceding
     *       click), so Windows drags the window / selects text instead of reading the
     *       press as the second half of a double-click (which maximized title bars);</li>
     *   <li>finger LIFTS without moving → a true double-click (click + click), so
     *       word-select double-taps keep working.</li>
     * </ul>
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (!isRdp) {
            return super.onDoubleTap(e);
        }
        GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTap, e: " + e);
        // Stock accepted the pair: arm the adaptive state machine (instead of
        // firing the base two-click default) and mark the stock path as taken.
        onManualDoubleTap(e);
        return true;
    }

    /**
     * Armed by either the stock detector's {@link #onDoubleTap(MotionEvent)}
     * (within getScaledDoubleTapSlop) or the base class's relaxed-slop
     * {@code DoubleTapPairTracker}. Both paths enter the same adaptive state
     * machine: the second tap's DOWN sends nothing yet; movement past
     * {@link #rdpTouchSlop} commits a LEFT-drag, a lift emits a double-click.
     * The base two-click shortcut is never used (marked here) so the pair
     * produces either the drag or the double-click, never extra clicks on top.
     */
    @Override
    protected void onManualDoubleTap(MotionEvent e) {
        if (!isRdp) {
            super.onManualDoubleTap(e);
            return;
        }
        // Tell the base class the pair was consumed: without this, after our
        // onTouchEvent returns, the base would fire notifyDoubleTap() (two
        // clicks) on top of the state machine's own output. Also arm the
        // click suppression so the stock onSingleTapConfirmed() scheduled for
        // the second UP does not add a third click behind the state machine's.
        stockDoubleTapFired = true;
        suppressNextSingleTapConfirmed = true;
        rdpDoubleTapDownX = e.getX();
        rdpDoubleTapDownY = e.getY();
        rdpDoubleTapPending = true;
        rdpDoubleTapDragging = false;
    }

    /**
     * Drives the adaptive double-tap state machine (RDP only); everything else flows
     * unchanged through {@code super.onTouchEvent}.
     *
     * <p>ACTION_DOWN clears leftover state before the detector feed inside super can
     * re-arm it, ACTION_MOVE commits to a drag once the finger passes the slop,
     * ACTION_UP emits the double-click, and ACTION_CANCEL releases a held button.
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isRdp) {
            final int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                // Stale state from an aborted gesture must not survive into this one.
                // super's detector feed calls onDoubleTap() (which arms PENDING) at the
                // very end of this dispatch, so clearing here is safe.
                rdpDoubleTapPending = false;
                rdpDoubleTapDragging = false;
                edgePinRepeater.stop();
                return super.onTouchEvent(e);
            }
            if (rdpDoubleTapPending || rdpDoubleTapDragging) {
                if (action == MotionEvent.ACTION_CANCEL) {
                    // CANCEL during either pending or committed-drag state releases
                    // the gesture. Generic UP/CANCEL won't release the held button
                    // once dragMode is set, so cancelDoubleTapGesture's dragging
                    // branch (releaseButton) has to run here.
                    cancelDoubleTapGesture();
                    edgePinRepeater.stop();
                    return super.onTouchEvent(e);
                }
            }
            if (rdpDoubleTapPending) {
                switch (action) {
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - rdpDoubleTapDownX;
                        float dy = e.getY() - rdpDoubleTapDownY;
                        if (dx * dx + dy * dy > rdpTouchSlop * rdpTouchSlop) {
                            commitDoubleTapDrag(e);
                        }
                        return super.onTouchEvent(e);
                    }
                    case MotionEvent.ACTION_UP:
                        edgePinRepeater.stop();
                        emitDoubleTapDoubleClick(e);
                        // Still feed super so the GestureDetector sees the UP and cancels
                        // its armed LONG_PRESS timer — otherwise a quick double-tap would
                        // be followed by a phantom right-click ~450ms later. The parent's
                        // UP branch releaseButton is idempotent with no button held.
                        super.onTouchEvent(e);
                        return true; // consume; both clicks were already sent in full
                }
            }
            // Slow-cursor drag-hold: while a double-click+drag is committed,
            // push the cursor further in the finger's edge-band direction so the
            // user can keep dragging past the local screen edge without lifting
            // the finger. Stop the repeater on UP so a finger lifted inside the
            // edge band doesn't keep ticking after the drag ends. The repeater
            // never clears dragMode / rdpDoubleTapDragging — ending the drag
            // stays owned by UP/CANCEL/setRdp(false) paths.
            if (rdpDoubleTapDragging) {
                if (action == MotionEvent.ACTION_MOVE) {
                    updateEdgePinRepeater(e);
                } else if (action == MotionEvent.ACTION_UP) {
                    edgePinRepeater.stop();
                }
            }
        }
        return super.onTouchEvent(e);
    }

    /**
     * Commits the pending double-tap to a left-button drag: presses LEFT at the cursor
     * position (touchpad semantics) and arms {@code dragMode} so the parent's ACTION_MOVE
     * branch keeps moving the cursor with the button held. No click precedes the press,
     * so the remote sees press+drag only (window moves, text selects).
     */
    private void commitDoubleTapDrag(MotionEvent e) {
        int x = getX(e);
        int y = getY(e);
        remoteInput.getPointer().leftButtonDown(x, y, e.getMetaState());
        dragMode = true;
        // Re-anchor drag deltas at the commit point so the cursor does not jump by the
        // slop distance the finger already travelled.
        dragX = e.getX();
        dragY = e.getY();
        rdpDoubleTapPending = false;
        rdpDoubleTapDragging = true;
    }

    /**
     * Emits a true double-click (two full down/up pairs at the cursor position). Only
     * called once the intent is confirmed — the finger lifted without moving.
     */
    private void emitDoubleTapDoubleClick(MotionEvent e) {
        int x = getX(e);
        int y = getY(e);
        int m = e.getMetaState();
        performTapClick(x, y, m);
        performTapClick(x, y, m);
        rdpDoubleTapPending = false;
    }

    /**
     * Aborts the gesture (ACTION_CANCEL): releases the LEFT button if the drag was
     * already committed, and clears the state machine.
     */
    private void cancelDoubleTapGesture() {
        if (rdpDoubleTapDragging) {
            int x = Math.round(remoteInput.getPointer().getX());
            int y = Math.round(remoteInput.getPointer().getY());
            remoteInput.getPointer().releaseButton(x, y, 0);
        }
        rdpDoubleTapPending = false;
        rdpDoubleTapDragging = false;
    }

    private void performTapClick(int x, int y, int metaState) {
        // Mirror TouchInputHandlerGeneric.performLeftClick so the synthesized
        // double-tap click path also surfaces the action bar and keeps the
        // pointer visible — the bare down/up sequence below left those out.
        touchInputDelegate.showActionBar();
        remoteInput.getPointer().leftButtonDown(x, y, metaState);
        SystemClock.sleep(50);
        remoteInput.getPointer().releaseButton(x, y, metaState);
        viewable.movePanToMakePointerVisible();
    }

    /**
     * Cursor-anchored touchpad: when not in a drag mode, returns the current
     * CURSOR position (not the touch position) so tap clicks land where the
     * cursor is; drag deltas while in a drag mode are cursor-relative.
     *
     * @see com.iiordanov.bVNC.input.InputHandlerGeneric#getX(android.view.MotionEvent)
     */
    protected int getX(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode) {
            // Raw per-event finger delta measured against the pre-event anchor,
            // captured before the anchor moves.
            float distanceX = e.getX() - dragX;
            float deltaX;
            if (isRdp && rdpPreciseTracking) {
                // Precise tracking: the drag follows the finger 1:1 — raw view
                // px divided by the zoom, positive sign (unlike onScroll's
                // GestureDetector deltas).
                deltaX = distanceX / getPreciseZoom();
            } else {
                // Raw per-event finger deltas (dp) measured against the pre-event
                // anchors; both axes are captured before either anchor moves so the
                // acceleration curve sees the true event velocity regardless of
                // whether getX or getY is called first.
                float rawDx = distanceX / displayDensity;
                float rawDy = (e.getY() - dragY) / displayDensity;
                // Compute the absolute new X coordinate. The sub-pixel carry is
                // RDP-only; non-RDP keeps the legacy math.
                deltaX = getDelta(distanceX, rawDx, rawDy, e.getEventTime());
            }
            dragX = e.getX();
            if (isRdp) {
                deltaX = carryFor(deltaX, true);
            }
            return Math.round(remoteInput.getPointer().getX() + deltaX);
        }
        dragX = e.getX();
        return remoteInput.getPointer().getX();
    }

    /**
     * Cursor-anchored touchpad: when not in a drag mode, returns the current
     * CURSOR position (not the touch position) so tap clicks land where the
     * cursor is; drag deltas while in a drag mode are cursor-relative.
     *
     * @see com.iiordanov.bVNC.input.InputHandlerGeneric#getY(android.view.MotionEvent)
     */
    protected int getY(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode) {
            // See getX: the raw delta is captured before either anchor moves so
            // the curve sees the true per-event velocity whichever axis is
            // evaluated first (the gain itself is cached per event time).
            float distanceY = e.getY() - dragY;
            float deltaY;
            if (isRdp && rdpPreciseTracking) {
                // Precise tracking: the drag follows the finger 1:1 — raw view
                // px divided by the zoom, positive sign (unlike onScroll's
                // GestureDetector deltas).
                deltaY = distanceY / getPreciseZoom();
            } else {
                float rawDx = (e.getX() - dragX) / displayDensity;
                float rawDy = distanceY / displayDensity;
                // Compute the absolute new Y coordinate. The sub-pixel carry is
                // RDP-only; non-RDP keeps the legacy math.
                deltaY = getDelta(distanceY, rawDx, rawDy, e.getEventTime());
            }
            dragY = e.getY();
            if (isRdp) {
                deltaY = carryFor(deltaY, false);
            }
            return Math.round(remoteInput.getPointer().getY() + deltaY);
        }
        dragY = e.getY();
        return remoteInput.getPointer().getY();
    }

    /**
     * Computes how far the cursor will move for this event.
     *
     * <p>RDP sessions run the zoom scaling through the velocity-dependent
     * {@link #pointerAccelCurve} (gain is evaluated exactly once per event, see
     * {@link #lastCurveEventTime}); the per-axis sub-pixel carry is applied by
     * the callers via {@link #carryFor} (see {@link #carryX}/{@link #carryY}).
     * Non-RDP sessions keep the legacy {@link #computeAcceleration(float)} math
     * byte-for-byte.
     *
     * @param distance   display-space movement for the axis (px).
     * @param rawDx      raw finger delta X for the event, density-normalized (dp).
     * @param rawDy      raw finger delta Y for the event, density-normalized (dp).
     * @param eventTimeMs {@link MotionEvent#getEventTime()} of the event.
     * @return cursor movement for the axis (px; fractional on the RDP path until
     *         the caller folds in the carry).
     */
    private float getDelta(float distance, float rawDx, float rawDy, long eventTimeMs) {
        float delta = (float) (distance * Math.cbrt(viewable.getZoomFactor()));
        if (!isRdp) {
            return computeAcceleration(delta);
        }
        return delta * getCurveGain(rawDx, rawDy, eventTimeMs);
    }

    /**
     * Folds the per-axis sub-pixel remainder into an RDP cursor delta: the
     * previous remainder is added, the integer part taken, and the fraction
     * stored back, so slow movements below 1 px/event still accumulate. A zero
     * delta (e.g. a click at the current cursor position) leaves the carry
     * untouched. Non-RDP deltas bypass this entirely, keeping that math
     * unchanged.
     */
    private float carryFor(float delta, boolean isX) {
        if (delta == 0f) {
            return 0f;
        }
        float carry = isX ? carryX : carryY;
        float sum = delta + carry;
        int step = (int) sum;
        if (isX) {
            carryX = sum - step;
        } else {
            carryY = sum - step;
        }
        return step;
    }

    /**
     * Evaluates the RDP acceleration curve at most once per event: getX() and
     * getY() process the same MotionEvent (same {@code eventTimeMs}), and the
     * curve's internal EMA state must advance exactly once per event, so the
     * second axis reuses the cached gain. A stale call with a different event
     * time (e.g. a delayed tap-confirmed click) simply recomputes — harmless.
     */
    private float getCurveGain(float rawDx, float rawDy, long eventTimeMs) {
        if (eventTimeMs != lastCurveEventTime) {
            lastCurveEventTime = eventTimeMs;
            lastCurveGain = pointerAccelCurve.gain(rawDx, rawDy, eventTimeMs);
        }
        return lastCurveGain;
    }

    /**
     * Computes the acceleration depending on the size of the supplied delta.
     * @param delta
     * @return
     */
    private float computeAcceleration(float delta) {
        float origSign = getSign(delta);
        delta = Math.abs(delta);
        boolean accelerated = remoteInput.getPointer().isAccelerated();
        if (delta <= 15) {
            // ponytail: small deltas pass through unscaled (was *0.75f — too sluggish).
        } else if (accelerated) {
            float strength = remoteInput.getPointer().getAccelerationStrength();
            float curve = (delta <= 70.0f) ? delta * delta / 20.0f : delta * 4.5f;
            // Strength lerps between pass-through and the legacy curve: 0 = no
            // acceleration, 1 = legacy curve exactly, >1 = exaggerated. Note the
            // quadratic branch over-shoots above strength 1 as delta grows (delta=50,
            // strength=2 -> 200 vs legacy 125) — intentional lerp behavior.
            delta = delta + (curve - delta) * strength;
        }
        return origSign * delta;
    }

    /**
     * Starts, restarts, or stops {@link #edgePinRepeater} based on whether the
     * finger sits in the edge band of the canvas. Re-anchors each (re)start at
     * the cursor's current position so restarts never jump.
     */
    private void updateEdgePinRepeater(MotionEvent e) {
        int band = edgePinBandPx;
        int w = viewable.getWidth();
        int h = viewable.getHeight();
        float speed = EDGE_PIN_SPEED_DP_PER_S * displayDensity;
        float vx = 0f;
        float vy = 0f;
        if (e.getX() < band) {
            vx = -speed;
        } else if (e.getX() > w - band) {
            vx = speed;
        }
        if (e.getY() < band) {
            vy = -speed;
        } else if (e.getY() > h - band) {
            vy = speed;
        }
        if (vx == 0f && vy == 0f) {
            edgePinRepeater.stop();
        } else {
            edgePinRepeater.start(vx, vy,
                    Math.round(remoteInput.getPointer().getX()),
                    Math.round(remoteInput.getPointer().getY()),
                    e.getMetaState());
        }
    }

    /**
     * Decays a release velocity and keeps nudging the remote pointer along that vector
     * via moveMouse until the speed falls below the noise floor. Bounds are enforced by
     * setNewPointerPosition inside each pointer protocol implementation.
     */
    private final class Flinger implements Runnable {
        private final Handler handler;
        private float vx;
        private float vy;
        private int x;
        private int y;
        private int meta;

        Flinger(Handler handler) {
            this.handler = handler;
        }

        void start(float velocityX, float velocityY, int startX, int startY, int metaState) {
            stop();
            this.vx = velocityX;
            this.vy = velocityY;
            this.x = startX;
            this.y = startY;
            this.meta = metaState;
            handler.postDelayed(this, FLING_TICK_MS);
        }

        void stop() {
            handler.removeCallbacks(this);
        }

        @Override
        public void run() {
            // Brake by the per-tick damping factor (now configured via
            // setFlingDamp, pushed from the activity's Fling Resistance setting).
            vx *= flingDamp;
            vy *= flingDamp;

            // Noise floor: stop the fling once neither axis is still moving meaningfully.
            if (Math.abs(vx) < FLING_NOISE_PX_PER_S && Math.abs(vy) < FLING_NOISE_PX_PER_S) {
                return;
            }

            float dt = (float) FLING_TICK_MS / 1000f;
            float dx;
            float dy;
            if (isRdp && rdpPreciseTracking) {
                // Precise tracking: the fling velocity is view px/s — divide by
                // the zoom (gain exactly 1/zoom) for a 1:1 finger->cursor motion.
                float zoom = getPreciseZoom();
                dx = vx * dt / zoom;
                dy = vy * dt / zoom;
            } else {
                // Mirror onScroll's display-density scaling so the fling feels consistent
                // with drag inertia across devices. Include the cbrt(zoomFactor) curve
                // that getDelta() applies, otherwise the fling speed scales differently
                // at different zoom levels than on-scroll does.
                float sensitivity = remoteInput.getPointer().getSensitivity();
                float zoomCurve = (float) Math.cbrt(viewable.getZoomFactor());
                dx = vx * dt * sensitivity / displayDensity * zoomCurve;
                dy = vy * dt * sensitivity / displayDensity * zoomCurve;
            }
            x = Math.round(x + dx);
            y = Math.round(y + dy);

            // Snapshot the pointer position before the move so we can detect
            // an edge-pinned tick (clamped to the same value) and stop the
            // flinger early instead of rescheduling until the noise floor.
            int beforeX = Math.round(remoteInput.getPointer().getX());
            int beforeY = Math.round(remoteInput.getPointer().getY());
            // The protocol's moveMouse → setNewPointerPosition path clamps to
            // desktop bounds, so fling ticks cannot wander off-screen.
            remoteInput.getPointer().moveMouse(x, y, meta);
            // Mirror onScroll/performTapClick so the viewport pans to keep the
            // flung cursor visible while the IME is open.
            viewable.movePanToMakePointerVisible();
            int afterX = Math.round(remoteInput.getPointer().getX());
            int afterY = Math.round(remoteInput.getPointer().getY());
            if (beforeX == afterX && beforeY == afterY) {
                // Pointer pinned at the edge — no further motion possible this
                // tick, and (because the clamp will keep rejecting our deltas)
                // none ever will. Drop the flinger instead of letting it tick
                // down through the noise floor with zero effective motion.
                return;
            }
            handler.postDelayed(this, FLING_TICK_MS);
        }
    }

    /**
     * Drag-hold helper: while the user is dragging with a finger pinned at the
     * canvas edge, keep moving the remote cursor (with LEFT held) so the user
     * can continue dragging past the local screen edge — matching the
     * laptop-touchpad "drag-hold" behavior. Velocity is constant (no damping);
     * stops when the finger leaves the edge band or the remote pointer is
     * clamped by the desktop bounds (no further motion possible).
     */
    private final class EdgePinRepeater implements Runnable {
        private final Handler handler;
        private float vx;
        private float vy;
        private int x;
        private int y;
        private int meta;

        EdgePinRepeater(Handler handler) {
            this.handler = handler;
        }

        void start(float velocityX, float velocityY, int startX, int startY, int metaState) {
            stop();
            this.vx = velocityX;
            this.vy = velocityY;
            this.x = startX;
            this.y = startY;
            this.meta = metaState;
            handler.postDelayed(this, FLING_TICK_MS);
        }

        void stop() {
            handler.removeCallbacks(this);
        }

        @Override
        public void run() {
            float dt = (float) FLING_TICK_MS / 1000f;
            if (isRdp && rdpPreciseTracking) {
                // Precise tracking: gain is exactly 1/zoom — view px/s divided
                // by the zoom gives 1:1 finger->cursor motion.
                float zoom = getPreciseZoom();
                x = Math.round(x + vx * dt / zoom);
                y = Math.round(y + vy * dt / zoom);
            } else {
                // Mirror Flinger's per-tick math (sensitivity, density, cbrt(zoom))
                // so the slow drag-hold motion feels consistent with regular drag
                // inertia at the same zoom level.
                float sensitivity = remoteInput.getPointer().getSensitivity();
                float zoomCurve = (float) Math.cbrt(viewable.getZoomFactor());
                x = Math.round(x + vx * dt * sensitivity / displayDensity * zoomCurve);
                y = Math.round(y + vy * dt * sensitivity / displayDensity * zoomCurve);
            }

            // Snapshot pointer position before the move so an edge-clamped tick
            // (same value before/after) stops the repeater instead of
            // re-posting into a dead clamp.
            int beforeX = Math.round(remoteInput.getPointer().getX());
            int beforeY = Math.round(remoteInput.getPointer().getY());
            // moveMouseButtonDown (not moveMouse) so the LEFT button stays
            // held — the user is mid-drag and we must not drop the button.
            remoteInput.getPointer().moveMouseButtonDown(x, y, meta);
            int afterX = Math.round(remoteInput.getPointer().getX());
            int afterY = Math.round(remoteInput.getPointer().getY());
            if (beforeX == afterX && beforeY == afterY) {
                // Edge of remote desktop reached; nothing more can move this
                // tick and the clamp will keep rejecting further ticks.
                return;
            }
            handler.postDelayed(this, FLING_TICK_MS);
        }
    }
}
