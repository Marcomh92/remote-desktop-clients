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

    /** Tick interval (ms) and per-tick damping factor for the fling deceleration. */
    private static final int FLING_TICK_MS = 20;
    private static final float FLING_DAMP = 0.86f;
    /** Below this speed (px/s in either axis) the fling stops to avoid jitter. */
    private static final float FLING_NOISE_PX_PER_S = 200f;
    /**
     * A very tiny density-independent movement threshold for what counts as a
     * double-click+drag — the finger may move a tiny amount without the gesture
     * being rejected; a true double-click has virtually no movement after the
     * 2nd tap lands.
     */
    private static final float DRAG_THRESHOLD_DP = 2f;
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
     * Fixed density-independent movement threshold (≈2 dp) the finger must
     * exceed to commit the second tap to a drag. Kept tiny so a true
     * double-click (which has almost no movement after the 2nd tap) still lands.
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
        }
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

            // Make distanceX/Y display density independent.
            float sensitivity = remoteInput.getPointer().getSensitivity();
            distanceX = sensitivity * distanceX / displayDensity;
            distanceY = sensitivity * distanceY / displayDensity;

            // Compute the absolute new mouse position.
            int newX = Math.round(remoteInput.getPointer().getX() + getDelta(-distanceX));
            int newY = Math.round(remoteInput.getPointer().getY() + getDelta(-distanceY));

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

        rdpDoubleTapDownX = e.getX();
        rdpDoubleTapDownY = e.getY();
        rdpDoubleTapPending = true;
        rdpDoubleTapDragging = false;

        return true;
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
            float distanceX = e.getX() - dragX;
            dragX = e.getX();
            // Compute the absolute new X coordinate.
            return Math.round(remoteInput.getPointer().getX() + getDelta(distanceX));
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
            float distanceY = e.getY() - dragY;
            dragY = e.getY();
            // Compute the absolute new Y coordinate.
            return Math.round(remoteInput.getPointer().getY() + getDelta(distanceY));
        }
        dragY = e.getY();
        return remoteInput.getPointer().getY();
    }

    /**
     * Computes how far the pointer will move.
     * @param distance
     * @return
     */
    private float getDelta(float distance) {
        float delta = (float) (distance * Math.cbrt(viewable.getZoomFactor()));
        return computeAcceleration(delta);
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

            // Mirror onScroll's display-density scaling so the fling feels consistent
            // with drag inertia across devices. Include the cbrt(zoomFactor) curve
            // that getDelta() applies, otherwise the fling speed scales differently
            // at different zoom levels than on-scroll does.
            float sensitivity = remoteInput.getPointer().getSensitivity();
            float zoomCurve = (float) Math.cbrt(viewable.getZoomFactor());
            float dt = (float) FLING_TICK_MS / 1000f;
            float dx = vx * dt * sensitivity / displayDensity * zoomCurve;
            float dy = vy * dt * sensitivity / displayDensity * zoomCurve;
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
            // Mirror Flinger's per-tick math (sensitivity, density, cbrt(zoom))
            // so the slow drag-hold motion feels consistent with regular drag
            // inertia at the same zoom level.
            float sensitivity = remoteInput.getPointer().getSensitivity();
            float zoomCurve = (float) Math.cbrt(viewable.getZoomFactor());
            float dt = (float) FLING_TICK_MS / 1000f;
            x = Math.round(x + vx * dt * sensitivity / displayDensity * zoomCurve);
            y = Math.round(y + vy * dt * sensitivity / displayDensity * zoomCurve);

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
