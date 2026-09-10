/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
 * Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
 * Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
 * Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
 * Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
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

//
// RemoteCanvas is a subclass of android.view.SurfaceView which draws a VNC
// desktop on it.
//

package com.iiordanov.bVNC;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Method;

import androidx.appcompat.widget.AppCompatImageView;

import com.google.android.material.snackbar.Snackbar;
import com.iiordanov.android.bc.BCFactory;
import com.iiordanov.bVNC.input.TouchInputHandlerTouchpad;
import com.undatech.opaque.AbstractDrawableData;
import com.undatech.opaque.Connection;
import com.undatech.opaque.DrawableReallocatedListener;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.Viewable;
import com.undatech.opaque.input.RemotePointer;
import com.undatech.remoteClientUi.R;

public class RemoteCanvas extends AppCompatImageView implements Viewable {
    // Diagnostic tags. Use `adb logcat -s TAG_*` to see only cursor-related logs.
    // Each is a separate component on the cursor pipeline so missing entries
    // pinpoint which link is broken (recv -> gate -> decode -> draw).
    private static final String TAG_CURSOR_RECV   = "TAG_cursor_recv";   // OnPointerEvent entries from RdpCommunicator
    private static final String TAG_CURSOR_GATE   = "TAG_cursor_gate";   // shouldIgnoreHostCursor + softCursorMove
    private static final String TAG_CURSOR_DECODE = "TAG_cursor_decode"; // hostCursorFromRdp
    private static final String TAG_CURSOR_DRAW   = "TAG_cursor_draw";   // setSoftCursor/setCursorRect + hideHostCursor
    private static final String TAG_CURSOR_PROBE  = "TAG_cursor_probe";  // one-shot lib patch detection
    private static boolean cursorProbeOnce = false;
    private final static String TAG = "RemoteCanvas";
    // BUG-002 enhancement: cursor edge threshold in dp. The viewport starts
    // panning when the cursor is within this many dp of the visible-area
    // edge (was previously the px constant Constants.H_THRESH / W_THRESH =
    // 50, which was density-insensitive and felt too coarse on high-DPI
    // panels). Overridable at runtime via setEdgeThresholdDp (Edge Pan
    // Threshold setting); see edgeThreshDp below.
    private static final float EDGE_THRESH_DP = 35.0f;
    private float edgeThreshDp = EDGE_THRESH_DP;

    public AbstractScaling canvasZoomer;

    // Variable indicating that we are currently scrolling in simulated touchpad mode.
    public boolean cursorBeingMoved = false;

    // Connection parameters
    public Connection connection;
    // VNC protocol connection
    public AbstractDrawableData myDrawable;
    // Progress dialog shown at connection time.
    public Runnable setModes;

    /**
     * Handler for the dialogs that display the x509/RDP/SSH key signatures to the user.
     * Also shows the dialogs which show various connection failures.
     */
    public Handler handler;

    // The remote pointer and keyboard
    RemotePointer pointer;
    boolean useFull = false;
    boolean compact = false;

    /*
     * Position of the top left portion of the <i>visible</i> part of the screen, in
     * full-frame coordinates
     */
    int absoluteXPosition = 0, absoluteYPosition = 0;

    /*
     * How much to shift coordinates over when converting from full to view coordinates.
     */
    float shiftX = 0, shiftY = 0;

    /*
     * This variable holds the height of the visible rectangle of the screen. It is used to keep track
     * of how much of the screen is hidden by the soft keyboard if any.
     */
    int visibleHeight = -1;

    /*
     * RDP: physical (full-screen) canvas height captured when the IME is closed; the zoom floor and
     * viewport math use this so the IME never re-scales the desktop or disables the pan.
     */
    private int rdpFullViewHeight = -1;

    /*
     * These variables contain the width and height of the display in pixels
     */
    int displayWidth;
    int displayHeight;
    float displayDensity;

    /*
     * This flag indicates whether this is the VNC client.
     */
    boolean isVnc;

    /*
     * This flag indicates whether this is the SPICE client.
     */
    boolean isSpice;

    /*
     * This flag indicates whether this is the Opaque client.
     */
    boolean isOpaque;
    long lastDraw;
    boolean userPanned = false;

    boolean isForegrounded = false;

    /**
     * This runnable displays a message on the screen.
     */
    CharSequence screenMessage;

    DrawableReallocatedListener drawableReallocatedListener;

    /**
     * Shows a non-fatal error message.
     */
    Runnable showDialogMessage = new Runnable() {
        public void run() {
            Utils.showErrorMessage(getContext(), String.valueOf(screenMessage));
        }
    };

    //Log.d(TAG, "invalidateCanvasRunnable");
    Runnable invalidateCanvasRunnable = this::postInvalidate;

    /**
     * This runnable sets the drawable (contained in myDrawable) for the RemoteCanvas (ImageView).
     */
    private final Runnable drawableSetter = new Runnable() {
        public void run() {
            synchronized (RemoteCanvas.this) {
                Log.d(TAG, "drawableSetter.run");
                if (myDrawable != null) {
                    Log.d(TAG, "drawableSetter myDrawable not null");
                    myDrawable.setImageDrawable(RemoteCanvas.this);
                } else {
                    Log.e(TAG, "drawableSetter myDrawable is null");
                    displayOnScreenMessageLongDuration(R.string.error_out_of_memory);
                }
            }
        }
    };
    private final Runnable showMessage = () -> Utils.showMessage(RemoteCanvas.this, screenMessage, Snackbar.LENGTH_SHORT);

    private final Runnable showLongMessage = () -> Utils.showMessage(RemoteCanvas.this, screenMessage, Snackbar.LENGTH_LONG);

    /**
     * Constructor used by the inflation apparatus
     */
    public RemoteCanvas(final Context context, AttributeSet attrs) {
        super(context, attrs);

        isVnc = Utils.isVnc(getContext());
        isSpice = Utils.isSpice(getContext());
        isOpaque = Utils.isOpaque(getContext());

        // One-shot: probe the loaded FreeRDP Java classes for patch 22's
        // new UIEventListener methods. After about 5s of being connected,
        // the absence of `TAG_cursor_recv: OnPointerEventNew entered` is
        // the smoking gun: the C side drops the bitmap before Java.
        if (!cursorProbeOnce) {
            cursorProbeOnce = true;
            probePatch22();
        }

        final Display display = ((Activity) context).getWindow().getWindowManager().getDefaultDisplay();
        displayWidth = display.getWidth();
        displayHeight = display.getHeight();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        displayDensity = metrics.density;
    }

