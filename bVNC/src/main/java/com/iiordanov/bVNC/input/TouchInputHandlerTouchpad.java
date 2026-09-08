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
     * RDP-style gestures (fling, long-press=right-click, double-tap-and-hold=drag) only apply
     * to RDP touchpad sessions. Non-RDP touchpad sessions keep the legacy behaviour.
     */
    private boolean isRdp = false;

    /** Tick interval (ms) and per-tick damping factor for the fling deceleration. */
    private static final int FLING_TICK_MS = 20;
    private static final float FLING_DAMP = 0.86f;
    /** Below this speed (px/s in either axis) the fling stops to avoid jitter. */
    private static final float FLING_NOISE_PX_PER_S = 200f;
    /** How long (ms) after the second tap we wait before deciding double-click vs drag. */
    private static final long DOUBLE_TAP_HOLD_MS = 180L;

    private final Flinger flinger;
    private Runnable pendingDoubleTapCommit;
    private boolean doubleTapFingerUp;
    private int doubleTapX;
    private int doubleTapY;
    private int doubleTapMeta;

    /**
     * If {@link #rightDragFromLongPress} is set, release the right button at these
     * coordinates (where the user originally pressed) rather than at the finger-lift
     * position. Mirrors the Microsoft RDP touchpad behaviour where long-press = right
     * click is anchored to the press point.
     */
    private boolean rightDragFromLongPress;
    private int longPressReleaseX;
    private int longPressReleaseY;

    public TouchInputHandlerTouchpad(TouchInputDelegate touchInputDelegate, Viewable viewable,
                                     InputCarriable remoteInput, boolean debugLogging,
                                     float scrollRate) {
        super(touchInputDelegate, viewable, remoteInput, debugLogging, scrollRate);
        this.flinger = new Flinger(viewable.getHandler());
    }

    /**
     * Set by the integration pass to enable Microsoft-RDP-style touchpad gestures
     * (fling / long-press=right-click / double-tap-and-hold=left-drag) for this instance.
     */
    public void setRdp(boolean isRdp) {
        this.isRdp = isRdp;
        if (!isRdp) {
            // Cancel any in-flight gestures to avoid leaving the state machine
            // half-initialized when the flag flips off mid-gesture.
            flinger.stop();
            cancelPendingDoubleTapCommit();
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
            rightDragFromLongPress = false;
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
            // While a right-button drag (from long-press) is held, do not let incremental finger
            // jitter translate into cursor drift — the right click should land at the original
            // touch position, not chase a moving cursor.
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
        flinger.stop();
        return true;
    }

    /**
     * RDP-only long-press fires a RIGHT mouse-button click while the finger is still down —
     * replacing the legacy left-drag that TouchInputHandlerGeneric.onLongPress would start.
     * The existing Generic UP path still releases the right button on finger lift via
     * endDragModesAndScrolling + pointer.releaseButton.
     *
     * <p>The release point is recorded so {@link #onTouchEvent(MotionEvent)} can
     * re-anchor the release at the press position (matching the Microsoft RDP
     * touchpad semantics).
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
        rightDragMode = true;
        rightDragFromLongPress = true;
        longPressReleaseX = getX(e);
        longPressReleaseY = getY(e);
        remoteInput.getPointer().rightButtonDown(longPressReleaseX, longPressReleaseY, metaState);
    }

    /**
     * RDP-only double-tap-and-hold semantics:
     *  - fire click #1 immediately,
     *  - wait {@link #DOUBLE_TAP_HOLD_MS},
     *  - if the finger lifted in the meantime, fire click #2 (= a double-click);
     *  - if the finger is still down, arm a left-drag from the tap position (the existing
     *    Generic ACTION_MOVE path will then move the mouse while the left button stays held,
     *    thanks to the button-mask fix in the pointer classes).
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (!isRdp) {
            return super.onDoubleTap(e);
        }
        GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTap, e: " + e);

        cancelPendingDoubleTapCommit();

        final int metaState = e.getMetaState();
        doubleTapX = getX(e);
        doubleTapY = getY(e);
        doubleTapMeta = metaState;
        doubleTapFingerUp = false;

        performTapClick(doubleTapX, doubleTapY, metaState);

        Handler handler = viewable.getHandler();
        pendingDoubleTapCommit = new Runnable() {
            @Override
            public void run() {
                pendingDoubleTapCommit = null;
                if (doubleTapFingerUp) {
                    // Finger lifted during the hold window — finish the second tap.
                    performTapClick(doubleTapX, doubleTapY, doubleTapMeta);
                } else {
                    // Finger is still down — promote to a left-button drag from the tap point.
                    dragMode = true;
                    remoteInput.getPointer().leftButtonDown(doubleTapX, doubleTapY, doubleTapMeta);
                }
            }
        };
        handler.postDelayed(pendingDoubleTapCommit, DOUBLE_TAP_HOLD_MS);
        return true;
    }

    /**
     * Tracks finger state while a double-tap-and-hold commit is pending so we can decide
     * whether the user released (→ double-click) or kept the finger down (→ left-drag).
     * Also re-anchors the long-press right-button release at the press position.
     * All other touch behaviour flows unchanged through {@code super.onTouchEvent}.
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isRdp) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP && rightDragFromLongPress) {
                // Re-anchor the release at the press position. We do this BEFORE
                // super.onTouchEvent runs so that the Generic UP path's
                // releaseButton(getX(e), getY(e), meta) call (which uses the
                // pointer's current position when rightDragMode has been cleared
                // by endDragModesAndScrolling) lands on the press point instead
                // of the finger-lift point. The MOVE path may have nudged the
                // pointer slightly off press while the user held, so we restore.
                remoteInput.getPointer().moveMouse(longPressReleaseX, longPressReleaseY, 0);
                rightDragFromLongPress = false;
            }
            if (pendingDoubleTapCommit != null) {
                // We are inside the DOUBLE_TAP_HOLD_MS window for the second tap of a
                // double-tap-and-hold gesture. Watch the primary finger.
                if (action == MotionEvent.ACTION_UP) {
                    if (e.getActionIndex() == 0) {
                        doubleTapFingerUp = true;
                    }
                } else if (action == MotionEvent.ACTION_DOWN) {
                    // A new gesture started (e.g. third tap, or new drag) — cancel the
                    // pending commit. We don't flush anything because the third tap's
                    // own onSingleTap/onDoubleTap will fire as the gesture detector
                    // sees a fresh sequence.
                    cancelPendingDoubleTapCommit();
                    doubleTapFingerUp = false;
                }
            }
        }
        return super.onTouchEvent(e);
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

    private void cancelPendingDoubleTapCommit() {
        if (pendingDoubleTapCommit != null) {
            viewable.getHandler().removeCallbacks(pendingDoubleTapCommit);
            pendingDoubleTapCommit = null;
        }
    }

    /*
     * (non-Javadoc)
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

    /*
     * (non-Javadoc)
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
            delta = delta * 0.75f;
        } else if (accelerated && delta <= 70.0f) {
            delta = delta * delta / 20.0f;
        } else if (accelerated) {
            delta = delta * 4.5f;
        }
        return origSign * delta;
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
            // Brake by the per-tick damping factor.
            vx *= FLING_DAMP;
            vy *= FLING_DAMP;

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
}