    /**
     * Reflectively checks whether the FreeRDP vendor Java wrapper class
     * (the one that receives C calls via the patched
     * {@code android_Pointer_*} callbacks) has the new
     * {@code OnPointerEvent*} methods. Patch 22 makes them
     * {@code default}; without the patch the interface lacks them entirely.
     */
    private void probePatch22() {
        String versionInfo = "unknown";
        try {
            versionInfo = (String) Class.forName("com.freerdp.freerdpcore.services.LibFreeRDP")
                    .getMethod("getVersion").invoke(null);
        } catch (Throwable t) {
            Log.i(TAG_CURSOR_PROBE, "LibFreeRDP.getVersion() failed: " + t);
        }
        Log.i(TAG_CURSOR_PROBE, "LibFreeRDP.getVersion() -> " + versionInfo);
        try {
            Class<?> uiEventListenerClass = Class.forName(
                    "com.freerdp.freerdpcore.services.LibFreeRDP$UIEventListener");
            int foundDefault = 0;
            for (Method m : uiEventListenerClass.getDeclaredMethods()) {
                if (m.getName().startsWith("OnPointerEvent") && m.isDefault()) {
                    foundDefault++;
                }
            }
            Log.i(TAG_CURSOR_PROBE,
                    "UIEventListener.OnPointerEvent* default methods: " + foundDefault
                    + "  (patch 22 expected: 5; vanilla FreeRDP 2.11.7 expected: 0)");
        } catch (Throwable t) {
            Log.i(TAG_CURSOR_PROBE, "UIEventListener introspection failed: " + t);
        }
    }

    public void setParameters(
            DrawableReallocatedListener drawableReallocatedListener,
            Connection connection,
            Handler handler,
            RemotePointer pointer,
            Runnable setModes
    ) {
        this.drawableReallocatedListener = drawableReallocatedListener;
        this.connection = connection;
        this.handler = handler;
        this.pointer = pointer;
        this.setModes = setModes;
    }

    /**
     * Retrieves the requested remote width.
     */
    @Override
    public int getDesiredWidth() {
        int w = getRemoteWidth(getWidth(), getHeight());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM) {
            w = connection.getRdpWidth();
        }
        Log.d(TAG, "Width requested: " + w);
        return w;
    }

    /**
     * Retrieves the requested remote height.
     */
    @Override
    public int getDesiredHeight() {
        int h = getRemoteHeight(getWidth(), getHeight());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM) {
            h = connection.getRdpHeight();
        }
        Log.d(TAG, "Height requested: " + h);
        return h;
    }

    /**
     * Retrieves the requested remote width.
     */
    public int getRemoteWidth(int viewWidth, int viewHeight) {
        int remoteWidth;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteWidth = reqWidth;
        } else if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_NATIVE_PORTRAIT) {
            remoteWidth = Math.min(viewWidth, viewHeight);
        } else if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_NATIVE_LANDSCAPE) {
            remoteWidth = Math.max(viewWidth, viewHeight);
        } else {
            remoteWidth = viewWidth;
        }
        // We make the resolution even if it is odd.
        if (remoteWidth % 2 == 1) remoteWidth--;
        return remoteWidth;
    }

    /**
     * Retrieves the requested remote height.
     */
    public int getRemoteHeight(int viewWidth, int viewHeight) {
        int remoteHeight;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteHeight = reqHeight;
        } else if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_NATIVE_PORTRAIT) {
            remoteHeight = Math.max(viewWidth, viewHeight);
        } else if (connection.getRdpResType() == RemoteClientLibConstants.RDP_GEOM_SELECT_NATIVE_LANDSCAPE) {
            remoteHeight = Math.min(viewWidth, viewHeight);
        } else {
            remoteHeight = viewHeight;
        }
        // We make the resolution even if it is odd.
        if (remoteHeight % 2 == 1) remoteHeight--;
        return remoteHeight;
    }

    @Override
    public void writeScreenshotToFile(String filePath, int dstWidth) {
        Utils.writeScreenshotToFile(myDrawable, filePath, dstWidth);
    }

    void showMessage(final String error) {
        Log.d(TAG, "showMessage");
        screenMessage = error;
        postMessage(showDialogMessage);
    }

    /**
     * Initializes the drawable and bitmap into which the remote desktop is drawn.
     */
    @Override
    public void reallocateDrawable(int dx, int dy) {
        Log.i(TAG, "Desktop size is " + dx + " x " + dy);

        int fbSize = dx * dy;

        // Internal bitmap data
        int capacity = BCFactory.getInstance().getBCActivityManager().getMemoryClass(Utils.getActivityManager(getContext()));

        if (connection.getForceFull() == BitmapImplHint.AUTO) {
            if (fbSize * CompactBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024) {
                useFull = true;
                compact = true;
            } else {
                useFull = true;
            }
        } else {
            useFull = (connection.getForceFull() == BitmapImplHint.FULL);
        }

        reallocateMyDrawable(dx, dy);

        try {
            if (needsLocalCursor()) {
                initializeSoftCursor();
            }
            postDrawableSetter();
            handler.post(setModes);
            syncScroll();
            drawableReallocatedListener.setBitmapData(myDrawable);
        } catch (NullPointerException e) {
            Log.e(TAG, "Caught a NullPointerException");
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    private void reallocateMyDrawable(int dx, int dy) {
        synchronized (this) {
            if (!isVnc) {
                Log.i(TAG, "Using UltraCompactBufferBitmapData.");
                myDrawable = new UltraCompactBitmapData(dx, dy, this, isSpice | isOpaque);
            } else {
                try {
                    if (!compact) {
                        Log.i(TAG, "Using FullBufferBitmapData.");
                        myDrawable = new FullBufferBitmapData(dx, dy, this);
                    } else {
                        Log.i(TAG, "Using CompactBufferBitmapData.");
                        myDrawable = new CompactBitmapData(dx, dy, this, isSpice | isOpaque);
                    }
                } catch (Throwable e) { // If despite our efforts we fail to allocate memory, use CompactBitmapData.
                    Log.e(TAG, "Could not allocate drawable, attempting to use CompactBitmapData.");
                    if (myDrawable != null) {
                        myDrawable.dispose();
                    }
                    myDrawable = new CompactBitmapData(dx, dy, this, isSpice | isOpaque);
                }
            }
        }
    }

/**
     * Determines if the app should show a local cursor or not.
     *
     * <p>This only governs the initial {@code reallocateDrawable()} seed.
     * Cursor updates that arrive later (VNC decoder push, RDP host bitmap
     * via OnPointerEventNew/Set) replace this seed on the softCursor
     * layer regardless of what this returns.</p>
     *
     * <p>AUTO behavior:
     * <ul>
     * <li><b>SPICE/Opaque</b> still seed the local PNG because their
     *     host-cursor callback is an un-implemented TODO stub at
     *     {@code remoteClientLib/src/main/cpp/android/android-spice-widget.c:314-319}.
     *     The server cursor never reaches Java on those protocols.</li>
     * <li><b>RDP</b> does not seed - the softCursor is filled by
     *     OnPointerEventNew/Set, which fire because of patch
     *     {@code 22_freerdp_add_cursor_callback.patch}.</li>
     * <li><b>VNC</b> does not seed here either - VNC's server-cursor path
     *     goes through {@code Decoder.handleCursorShapeUpdate} which sets
     *     the softCursor directly; if the VNC server never sends a cursor
     *     we rely on the lazy-init inside {@link #softCursorMove}.</li>
     * </ul></p>
     *
     * <p>FORCE_LOCAL and FORCE_DISABLE preserve their original meanings on
     * all flavors.</p>
     */
    private boolean needsLocalCursor() {
        int mode = connection.getUseLocalCursor();
        if (mode == Constants.CURSOR_FORCE_LOCAL) return true;
        if (mode == Constants.CURSOR_FORCE_DISABLE) return false;
        // CURSOR_AUTO: only seed for protocols that never receive a
        // host bitmap. RDP relies on OnPointerEvent{New,Set} for that
        // bitmap; VNC uses the decoder path; SPICE/Opaque have no path.
        return isSpice || isOpaque;
    }

    @Override
    public void postDrawableSetter() {
        handler.post(drawableSetter);
    }


    @Override
    public void prepareFullUpdateRequest(boolean incremental) {
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.prepareFullUpdateRequest(incremental);
            }
        }
    }

    @Override
    public int getXoffset() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.getXoffset();
            }
            return 0;
        }
    }

    @Override
    public int getYoffset() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.getYoffset();
            }
            return 0;
        }
    }

    @Override
    public int bmWidth() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.bmWidth();
            }
            return 0;
        }
    }

    @Override
    public int bmHeight() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.bmHeight();
            }
            return 0;
        }
    }

    /**
     * Displays a message on the screen for a short duration.
     */
    public void displayOnScreenMessageShortDuration(final CharSequence message) {
        screenMessage = message;
        postMessage(showMessage);
    }

    @Override
    public void displayOnScreenMessageLongDuration(final CharSequence message) {
        screenMessage = message;
        postMessage(showLongMessage);
    }

    /**
     * Displays a message on the screen for a short duration
     */
    @Override
    public void displayOnScreenMessageShortDuration(final int messageID) {
        screenMessage = getResources().getText(messageID);
        postMessage(showMessage);
    }

    @Override
    public void displayOnScreenMessageLongDuration(final int messageID) {
        screenMessage = getResources().getText(messageID);
        postMessage(showLongMessage);
    }

    private void postMessage(Runnable showMessage) {
        handler.removeCallbacks(showMessage);
        handler.post(showMessage);
    }

    /**
     * Lets the drawable know that an update from the remote server has arrived.
     */
    public void doneWaiting() {
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.doneWaiting();
            }
        }
    }

    /**
     * Indicates that RemoteCanvas's scroll position should be synchronized with the
     * drawable's scroll position (used only in LargeBitmapData)
     */
    public void syncScroll() {
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.syncScroll();
            }
        }
    }

    @Override
    public boolean isUseFull() {
        return useFull;
    }

    /**
     * Computes the X and Y offset for converting coordinates from full-frame coordinates to view coordinates.
     */
    public void computeShiftFromFullToView() {
        synchronized (this) {
            if (myDrawable != null) {
                shiftX = (myDrawable.fbWidth() - getWidth()) / 2.0f;
                shiftY = (myDrawable.fbHeight() - getHeight()) / 2.0f;
            }
        }
    }

    /**
     * Change to Canvas's scroll position to match the absoluteXPosition
     */
    void resetScroll() {
        float scale = getZoomFactor();
        Log.d(TAG, "resetScroll: " + (absoluteXPosition - shiftX) * scale + ", "
                + (absoluteYPosition - shiftY) * scale);
        scrollTo((int) ((absoluteXPosition - shiftX) * scale),
                (int) ((absoluteYPosition - shiftY) * scale));
    }

    /**
     * Make sure mouse is visible on displayable part of screen
     */
    public void movePanToMakePointerVisible() {
        //Log.d(TAG, "movePanToMakePointerVisible");
        boolean panX = true;
        boolean panY = true;

        // Don't pan in a certain direction if dimension scaled is already less
        // than the dimension of the visible part of the screen.
        synchronized (this) {
            if (myDrawable != null) {
                if (myDrawable.fbWidth() < getVisibleDesktopWidth()) {
                    panX = false;
                }
                if (myDrawable.fbHeight() < getVisibleDesktopHeight()) {
                    panY = false;
                }
            }
        }

        // BUG-002 fix: do NOT early-exit on canvasZoomer.isAbleToPan().
        // FitToScreenScaling.isAbleToPan() is a hard-coded `false` constant and
        // does not inspect dimensions — so when the surface perfectly fits the
        // desktop (e.g. 1440x3216 == 1440x3216) the user is in fit-to-screen,
        // the early-exit fires, and the viewport never pans to follow the
        // cursor. That's correct when there is no IME/input area, but the IME
        // shrinks visibleDesktopHeight (e.g. 3216 -> 1886) so the framebuffer
        // is now taller than the visible area and pan IS needed.
        //
        // The dimension gate above already does the right thing: it sets
        // panX/panY=false when fbDim < visDim, so panning is geometrically
        // impossible; otherwise it lets the pan logic run.

        int x = pointer.getX();
        int y = pointer.getY();
        boolean panned = false;
        int w = getVisibleDesktopWidth();
        int h = getVisibleDesktopHeight();
        int iw = getImageWidth();
        int ih = getImageHeight();
        // BUG-002: cursor edge threshold for viewport panning. See EDGE_THRESH_DP
        // for the default and history; overridable via setEdgeThresholdDp.
        float density = getResources().getDisplayMetrics().density;
        int edgeThreshPx = (int) (edgeThreshDp * density + 0.5f);
        int wThresh = edgeThreshPx;
        int hThresh = edgeThreshPx;

        int newX = absoluteXPosition;
        int newY = absoluteYPosition;

        if (x - absoluteXPosition >= w - wThresh) {
            newX = x - (w - wThresh);
            if (newX + w > iw)
                newX = iw - w;
        } else if (x < absoluteXPosition + wThresh) {
            newX = x - wThresh;
            if (newX < 0)
                newX = 0;
        }
        if (panX && newX != absoluteXPosition) {
            absoluteXPosition = newX;
            panned = true;
        }

        if (y - absoluteYPosition >= h - hThresh) {
            newY = y - (h - hThresh);
            if (newY + h > ih)
                newY = ih - h;
        } else if (y < absoluteYPosition + hThresh) {
            newY = y - hThresh;
            if (newY < 0)
                newY = 0;
        }
        if (panY && newY != absoluteYPosition) {
            absoluteYPosition = newY;
            panned = true;
        }

        if (panned) {
            //scrollBy(newX - absoluteXPosition, newY - absoluteYPosition);
            resetScroll();
        }
    }

    public int getTopMargin(double scale) {
        return (int) (Constants.TOP_MARGIN / scale);
    }

    public int getBottomMargin(double scale) {
        return (int) (Constants.BOTTOM_MARGIN / scale);
    }

    /**
     * Pan by a number of pixels (relative pan)
     *
     * @return True if the pan changed the view (did not move view out of bounds); false otherwise
     */
    public boolean relativePan(float dX, float dY) {
        Log.d(TAG, "relativePan: " + dX + ", " + dY);

        // We only pan if the current scaling is able to pan.
        if (canvasZoomer != null && !canvasZoomer.isAbleToPan())
            return false;

        double scale = getZoomFactor();

        double sX = (double) dX / scale;
        double sY = (double) dY / scale;

        int buttonAndCurveOffset = getBottomMargin(scale);
        int curveOffset = 0;
        if (userPanned) {
            curveOffset = getTopMargin(scale);
        }

        userPanned = dX != 0.0 || dY != 0.0;

        // Prevent panning above the desktop image except for provision for curved screens.
        if (absoluteXPosition + sX < 0)
            // dX = diff to 0
            sX = -absoluteXPosition;
        if (absoluteYPosition + sY < -curveOffset)
            sY = -absoluteYPosition - curveOffset;

        // Prevent panning right or below desktop image except for provision for on-screen
        // buttons and curved screens
        if (absoluteXPosition + getVisibleDesktopWidth() + sX > getImageWidth())
            sX = getImageWidth() - getVisibleDesktopWidth() - absoluteXPosition;
        if (absoluteYPosition + getVisibleDesktopHeight() + sY > getImageHeight() + buttonAndCurveOffset)
            sY = getImageHeight() - getVisibleDesktopHeight() - absoluteYPosition + buttonAndCurveOffset;

        absoluteXPosition += (int) sX;
        absoluteYPosition += (int) sY;
        resetScroll();
        return true;
    }

    /**
     * Absolute pan.
     */
    public void absolutePan(int x, int y) {
        //Log.d(TAG, "absolutePan: " + x + ", " + y);

        if (canvasZoomer != null) {
            int vW = getVisibleDesktopWidth();
            int vH = getVisibleDesktopHeight();
            int w = getImageWidth();
            int h = getImageHeight();
            if (x + vW > w) x = w - vW;
            if (y + vH > h) y = h - vH;
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            absoluteXPosition = x;
            absoluteYPosition = y;
            resetScroll();
        }
    }

    /* (non-Javadoc)
     * @see android.view.View#onScrollChanged(int, int, int, int)
     */
    @Override
    protected void onScrollChanged(int l, int t, int oldL, int oldT) {
        super.onScrollChanged(l, t, oldL, oldT);
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.scrollChanged(absoluteXPosition, absoluteYPosition);
            }
        }
    }

    @Override
    public Bitmap getBitmap() {
        synchronized (this) {
            Bitmap bitmap = null;
            if (myDrawable != null) {
                bitmap = myDrawable.getMbitmap();
            }
            return bitmap;
        }
    }

    /**
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(int x, int y, int w, int h) {
        //Log.i(TAG, "reDraw called: " + x + ", " + y + " + " + w + "x" + h);
        long timeNow = System.currentTimeMillis();
        if (timeNow - lastDraw > 16.6666) {
            float scale = getZoomFactor();
            float shiftedX = x - shiftX;
            float shiftedY = y - shiftY;
            // Make the box slightly larger to avoid artifacts due to truncation errors.
            postInvalidate((int) ((shiftedX - 1) * scale), (int) ((shiftedY - 1) * scale),
                    (int) ((shiftedX + w + 1) * scale), (int) ((shiftedY + h + 1) * scale));
            lastDraw = timeNow;
        } else {
            handler.removeCallbacks(invalidateCanvasRunnable);
            handler.postDelayed(invalidateCanvasRunnable, 100);
        }
    }

    /**
     * This is a float-accepting version of reDraw().
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(float x, float y, float w, float h) {
        //Log.i(TAG, "reDraw float called: " + x + ", " + y + " + " + w + "x" + h);
        long timeNow = System.currentTimeMillis();
        if (timeNow - lastDraw > 16.6666) {
            float scale = getZoomFactor();
            float shiftedX = x - shiftX;
            float shiftedY = y - shiftY;
            // Make the box slightly larger to avoid artifacts due to truncation errors.
            postInvalidate((int) ((shiftedX - 1.f) * scale), (int) ((shiftedY - 1.f) * scale),
                    (int) ((shiftedX + w + 1.f) * scale), (int) ((shiftedY + h + 1.f) * scale));
            lastDraw = timeNow;
        } else {
            handler.removeCallbacks(invalidateCanvasRunnable);
            handler.postDelayed(invalidateCanvasRunnable, 100);
        }
    }

    /**
     * Invalidates (to redraw) the location of the remote pointer.
     */
    public void invalidateMousePosition() {
        synchronized (this) {
            if (myDrawable != null) {
                moveSoftCursor(pointer.getX(), pointer.getY());
                RectF r = getCursorRect();
                reDraw(r.left, r.top, r.width(), r.height());
            }
        }
    }

    @Override
    public void setMousePointerPosition(int x, int y) {
        softCursorMove(x, y);
    }

    @Override
    public void mouseMode(boolean relative) {
        if (relative && !connection.getInputMode().equals(TouchInputHandlerTouchpad.ID)) {
            showMessage(getContext().getString(R.string.info_set_touchpad_input_mode));
        } else {
            this.pointer.setRelativeEvents(relative);
        }
    }

    @Override
    public boolean isAbleToPan() {
        return canvasZoomer.isAbleToPan();
    }

    /**
     * Moves soft cursor into a particular location.
     */
    synchronized public void softCursorMove(int x, int y) {
        Log.i(TAG_CURSOR_GATE, "softCursorMove (" + x + "," + y + ")"
                + " initialized=" + !isNotInitSoftCursor()
                + " mode=" + (connection != null ? connection.getUseLocalCursor() : -1)
                + " isCursorBeingMoved=" + isCursorBeingMoved()
                + " relative=" + pointer.isRelativeEvents());
        // Lazy-init only on protocols that have no other cursor source:
        //   - VNC: server cursor is fed by Decoder.handleCursorShapeUpdate;
        //     lazy-init ensures something visible if the server never sends one.
        //   - SPICE/Opaque: TODO stub at android-spice-widget.c:314-319.
        // RDP under CURSOR_AUTO never lazily-inits here because the cursor
        // bitmap arrives via OnPointerEventNew/Set from the patched
        // android_register_pointer in FreeRDP client/Android.
        if (isNotInitSoftCursor() && connection.getUseLocalCursor() != Constants.CURSOR_FORCE_DISABLE
            && (isVnc || isSpice || isOpaque)) {
            Log.i(TAG_CURSOR_GATE, "softCursorMove: lazy-init local softCursor PNG");
            initializeSoftCursor();
        }

        if (!isCursorBeingMoved() || pointer.isRelativeEvents()) {
            pointer.setX(x);
            pointer.setY(y);
            RectF prevR = new RectF(getCursorRect());
            // Move the cursor.
            moveSoftCursor(x, y);
            // Show the cursor.
            RectF r = getCursorRect();
            reDraw(r.left, r.top, r.width(), r.height());
            reDraw(prevR.left, prevR.top, prevR.width(), prevR.height());
        }
    }

    private RectF getCursorRect() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.getCursorRect();
            }
            return new RectF();
        }
    }

    private void moveSoftCursor(int x, int y) {
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.moveCursorRect(x, y);
            }
        }
    }

    private boolean isNotInitSoftCursor() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.isNotInitSoftCursor();
            }
            return false;
        }
    }

    /**
     * Initializes the data structure which holds the remote pointer data.
     */
    void initializeSoftCursor() {
        Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.cursor);
        int w = bm.getWidth();
        int h = bm.getHeight();
        int[] tempPixels = new int[w * h];
        bm.getPixels(tempPixels, 0, w, 0, 0, w, h);
        // Set cursor rectangle as well.
        setSoftCursorRectAndPixels(w, h, tempPixels);
        bm.recycle();
    }

    private void setSoftCursorRectAndPixels(int w, int h, int[] tempPixels) {
        setSoftCursorRectAndPixels(w, h, 0, 0, tempPixels);
    }

    private void setSoftCursorRectAndPixels(int w, int h, int hotX, int hotY, int[] tempPixels) {
        synchronized (this) {
            if (myDrawable != null) {
                myDrawable.setCursorRect(pointer.getX(), pointer.getY(), w, h, hotX, hotY);
                // Set softCursor to whatever the resource is.
                myDrawable.setSoftCursor(tempPixels);
                Log.i(TAG_CURSOR_DRAW, "setCursorRect(" + pointer.getX() + "," + pointer.getY()
                        + " " + w + "x" + h + " hot=" + hotX + "," + hotY + ")"
                        + " + setSoftCursor(" + tempPixels.length + " px)");
            } else {
                Log.w(TAG_CURSOR_DRAW, "setSoftCursorRectAndPixels myDrawable==null "
                        + w + "x" + h + " hot=" + hotX + "," + hotY);
            }
        }
    }

    // The following four overrides receive the host cursor from FreeRDP via
    // RdpCommunicator. Without the corresponding patch
    // remoteClientLib/jni/libs/22_freerdp_add_cursor_callback.patch the
    // stub C-side callbacks in libfreerdp/cache/pointer.c would drop
    // the cursor bitmap and these would never fire.

    @Override
    public void OnPointerEventNew(byte[] andMask, byte[] xorMask, int width, int height,
                                  int xorBpp, int lengthAndMask, int lengthXorMask,
                                  int hotspotX, int hotspotY) {
        Log.i(TAG_CURSOR_RECV, "OnPointerEventNew entered " + width + "x" + height
                + " xorBpp=" + xorBpp + " hotspot=(" + hotspotX + "," + hotspotY + ")"
                + " andLen=" + lengthAndMask + " xorLen=" + lengthXorMask
                + " andArr=" + (andMask != null ? andMask.length : "null")
                + " xorArr=" + (xorMask != null ? xorMask.length : "null"));
        if (shouldIgnoreHostCursor()) return;
        hostCursorFromRdp(andMask, xorMask, width, height, xorBpp, hotspotX, hotspotY);
    }

    @Override
    public void OnPointerEventSet(byte[] andMask, byte[] xorMask, int width, int height,
                                  int xorBpp, int lengthAndMask, int lengthXorMask,
                                  int hotspotX, int hotspotY) {
        Log.i(TAG_CURSOR_RECV, "OnPointerEventSet entered " + width + "x" + height
                + " xorBpp=" + xorBpp + " hotspot=(" + hotspotX + "," + hotspotY + ")");
        // No cursor-cache lookup on the Java side; FreeRDP's C-side cache
        // owns the bitmap memory and re-fires with full payload on every
        // activation, so the same decoder path works for both New and Set.
        OnPointerEventNew(andMask, xorMask, width, height, xorBpp,
                          lengthAndMask, lengthXorMask, hotspotX, hotspotY);
    }

    @Override
    public void OnPointerEventSetPosition(int x, int y) {
        Log.i(TAG_CURSOR_RECV, "OnPointerEventSetPosition (" + x + "," + y + ")");
        if (shouldIgnoreHostCursor()) return;
        softCursorMove(x, y);
    }

    @Override
    public void OnPointerEventHide() {
        Log.i(TAG_CURSOR_RECV, "OnPointerEventHide");
        if (shouldIgnoreHostCursor()) return;
        hideHostCursor();
    }

    @Override
    public void OnPointerEventDefault() {
        Log.i(TAG_CURSOR_RECV, "OnPointerEventDefault");
        if (shouldIgnoreHostCursor()) return;
        hideHostCursor();
    }

    /**
     * Determines whether host-driven cursor updates from RDP should be
     * applied at all. Active only on RDP (VNC has its own
     * Decoder.handleCursorShapeUpdate path, SPICE/Opaque leave the cursor
     * to the local PNG). Disabled by CURSOR_FORCE_DISABLE; ignored
     * (replaced by local PNG) under CURSOR_FORCE_LOCAL.
     */
    private boolean shouldIgnoreHostCursor() {
        int mode = connection.getUseLocalCursor();
        boolean ignore;
        if (mode == Constants.CURSOR_FORCE_DISABLE) ignore = true;
        else if (isVnc) ignore = true;
        else if (isSpice || isOpaque) ignore = true;
        else ignore = false;
        Log.i(TAG_CURSOR_GATE, "shouldIgnoreHostCursor mode=" + mode
                + " isVnc=" + isVnc + " isSpice=" + isSpice + " isOpaque=" + isOpaque
                + " -> " + ignore);
        return ignore;
    }

    /**
     * Decodes the AND/XOR mask pair from FreeRDP into an Android ARGB_8888
     * int[] suitable for {@code AbstractBitmapDrawable.setSoftCursor}.
     *
     * <p>The RDP protocol transmits cursor bitmaps as two MSB-first packed
     * monochrome planes:
     * <ul>
     * <li>{@code andMask} 1bpp:  {@code 1} = transparent, {@code 0} =
     *     render the XOR-pixel at this position.</li>
     * <li>{@code xorMask} depth depends on {@code xorBpp} (1, 4, 8, 16,
     *     24, 32).</li>
     * </ul>
     * Higher-depth XOR masks are stored bottom-up, BGRA for 24/32bpp. For
     * sub-32bpp shapes we fall back to a black/white interpretation which
     * matches what newer FreeRDP clients do for legacy 1bpp cursors.
     */
    private void hostCursorFromRdp(byte[] andMask, byte[] xorMask, int width, int height,
                                   int xorBpp, int hotspotX, int hotspotY) {
        Log.i(TAG_CURSOR_DECODE, "hostCursorFromRdp " + width + "x" + height
                + " xorBpp=" + xorBpp + " hotspot=(" + hotspotX + "," + hotspotY + ")"
                + " andLen=" + (andMask != null ? andMask.length : -1)
                + " xorLen=" + (xorMask != null ? xorMask.length : -1));
        if (width <= 0 || height <= 0) {
            Log.w(TAG_CURSOR_DECODE, "non-positive dimensions, ignoring");
            return;
        }
        if (xorMask == null && andMask == null) {
            Log.w(TAG_CURSOR_DECODE, "null masks, ignoring");
            return;
        }
        int[] pixels = new int[width * height];
        // MS-RDPBCGR §2.2.9.1.1.4: AND/XOR masks are stored bottom-up in the
        // wire cache for color cursors (xorBpp >= 24). For the legacy
        // mono-pointer TS_PTRMSG_TYPE_POINTER path (xorBpp == 1), rows are
        // top-down (FreeRDP's vFlip is FALSE in
        // freerdp_image_copy_from_pointer_data_1bpp when xorBpp == 1).
        // AND-mask scanlines are 2-byte aligned on the wire (stride =
        // (((width+7)/8) + 1) & ~1), not (width+7)/8; widths 16/32 happen
        // to fit, widths 24+ misread otherwise. XOR-mask rows for color
        // cursors are tight packed per xorBpp; we expand to ARGB below.
        int andStride = (((width + 7) / 8) + 1) & ~1;
        // AND mask convention — matches libfreerdp/codec/color.c:377-384:
        //   AND=0, XOR=0 → opaque black  (cursor body fill)
        //   AND=0, XOR=1 → opaque white  (cursor border / filled variant)
        //   AND=1, XOR=0 → transparent   (don't draw cursor here)
        //   AND=1, XOR=1 → opaque "screen-inverted"; we approximate as black
        //     (no screen sampling available) so the I-beam shaft and hand-grab
        //     fill render as a solid body against most backgrounds.
        // The old decoder mapped AND=1 → transparent, which made cursors
        // whose body pixels sit at AND=1 (notably the Windows I-beam) vanish
        // on white-on-white textfields.

        if (xorBpp == 32) {
            /* Color cursor. Match libfreerdp/codec/color.c:469-514 exactly:
             *   AND=0  → show XOR pixel (BGRA → ARGB32).
             *   AND=1, pixel opaque black  → transparent (XOR is at AND=1 to make
             *     the cursor body region pass through to the desktop).
             *   AND=1, pixel opaque white  → "screen-inverted" approximation:
             *     FreeRDP's freerdp_image_inverted_pointer_color is a checkerboard
             *     ((x+y)&1 ? black : white); we approximate as solid black to keep
             *     the cursor visible without screen sampling.
             *   AND=1, other pixels        → leave as-is (anti-alias edges).
             * vFlip is TRUE for xorBpp != 1 (rows arrive bottom-up). */
            int xorRowStride = width * 4;
            for (int y = 0; y < height; y++) {
                int sy = height - 1 - y;
                int xorRowOff = sy * xorRowStride;
                for (int x = 0; x < width; x++) {
                    int idx = y * width + x;
                    int xorOff = xorRowOff + x * 4;
                    if (xorMask == null || xorOff + 3 >= xorMask.length) {
                        pixels[idx] = 0;
                        continue;
                    }
                    int b = xorMask[xorOff] & 0xff;
                    int g = xorMask[xorOff + 1] & 0xff;
                    int r = xorMask[xorOff + 2] & 0xff;
                    int a = xorMask[xorOff + 3] & 0xff;
                    int pixel = (a << 24) | (r << 16) | (g << 8) | b;
                    if (andBit(andMask, x, sy, andStride) != 0) {
                        if ((pixel & 0x00ffffff) == 0 && a != 0)
                            pixels[idx] = 0;             /* opaque black → transparent */
                        else if (pixel == 0xffffffff)
                            pixels[idx] = 0xff000000;    /* opaque white → opaque black (inverted) */
                        else
                            pixels[idx] = pixel;        /* anti-alias etc. — keep as-is */
                    } else {
                        pixels[idx] = pixel;
                    }
                }
            }
        } else if (xorBpp == 24) {
            /* Same convention as 32bpp, but the wire has no alpha channel:
             * every pixel is opaque, so AND=1+opaque-black always becomes
             * transparent (no anti-alias edges pass through). */
            int xorRowStride = width * 3;
            for (int y = 0; y < height; y++) {
                int sy = height - 1 - y;
                int xorRowOff = sy * xorRowStride;
                for (int x = 0; x < width; x++) {
                    int idx = y * width + x;
                    int xorOff = xorRowOff + x * 3;
                    if (xorMask == null || xorOff + 2 >= xorMask.length) {
                        pixels[idx] = 0;
                        continue;
                    }
                    int b = xorMask[xorOff] & 0xff;
                    int g = xorMask[xorOff + 1] & 0xff;
                    int r = xorMask[xorOff + 2] & 0xff;
                    int pixel = 0xff000000 | (r << 16) | (g << 8) | b;
                    if (andBit(andMask, x, sy, andStride) != 0) {
                        if ((pixel & 0x00ffffff) == 0)
                            pixels[idx] = 0;             /* opaque black → transparent */
                        else if (pixel == 0xffffffff)
                            pixels[idx] = 0xff000000;    /* opaque white → opaque black */
                        else
                            pixels[idx] = pixel;
                    } else {
                        pixels[idx] = pixel;
                    }
                }
            }
        } else {
            /* Legacy mono cursor (TS_PTRMSG_TYPE_POINTER with xorBpp==1).
             * Rows are stored top-down (no vflip — FreeRDP sets vFlip=FALSE
             * for xorBpp==1 in freerdp_image_copy_from_pointer_data_1bpp).
             * Apply the AND/XOR truth table from libfreerdp/codec/color.c. */
            int xorStride = (width + 7) / 8;
            for (int y = 0; y < height; y++) {
                int sy = y; /* no vflip for xorBpp == 1 */
                for (int x = 0; x < width; x++) {
                    int idx = y * width + x;
                    int andPixel = andBit(andMask, x, sy, andStride);
                    int xorPixel = xorBit(xorMask, x, sy, xorStride);
                    if (andPixel == 0 && xorPixel == 0)
                        pixels[idx] = 0xff000000;        /* black */
                    else if (andPixel == 0 && xorPixel != 0)
                        pixels[idx] = 0xffffffff;        /* white */
                    else if (andPixel != 0 && xorPixel == 0)
                        pixels[idx] = 0;                 /* transparent */
                    else /* andPixel != 0 && xorPixel != 0 */
                        pixels[idx] = 0xff000000;        /* opaque black ≈ "screen-inverted" */
                }
            }
        }
        setSoftCursorRectAndPixels(width, height, hotspotX, hotspotY, pixels);
        // Diagnostic dump for small cursors (≤ 64 wide): count opaque/black/white/transparent
        // pixels and print the first row as a bitmap. Lets us tell whether the host sent a
        // filled body, an outline, or a fully-transparent bitmap for things like the I-beam.
        if (width <= 64 && height <= 64) {
            int opaque = 0, black = 0, white = 0, transparent = 0;
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int a = (p >>> 24) & 0xff;
                int r = (p >>> 16) & 0xff, g = (p >>> 8) & 0xff, b = p & 0xff;
                if (a == 0) { transparent++; continue; }
                opaque++;
                if (r == 0 && g == 0 && b == 0) black++;
                else if (r == 0xff && g == 0xff && b == 0xff) white++;
            }
            StringBuilder firstRow = new StringBuilder();
            for (int x = 0; x < width && x < 48; x++) {
                int p = pixels[x];
                int a = (p >>> 24) & 0xff;
                if (a == 0) firstRow.append('.');
                else if ((p & 0x00ffffff) == 0) firstRow.append('#');
                else if (p == 0xffffffff) firstRow.append('O');
                else firstRow.append('?');
            }
            Log.i(TAG_CURSOR_DECODE, "decoded " + width + "x" + height
                    + " opaque=" + opaque + " black=" + black + " white=" + white
                    + " transparent=" + transparent
                    + " | first-row: \"" + firstRow + "\"");
        }
        // Force a redraw of the cursor region: on a static framebuffer (the
        // user's exact complaint: hover over a window edge with no other
        // animated pixels nearby) the new bitmap would otherwise sit there
        // invisible until the next framebuffer update triggers a draw.
        reDraw(0, 0, getWidth(), getHeight());
    }

    private static int andBit(byte[] mask, int x, int y, int byteStride) {
        if (mask == null) return 0;
        int off = y * byteStride + (x / 8);
        if (off < 0 || off >= mask.length) return 0;
        return (mask[off] >> (7 - (x % 8))) & 1;
    }

    private static int xorBit(byte[] mask, int x, int y, int byteStride) {
        if (mask == null) return 0;
        int off = y * byteStride + (x / 8);
        if (off < 0 || off >= mask.length) return 0;
        return (mask[off] >> (7 - (x % 8))) & 1;
    }

    /**
     * Removes the softCursor overlay by replacing it with a single
     * transparent pixel and shrinking the cursor rect off-screen so
     * subsequent framebuffer redraws leave no cursor artifact.
     */
    private void hideHostCursor() {
        synchronized (this) {
            if (myDrawable == null) {
                Log.w(TAG_CURSOR_DRAW, "hideHostCursor myDrawable==null");
                return;
            }
            Log.i(TAG_CURSOR_DRAW, "hideHostCursor called");
            // 1x1 transparent: draw path (CompactBitmapData:209 etc.)
            // already treats softCursor==null as no overlay.
            myDrawable.setCursorRect(0, 0, 1, 1, 0, 0);
            myDrawable.setSoftCursor(new int[]{0});
            reDraw(0, 0, getWidth(), getHeight());
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        Log.d(TAG, "onCreateInputConnection called");
        BaseInputConnection bic = new BaseInputConnection(this, false);
        outAttrs.actionLabel = null;
        outAttrs.inputType = getKeyboardVariation();
        String currentIme = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        Log.d(TAG, "currentIme: " + currentIme);
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return bic;
    }

    private int getKeyboardVariation() {
        String keyboardVariationStr = Utils.querySharedPreferenceString(
                getContext(),
                Constants.softwareKeyboardType,
                getContext().getString(R.string.pref_keyboard_type_TYPE_NULL_value)
        );
        int keyboardVariation = 0;
        try {
            keyboardVariation = Integer.parseInt(keyboardVariationStr);
        } catch (NumberFormatException e) {
            Log.e(TAG, e.toString());
        }
        return keyboardVariation;
    }

    public float getZoomFactor() {
        if (canvasZoomer == null)
            return 1;
        return canvasZoomer.getZoomFactor();
    }

    public int getVisibleDesktopWidth() {
        return (int) ((double) getWidth() / getZoomFactor() + 0.5);
    }

    public int getVisibleDesktopHeight() {
        if (visibleHeight > 0)
            return (int) ((double) visibleHeight / getZoomFactor() + 0.5);
        else
            return (int) ((double) getHeight() / getZoomFactor() + 0.5);
    }

    public void setVisibleDesktopHeight(int newHeight) {
        if (newHeight != visibleHeight) {
            // BUG-002 enhancement: when the visible area expands (IME hidden,
            // screen rotation makes more room, etc.) reset the viewport to
            // top-left so the user sees the full desktop again. The IME-up
            // pan keeps the cursor visible while typing; on IME-hide we
            // want the full desktop back. Skip the reset if the viewport
            // was already at the origin (no-op avoids unnecessary work).
            if (newHeight > visibleHeight
                    && (absoluteXPosition != 0 || absoluteYPosition != 0)) {
                absoluteXPosition = 0;
                absoluteYPosition = 0;
                resetScroll();
            }
        }
        visibleHeight = newHeight;
    }

    public void setRdpFullViewHeight(int height) {
        rdpFullViewHeight = height;
    }

    public void setEdgeThresholdDp(float edgeThresholdDp) {
        this.edgeThreshDp = edgeThresholdDp;
    }

    int getRdpFullViewHeight() {
        return rdpFullViewHeight;
    }

    public int getImageWidth() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.fbWidth();
            }
            return 0;
        }
    }

    public int getImageHeight() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.fbHeight();
            }
            return 0;
        }
    }

    public int getCenteredXOffset() {
        synchronized (this) {
            if (myDrawable != null) {
                return (myDrawable.fbWidth() - getWidth()) / 2;
            }
            return 0;
        }
    }

    public int getCenteredYOffset() {
        synchronized (this) {
            if (myDrawable != null) {
                return (myDrawable.fbHeight() - getHeight()) / 2;
            }
            return 0;
        }
    }

    public float getMinimumScale() {
        synchronized (this) {
            if (myDrawable != null) {
                return myDrawable.getMinimumScale();
            }
            return 1.f;
        }
    }

    public float getDisplayDensity() {
        return displayDensity;
    }

    public boolean getMouseFollowPan() {
        return connection.getFollowPan();
    }

    public int getAbsX() {
        return absoluteXPosition;
    }

    public int getAbsY() {
        return absoluteYPosition;
    }

    @Override
    public boolean relativePan(int deltaX, int deltaY) {
        return relativePan((float) deltaX, (float) deltaY);
    }

    @Override
    public boolean isCursorBeingMoved() {
        return cursorBeingMoved;
    }

    @Override
    public void setCursorBeingMoved(boolean cursorBeingMoved) {
        this.cursorBeingMoved = cursorBeingMoved;
    }

    @Override
    public void changeZoom(float scaleFactor, float fx, float fy) {
        if (canvasZoomer != null && getContext() instanceof RemoteCanvasActivity) {
            canvasZoomer.changeZoom((RemoteCanvasActivity) getContext(), scaleFactor, fx, fy);
        }
    }

    @Override
    public boolean isZoomerAbleToPan() {
        return canvasZoomer != null && canvasZoomer.isAbleToPan();
    }

    @Override
    public Handler getHandler() {
        return handler;
    }

    /**
     * Used to wait until getWidth and getHeight return sane values.
     */
    public void waitUntilInflated() {
        synchronized (this) {
            while (getWidth() == 0 || getHeight() == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, Log.getStackTraceString(e));
                }
            }
        }
    }

    /**
     * Used to detect when the view is inflated to a sane size other than 0x0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w > 0 && h > 0) {
            synchronized (this) {
                this.notify();
            }
        }
    }

    @Override
    public boolean isForegrounded() {
        return isForegrounded;
    }

    public void setForegrounded(boolean foregrounded) {
        isForegrounded = foregrounded;
    }
}
