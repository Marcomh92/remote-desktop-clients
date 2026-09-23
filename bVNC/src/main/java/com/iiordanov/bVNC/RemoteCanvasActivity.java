/**
 * Copyright (C) 2012-2017 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
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
// CanvasView is the Activity for showing VNC Desktop.
//
package com.iiordanov.bVNC;

import static com.iiordanov.bVNC.Constants.EXTRA_KEYS_TOUR_SHOWN;
import static com.iiordanov.bVNC.dialogs.MetaKeyDialog.tryPopulateKeysInListWhereFieldMatchesValue;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.viewpager.widget.ViewPager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;
import com.iiordanov.bVNC.dialogs.EnterTextDialog;
import com.iiordanov.bVNC.dialogs.MetaKeyDialog;
import com.iiordanov.bVNC.extrakeys.ExtraKeysView;
import com.iiordanov.bVNC.extrakeys.ExtraKeysPagerAdapter;
import com.iiordanov.bVNC.extrakeys.RdpModifierRowHandler;
import com.iiordanov.bVNC.input.IgnoringMouseInputListener;
import com.iiordanov.bVNC.input.InputAreaState;
import com.iiordanov.bVNC.input.MetaKeyBean;
import com.iiordanov.bVNC.input.Panner;
import com.iiordanov.bVNC.input.RemoteCanvasHandler;
import com.iiordanov.bVNC.input.RemoteClientsInputListener;
import com.iiordanov.bVNC.input.RemoteKeyboard;
import com.iiordanov.bVNC.input.ScrollWheelButton;
import com.iiordanov.bVNC.input.TouchInputHandler;
import com.iiordanov.bVNC.input.TouchInputHandlerDirectDragPan;
import com.iiordanov.bVNC.input.TouchInputHandlerDirectSwipePan;
import com.iiordanov.bVNC.input.TouchInputHandlerSingleHanded;
import com.iiordanov.bVNC.input.TouchInputHandlerTouchpad;
import com.iiordanov.bVNC.input.TouchInputDelegate;
import com.iiordanov.bVNC.protocol.GettingConnectionSettingsException;
import com.iiordanov.bVNC.protocol.RemoteConnection;
import com.iiordanov.bVNC.protocol.RemoteConnectionFactory;
import com.iiordanov.util.SamsungDexUtils;
import com.undatech.opaque.Connection;
import com.undatech.opaque.MessageDialogs;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.dialogs.SelectTextElementFragment;
import com.undatech.opaque.util.GeneralUtils;
import com.undatech.opaque.util.OnTouchViewMover;
import com.undatech.opaque.util.RemoteToolbar;
import com.undatech.remoteClientUi.R;


import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

@SuppressLint("ClickableViewAccessibility")
public class RemoteCanvasActivity extends AppCompatActivity implements
        SelectTextElementFragment.OnFragmentDismissedListener, TouchInputDelegate {

    public static final int[] inputModeIds = {R.id.itemInputTouchpad,
            R.id.itemInputTouchPanZoomMouse,
            R.id.itemInputDragPanZoomMouse,
            R.id.itemInputSingleHanded};
    public static final Map<Integer, String> inputModeMap;
    private final static String TAG = "RemoteCanvasActivity";
    private static final int[] scalingModeIds = {R.id.itemZoomable, R.id.itemFitToScreen,
            R.id.itemOneToOne};

    static {
        inputModeMap = Map.of(
                R.id.itemInputTouchpad, TouchInputHandlerTouchpad.ID,
                R.id.itemInputDragPanZoomMouse, TouchInputHandlerDirectDragPan.ID,
                R.id.itemInputTouchPanZoomMouse, TouchInputHandlerDirectSwipePan.ID,
                R.id.itemInputSingleHanded, TouchInputHandlerSingleHanded.ID
        );
    }

    TouchInputHandler touchInputHandler;
    Panner panner;
    Handler handler;
    ViewPager extraKeysToolbar;
    LinearLayout extraKeysPageIndicator;
    View[] pageIndicatorDots;
    ExtraKeysPagerAdapter extraKeysPagerAdapter;
    boolean extraKeysHidden = false;
    volatile boolean softKeyboardUp;
    RemoteToolbar toolbar;
    View rootView;
    KeyboardIconShower keyboardIconShower = new KeyboardIconShower();
    private Vibrator myVibrator;
    private FrameLayout canvasLayout;
    private RemoteCanvas canvas;
    private RemoteConnection remoteConnection;
    private MenuItem[] inputModeMenuItems;
    private MenuItem[] scalingModeMenuItems;
    private TouchInputHandler[] inputModeHandlers;
    private Connection connection;
    public RemoteClientsInputListener inputListener;
    private ImageButton keyboardIconForAndroidTv;
    float keyboardIconForAndroidTvX = Float.MAX_VALUE;
    IgnoringMouseInputListener ignoringMouseInputListener = new IgnoringMouseInputListener();
    private ImageButton keyboardToggleButton;
    private ImageButton toolbarToggleButton;
    private boolean toolbarExpanded = false;
    private FrameLayout rdpInputAreaContainer;
    private RdpModifierRowHandler rdpModifierRowHandler;
    private InputAreaState inputAreaState = InputAreaState.NONE;
    private static final long DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L;
    private long lastBackPressForDisconnect = 0;
    private int lastImeHeightPx = 0; // RDP IME inset height in px; 0 = IME closed
    // Snapshot of the launching Intent's extras, captured in onCreate so the
    // foreground-session notification's content intent can re-launch this same
    // connection when the user taps the notification.
    private Bundle sessionLaunchExtras;
    // Runtime POST_NOTIFICATIONS prompt for Android 13+. The foreground service
    // still runs even if the user denies this permission (Android keeps it
    // active without the notification UI); on grant we re-issue startForeground
    // so the notification posts.
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            // Permission just granted; restart the service so the
                            // notification now posts. Bypass startRemoteSessionService
                            // to avoid re-entering the permission check.
                            String nickname = "";
                            String address = "";
                            if (connection != null) {
                                nickname = connection.getNickname() != null
                                        ? connection.getNickname() : "";
                                address = connection.getAddress() != null
                                        ? connection.getAddress() : "";
                            }
                            RemoteSessionService.start(this, sessionLaunchExtras,
                                    nickname, address);
                        }
                    });
    int xPointerOffset = 0;
    int yPointerOffset = 0;

    /**
     * This runnable enables immersive mode.
     */
    private final Runnable immersiveEnabler = new Runnable() {
        public void run() {
            try {
                if (Utils.querySharedPreferenceBoolean(RemoteCanvasActivity.this,
                        Constants.disableImmersiveTag, false)) {
                    Log.d(TAG, "immersiveEnabler - exiting because immersive mode disabled");
                    return;
                }

                Log.d(TAG, "immersiveEnabler - enabling immersive mode");
                if (Constants.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    canvas.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            } catch (Exception e) {
                Log.d(TAG, "Ignored Exception while enabling immersive mode");
            }
        }
    };
    /**
     * This runnable disables immersive mode.
     */
    private final Runnable immersiveDisabler = new Runnable() {
        public void run() {
            try {
                if (!Utils.querySharedPreferenceBoolean(RemoteCanvasActivity.this,
                        Constants.disableImmersiveTag, false)) {
                    Log.d(TAG, "immersiveDisabler - exiting because immersive mode enabled");
                    return;
                }

                Log.d(TAG, "immersiveDisabler - disabling immersive mode");
                if (Constants.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    canvasLayout.setFitsSystemWindows(true);
                    canvas.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    ViewCompat.requestApplyInsets(canvasLayout);
                }
            } catch (Exception e) {
                Log.d(TAG, "Ignored Exception while disabling immersive mode");
            }
        }
    };

    /**
     * This runnable fixes things up after a rotation.
     */
    private final Runnable rotationCorrector = () -> {
        try {
            correctAfterRotation();
        } catch (Exception e) {
            Log.d(TAG, "Ignoring Exception on rotationCorrector run.");
        }
    };

    private void correctAfterRotation() throws Exception {
        Log.d(TAG, "correctAfterRotation");
        canvas.waitUntilInflated();
        // Its quite common to see NullPointerExceptions here when this function is called
        // at the point of disconnection. Hence, we catch and ignore the error.
        float oldScale = canvas.canvasZoomer.getZoomFactor();
        int x = canvas.absoluteXPosition;
        int y = canvas.absoluteYPosition;
        canvas.canvasZoomer.setScaleTypeForActivity(this);
        float newScale = canvas.canvasZoomer.getZoomFactor();
        canvas.canvasZoomer.changeZoom(this, oldScale / newScale, 0, 0);
        newScale = canvas.canvasZoomer.getZoomFactor();
        if (newScale <= oldScale &&
                canvas.canvasZoomer.getScaleType() != ImageView.ScaleType.FIT_CENTER) {
            canvas.absoluteXPosition = x;
            canvas.absoluteYPosition = y;
            canvas.resetScroll();
        }
        remoteConnection.correctAfterRotation();
    }

    /**
     * Controls sticky immersive mode (enables/disables as configured).
     */
    private void controlImmersive() {
        if (handler != null) {
            handler.removeCallbacks(immersiveEnabler);
            handler.postDelayed(immersiveEnabler, 200);
            handler.removeCallbacks(immersiveDisabler);
            handler.postDelayed(immersiveDisabler, 200);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
        canvas.setForegrounded(hasFocus);
        if (hasFocus) {
            controlImmersive();
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    @Override
    public void onCreate(Bundle icicle) {
        Log.d(TAG, "OnCreate called");
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // RDP only: make the window resize when the IME comes up so the canvas shrinks and
        // getWindowVisibleDisplayFrame().bottom drops. Otherwise the relayout heuristic never
        // detects the IME and the RDP modifier row ends up translated off-screen.
        if (Utils.isRdp(this)) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        Utils.showMenu(this);

        setContentView(R.layout.canvas);

        canvasLayout = findViewById(R.id.canvasLayout);
        canvas = findViewById(R.id.canvas);
        if (canvas != null) {
            canvas.setEdgeThresholdDp(getEdgeThresholdDpPref());
        }
        keyboardIconForAndroidTv = findViewById(R.id.keyboardIconForAndroidTv);
        // Both floating buttons are visible on every flavor: the keyboard FAB
        // toggles the IME (state-machine on RDP, plain show/hide elsewhere),
        // and the toolbar-toggle FAB expands the action-bar toolbar.
        keyboardToggleButton = findViewById(R.id.keyboardToggleButton);
        toolbarToggleButton = findViewById(R.id.toolbarToggleButton);
        if (Utils.isRdp(this)) {
            rdpInputAreaContainer = findViewById(R.id.rdpInputAreaContainer);

            // RDP-only: drive input-area state and viewport recompute from the IME inset.
            // The 19% relayout heuristic cannot see the IME on devices where the window
            // does not resize when the soft keyboard comes up; the insets listener covers
            // that model and is idempotent under the resize model.
            ViewCompat.setOnApplyWindowInsetsListener(canvasLayout, (v, insets) -> {
                if (canvasLayout.getHeight() <= 0) {
                    return insets;
                }
                int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                // Bogus-IME guard: clamp absurd inset values to a closed-IME sentinel so
                // we never trigger a phantom KEYBOARD state on first-frame events or
                // transient insets during configuration changes. Use the physical full
                // height (not canvasLayout.getHeight()) as the denominator: when the
                // window DOES resize, the canvas shrank and a real IME height would
                // otherwise be clamped to zero and the modifier row would vanish.
                int imeMaxHeight = canvas.getRdpFullViewHeight() > 0 ? canvas.getRdpFullViewHeight() : canvasLayout.getHeight() + lastImeHeightPx;
                if (ime > imeMaxHeight / 2) {
                    ime = 0;
                }
                lastImeHeightPx = ime;
                if (lastImeHeightPx > 0 && inputAreaState == InputAreaState.NONE) {
                    setInputAreaState(InputAreaState.KEYBOARD);
                } else if (lastImeHeightPx == 0 && inputAreaState == InputAreaState.KEYBOARD) {
                    setInputAreaState(InputAreaState.NONE);
                }
                recomputeRdpViewport();
                return insets;
            });
            ViewCompat.requestApplyInsets(canvasLayout);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.setDefaultFocusHighlightEnabled(false);
        }
        if (Build.VERSION.SDK_INT >= 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        myVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener
                (visibility -> {
                    try {
                        remoteConnection.correctAfterRotation();
                    } catch (Exception e) {
                        Log.d(TAG, "Ignoring Exception during SystemUiVisibilityChangeListener execution");
                    }
                });

        Runnable setModes = () -> {
            try {
                setModes();
            } catch (NullPointerException e) {
                Log.d(TAG, "Ignored NullPointerException while running setModes: " + Log.getStackTraceString(e));
            }
        };
        Runnable hideKeyboardAndExtraKeys = () -> {
            try {
                hideKeyboardAndExtraKeys();
            } catch (NullPointerException e) {
                Log.d(TAG, "Ignoring NullPointerException while running hideKeyboardAndExtraKeys.");
            }
        };

        setApplicationSpecificSettings();

        try {
            connection = AbstractConnectionBean.getRemoteConnectionSettings(getIntent(), this, isMasterPasswordEnabled());
        } catch (GettingConnectionSettingsException e) {
            Utils.showFatalErrorMessage(this, getResources().getString(e.getErrorStringId()));
            return;
        }
        remoteConnection = new RemoteConnectionFactory(this, connection, canvas, hideKeyboardAndExtraKeys).build();

        if (connection != null && connection.isReadyForConnection()) {
            // Snapshot the launching Intent's extras so the foreground-session notification's
            // content intent can re-launch this same connection when tapped.
            sessionLaunchExtras = getIntent() != null ? getIntent().getExtras() : null;
            handler = new RemoteCanvasHandler(this, canvas, remoteConnection, connection, setModes);
            Log.d(TAG, "OnCreate - initializing session with a REINIT_SESSION message");
            handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
            startRemoteSessionService();
            continueConnecting();
        } else {
            showConnectionScreenOrExitIfNotReadyForConnecting(connection);
        }

        Log.d(TAG, "OnCreate complete");
    }

    private void setApplicationSpecificSettings() {
        if (Utils.isOpaque(this)) {
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
        } else {
            if (Utils.querySharedPreferenceBoolean(this, Constants.keepScreenOnTag))
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            if (Utils.querySharedPreferenceBoolean(this, Constants.forceLandscapeTag))
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private void showConnectionScreenOrExitIfNotReadyForConnecting(Connection connection) {
        // we need to save the connection to display the loading screen, so otherwise we should exit
        if (connection == null || !connection.isReadyForConnection()) {
            Utils.showMessage(canvas, getString(R.string.error_uri_noinfo_nosave), Snackbar.LENGTH_LONG);
            if (connection != null && connection.isReadyToBeSaved()) {
                Log.i(TAG, "Exiting - Insufficient information to connect and connection was not saved.");
            } else {
                Log.i(TAG, "Insufficient information to connect, showing connection dialog.");
                // launch appropriate activity
                Class<?> cls = bVNC.class;
                if (Utils.isRdp(this)) {
                    cls = aRDP.class;
                } else if (Utils.isSpice(this)) {
                    cls = aSPICE.class;
                }
                Intent Intent = new Intent(this, cls);
                startActivity(Intent);
            }
            Utils.justFinish(this);
        }
    }

    @SuppressLint("RtlHardcoded")
    public void continueConnecting() {
        Log.d(TAG, "continueConnecting");
        // Initialize extra keys view and pager.
        initializeExtraKeysView();

        canvas.setFocusableInTouchMode(true);
        canvas.setDrawingCacheEnabled(false);

        // This code detects when the soft keyboard is up and sets an appropriate visibleHeight in vncCanvas.
        // When the keyboard is gone, it resets visibleHeight and pans zero distance to prevent us from being
        // below the desktop image (if we scrolled all the way down when the keyboard was up).
        // TODO: Move this into a separate thread, and post the visibility changes to the handler.
        //       to avoid occupying the UI thread with this.
        rootView = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> relayoutViews(rootView));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);

        if (Utils.querySharedPreferenceBoolean(this, Constants.leftHandedModeTag)) {
            params.gravity = Gravity.CENTER | Gravity.LEFT;
        } else {
            params.gravity = Gravity.CENTER | Gravity.RIGHT;
        }

        panner = new Panner(this, handler);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbar.setOnGenericMotionListener(ignoringMouseInputListener);
        toolbar.getBackground().setAlpha(64);
        toolbar.setLayoutParams(params);
        setSupportActionBar(toolbar);

        // Mirror the toolbar's leftHandedModeTag gravity on the toggle FAB so
        // the FAB starts on the same side as the toolbar it owns. After this,
        // the user can still drag either element independently.
        if (toolbarToggleButton != null) {
            FrameLayout.LayoutParams fabParams =
                (FrameLayout.LayoutParams) toolbarToggleButton.getLayoutParams();
            fabParams.gravity = params.gravity;
            toolbarToggleButton.setLayoutParams(fabParams);
        }
    }

    void relayoutViews(View rootView) {
        Log.d(TAG, "onGlobalLayout: start");
        if (canvas == null) {
            Log.d(TAG, "onGlobalLayout: canvas null, returning");
            return;
        }

        Rect r = new Rect();

        rootView.getWindowVisibleDisplayFrame(r);
        Log.d(TAG, "onGlobalLayout: getWindowVisibleDisplayFrame: " + r);

        // To avoid setting the visible height to a wrong value after an screen unlock event
        // (when r.bottom holds the width of the screen rather than the height due to a rotation)
        // we make sure r.top is zero (i.e. there is no notification bar and we are in full-screen mode)
        // It's a bit of a hack.
        // One additional situation that needed handling was that devices with notches / cutouts don't
        // ever have r.top equal to zero. so a special case for them.
        Rect re = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(re);
        if (r.top == 0 || re.top > 0) {
            if (canvas.getDrawable() != null) {
                int extraKeysHeight = (extraKeysToolbar != null) ? extraKeysToolbar.getHeight() : 0;
                // For RDP, the new @+id/rdpInputAreaContainer replaces the legacy
                // extra-keys toolbar as the bottom UI surface that the canvas must
                // shrink around. Only subtract its height when it has been measured
                // (container.getHeight() == 0 when visibility was just toggled in
                // this same pass; the next pass will pick it up).
                int bottomShrinkHeight = extraKeysHeight;
                if (Utils.isRdp(this) && rdpInputAreaContainer != null
                        && rdpInputAreaContainer.getVisibility() == View.VISIBLE) {
                    int rdpContainerHeight = rdpInputAreaContainer.getHeight();
                    bottomShrinkHeight = (rdpContainerHeight > 0) ? rdpContainerHeight : 0;
                }
                int usableHeight = r.bottom - re.top - bottomShrinkHeight;
                Log.d(TAG, "onGlobalLayout: Setting VisibleDesktopHeight to: " + usableHeight +
                        ", extraKeysHeight: " + extraKeysHeight +
                        ", rdpInputAreaHeight: " + bottomShrinkHeight);
                // RDP viewport math is owned by recomputeRdpViewport() (driven by the IME
                // insets listener and by setInputAreaState). On the no-window-resize model
                // the legacy r.bottom here stays at full-screen height when the IME is
                // open, which would write a too-large visibleDesktopHeight and prevent the
                // cover floor from overhanging the IME. Skip the write for RDP.
                if (!Utils.isRdp(this)) {
                    canvas.setVisibleDesktopHeight(usableHeight);
                    canvas.relativePan(0, 0);
                }
            } else {
                Log.d(TAG, "onGlobalLayout: canvas.myDrawable is null");
            }
        } else {
            Log.d(TAG, "onGlobalLayout: Found r.top to be non-zero");
        }

        // Enable/show the toolbar if the keyboard is gone, and disable/hide otherwise.
        // We detect the keyboard if more than 19% of the screen is covered.
        // Use the visible display frame of the decor view to compute notch dimensions.
        int rootViewHeight = rootView.getHeight();

        if (keyboardIconForAndroidTvX == Float.MAX_VALUE) {
            keyboardIconForAndroidTvX = keyboardIconForAndroidTv.getX();
        }

        int[] rootViewScreenPos = new int[2];
        rootView.getLocationOnScreen(rootViewScreenPos);
        int rootViewTopInScreen = rootViewScreenPos[1];

        int layoutKeysBottom = extraKeysToolbar.getBottom();
        int toolbarBottom = toolbar.getBottom();
        int rootViewBottom = extraKeysToolbar.getRootView().getBottom();
        int diffLayoutKeysPosition = r.bottom - rootViewTopInScreen - layoutKeysBottom;
        int diffToolbarPosition = r.bottom - re.top - toolbarBottom - r.bottom / 2;
        int standardToolbarPositionX = r.right - toolbar.getWidth();
        int standardToolbarPositionY = r.bottom - re.top - toolbar.getHeight() - r.bottom / 2;
        Log.d(TAG, "onGlobalLayout: before: r.bottom: " + r.bottom +
                " rootViewHeight: " + rootViewHeight + " re.top: " + re.top + " re.bottom: " + re.bottom +
                " layoutKeysBottom: " + layoutKeysBottom + " rootViewBottom: " + rootViewBottom + " toolbarBottom: " + toolbarBottom +
                " diffLayoutKeysPosition: " + diffLayoutKeysPosition + " diffToolbarPosition: " + diffToolbarPosition);

        if (r.bottom > rootViewHeight * 0.81) {
            Log.d(TAG, "onGlobalLayout: Less than 19% of screen is covered");
            String direction = "down";
            // Soft Kbd gone, shift the extra keys toolbar down.
            if (extraKeysToolbar != null) {
                shiftToolbar(r, diffLayoutKeysPosition, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY, direction);
                if (softKeyboardUp) {
                    Log.d(TAG, "onGlobalLayout: softKeyboardUp was true, but keyboard is now hidden. Hiding on-screen buttons");
                    setExtraKeysVisibility(View.GONE, false);
                    canvas.invalidate();
                }
            }
            // RDP state-machine: only collapse the input area if we're transitioning
            // out of KEYBOARD. EXTRA (the "extra keys" grid) must survive an IME hide
            // so toggling the IME back via the row's 123 button keeps the grid state.
            if (Utils.isRdp(this) && inputAreaState == InputAreaState.KEYBOARD) {
                setInputAreaState(InputAreaState.NONE);
            }
            softKeyboardUp = false;
        } else {
            Log.d(TAG, "onGlobalLayout: More than 19% of screen is covered");
            softKeyboardUp = true;
            String direction = "up";
            //  Soft Kbd up, shift the extra keys toolbar up.
            if (extraKeysToolbar != null) {
                shiftToolbar(r, diffLayoutKeysPosition, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY, direction);
                if (extraKeysHidden) {
                    Log.d(TAG, "onGlobalLayout: on-screen buttons should be hidden");
                    setExtraKeysVisibility(View.GONE, false);
                } else {
                    Log.d(TAG, "onGlobalLayout: on-screen buttons should be showing");
                    // RDP keeps its own on-screen surface (@+id/rdpInputAreaContainer);
                    // the legacy 3-page extra-keys pager stays hidden so it doesn't
                    // double up above the IME.
                    if (!Utils.isRdp(this)) {
                        setExtraKeysVisibility(View.VISIBLE, true);
                    }
                }
                canvas.invalidate();
            }
            // RDP state-machine: when the IME comes up, collapse any open EXTRA grid
            // back to KEYBOARD ("123 replaces the software keyboard" — they are
            // mutually exclusive on screen). NONE also snaps to KEYBOARD so the
            // modifier row appears above the IME.
            if (Utils.isRdp(this) &&
                    (inputAreaState == InputAreaState.NONE
                            || inputAreaState == InputAreaState.EXTRA)) {
                setInputAreaState(InputAreaState.KEYBOARD);
            }
        }
        if (extraKeysToolbar != null) {
            layoutKeysBottom = extraKeysToolbar.getBottom();
            rootViewBottom = extraKeysToolbar.getRootView().getBottom();
        }
        Log.d(TAG, "onGlobalLayout: after: r.bottom: " + r.bottom +
                " rootViewHeight: " + rootViewHeight + " re.top: " + re.top + " re.bottom: " + re.bottom +
                " layoutKeysBottom: " + layoutKeysBottom + " rootViewBottom: " + rootViewBottom + " toolbarBottom: " + toolbarBottom +
                " diffLayoutKeysPosition: " + diffLayoutKeysPosition + " diffToolbarPosition: " + diffToolbarPosition);

        // RDP viewport math is owned by recomputeRdpViewport(). On every onGlobalLayout
        // (rotation, resize, IME-driven layout passes) we re-apply it so the visible
        // desktop height and the rdpInputAreaContainer translation track the latest
        // canvas/IME state. No-op for VNC/SPICE/Opaque.
        if (Utils.isRdp(this)) {
            recomputeRdpViewport();
        }

        recalculateYPointerOffset();
    }

    private void recalculateYPointerOffset() {
        boolean disabled = Utils.querySharedPreferenceBoolean(getApplicationContext(), "disablePointerOffsetCalculation");
        if (disabled) {
            Log.d(TAG, "recalculateTitleBarHeight - pointer offset calculation disabled");
            xPointerOffset = 0;
            yPointerOffset = 0;
        } else {
            Rect frame = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            int[] location = new int[2];
            getWindow().getDecorView().getLocationOnScreen(location);

            if (isWidthFull()) {
                Log.d(TAG, "recalculateTitleBarHeight - app is occupying full width of screen, skipping x offset calculation");
                xPointerOffset = 0;
            } else {
                int displayLeft = location[0];
                xPointerOffset = frame.left - displayLeft;
                Log.d(TAG, "recalculateTitleBarHeight - frame.left - displayLeft = xPointerOffset: " +
                        frame.left + " - " + displayLeft + " = " + xPointerOffset);
            }

            if (isHeightFull()) {
                Log.d(TAG, "recalculateTitleBarHeight - app is occupying full height of screen, skipping y offset calculation");
                yPointerOffset = 0;
            } else {
                int displayTop = location[1];
                yPointerOffset = frame.top - displayTop;
                Log.d(TAG, "recalculateTitleBarHeight - frame.top - displayTop = yPointerOffset: " +
                        frame.top + " - " + displayTop + " = " + yPointerOffset);
            }
        }
    }

    public boolean isWidthFull() {
        int w = canvas.getWidth();
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int dW = displayMetrics.widthPixels;
        Log.d(TAG, "isWidthFull - view width: " + w + ", display width: " + dW);
        return w == dW;
    }

    public boolean isHeightFull() {
        int h = canvas.getHeight();
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int dH = displayMetrics.heightPixels;
        Log.d(TAG, "isHeightFull - view height: " + h + ", display height: " + dH);
        return h == dH;
    }

    private void shiftToolbar(Rect r, int diffLayoutKeysPosition, int diffToolbarPosition, int standardToolbarPositionX, int standardToolbarPositionY, String direction) {
        Log.d(TAG, String.format("onGlobalLayout: shifting on-screen buttons %s by: %d", direction, diffLayoutKeysPosition));
        // Use setTranslationY instead of offsetTopAndBottom: unlike offsetTopAndBottom, setTranslationY
        // is a render-layer transform that is not reset by layout passes, and does not change getBottom(),
        // so every onGlobalLayout call sets the same absolute translation regardless of intermediate layouts.
        extraKeysToolbar.setTranslationY(diffLayoutKeysPosition);
        if (extraKeysPageIndicator != null)
            extraKeysPageIndicator.setTranslationY(diffLayoutKeysPosition);
        offsetOrRestoreSavedToolbarPosition(r, diffToolbarPosition, standardToolbarPositionX, standardToolbarPositionY);
    }

    // The toolbar's own Y is no longer shifted when the IME comes up: the toolbar
    // is hidden by default and only shown when the @+id/toolbarToggleButton FAB
    // is tapped. We still need a hook for relayoutViews(), however, so use the
    // call site to re-anchor the FAB to its saved drag position.
    private void offsetOrRestoreSavedToolbarPosition(Rect r, int diffToolbarPosition, int standardPositionX, int standardPositionY) {
        restoreToolbarTogglePosition();
    }

    public void extraKeysToggle(MenuItem m) {
        if (extraKeysToolbar.getVisibility() == View.VISIBLE) {
            extraKeysHidden = true;
            setExtraKeysVisibility(View.GONE, false);
        } else {
            extraKeysHidden = false;
            setExtraKeysVisibility(View.VISIBLE, true);
        }
        setKeyStowDrawableAndVisibility(m);
        relayoutViews(rootView);
    }

    private void setKeyStowDrawableAndVisibility(MenuItem m) {
        if (m == null) {
            return;
        }
        Drawable replacer;
        m.setVisible(connection.getExtraKeysToggleType() != Constants.EXTRA_KEYS_OFF);
        if (extraKeysToolbar.getVisibility() == View.GONE)
            replacer = ResourcesCompat.getDrawable(getResources(), R.drawable.showkeys, null);
        else
            replacer = ResourcesCompat.getDrawable(getResources(), R.drawable.hidekeys, null);

        m.setIcon(replacer);
    }

    public void sendShortVibration() {
        if (myVibrator != null) {
            myVibrator.vibrate(Constants.SHORT_VIBRATION);
        } else {
            Log.i(TAG, "Device cannot vibrate, not sending vibration");
        }
    }

    @Override
    public <T extends View> T findViewById(int id) {
        return super.findViewById(id);
    }

    @Override
    public boolean getUseDpadAsArrows() {
        return connection != null && connection.getUseDpadAsArrows();
    }

    @Override
    public boolean getRotateDpad() {
        return connection != null && connection.getRotateDpad();
    }

    @Override
    public int getxPointerOffset() {
        return xPointerOffset;
    }

    @Override
    public int getyPointerOffset() {
        return yPointerOffset;
    }

    @Override
    public int getDoubleTapSlopDp() {
        return Utils.querySharedPreferencesInt(this, Constants.doubleTapSlopDp, Constants.DEFAULT_DOUBLE_TAP_SLOP_DP);
    }

    @Override
    public int getDoubleTapTimeoutMs() {
        return Utils.querySharedPreferencesInt(this, Constants.doubleTapTimeoutMs, Constants.DEFAULT_DOUBLE_TAP_TIMEOUT_MS);
    }

    private void initializeExtraKeysView() {
        extraKeysToolbar = findViewById(R.id.extraKeysToolbar);
        float extraKeysAlpha = Color.alpha(ContextCompat.getColor(this, R.color.extra_keys_background)) / 255f;
        extraKeysToolbar.setAlpha(extraKeysAlpha);

        extraKeysPagerAdapter = new ExtraKeysPagerAdapter(this, new ExtraKeysPagerAdapter.Callbacks() {
            @Override
            public void onSendText(String text) {
                if (inputListener != null) {
                    inputListener.sendText(text);
                }
            }
            @Override
            public RemoteKeyboard getKeyboard() {
                return remoteConnection != null ? remoteConnection.getKeyboard() : null;
            }
        });
        extraKeysToolbar.setAdapter(extraKeysPagerAdapter);

        extraKeysToolbar.setCurrentItem(1, false);

        extraKeysPageIndicator = findViewById(R.id.extraKeysPageIndicator);
        extraKeysPageIndicator.setAlpha(extraKeysAlpha);
        pageIndicatorDots = new View[]{
            findViewById(R.id.dotPage0),
            findViewById(R.id.dotPage1),
            findViewById(R.id.dotPage2)
        };
        updatePageDots(1);

        extraKeysToolbar.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageDots(position);
                if (position == 0) {
                    if (extraKeysPagerAdapter != null && extraKeysPagerAdapter.getSendTextPanel() != null)
                        extraKeysPagerAdapter.getSendTextPanel().requestFocusOnText();
                } else {
                    canvas.requestFocus();
                }
            }
        });
    }

    /**
     * Shows the extra-keys toolbar (if hidden) and navigates to the Send Text panel with an
     * animated slide from the modifier-keys page, hinting that a right swipe reaches it.
     */
    private void openSendTextPanel() {
        extraKeysHidden = false;
        setExtraKeysVisibility(View.VISIBLE, true);
        // Snap to the modifier-keys page (1) without animation so the subsequent animated
        // slide always travels one page to the right, demonstrating the swipe gesture.
        extraKeysToolbar.setCurrentItem(1, false);
        extraKeysToolbar.post(() -> extraKeysToolbar.setCurrentItem(0, true));
    }

    /** Updates the page indicator dots so the active page's dot is bright and the others are dim. */
    private void updatePageDots(int activePage) {
        if (pageIndicatorDots == null) return;
        for (int i = 0; i < pageIndicatorDots.length; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(i == activePage ? 0xFFFFFFFF : 0x55FFFFFF);
            pageIndicatorDots[i].setBackground(dot);
        }
    }

    /**
     * On the very first launch, animates through all three pages so the user discovers the
     * swipe gestures. Runs only once, tracked in SharedPreferences.
     */
    private void maybeRunExtraKeysTour() {
        if (Utils.querySharedPreferenceBoolean(this, EXTRA_KEYS_TOUR_SHOWN, false)) {
            return;
        }
        Utils.setSharedPreferenceBoolean(this, EXTRA_KEYS_TOUR_SHOWN, true);
        // Sweep: modifier keys → F-keys → modifier keys → send text → modifier keys
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(2, true), 1000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(1, true), 2000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(0, true), 3000);
        extraKeysToolbar.postDelayed(() -> extraKeysToolbar.setCurrentItem(1, true), 4000);
    }

    /**
     * Resets the state of the on-screen special buttons.
     */
    private int resetOnScreenKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return keyCode;
        }
        ExtraKeysView extraKeysView = extraKeysPagerAdapter != null ? extraKeysPagerAdapter.getExtraKeysView() : null;
        if (extraKeysView != null) {
            extraKeysView.resetSpecialButtons();
        }
        return keyCode;
    }

    /**
     * Sets the visibility of the extra keys appropriately.
     */
    private void setExtraKeysVisibility(int visibility, boolean forceVisible) {
        Configuration config = getResources().getConfiguration();

        boolean makeVisible = forceVisible;
        if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
            makeVisible = true;

        if (!extraKeysHidden && makeVisible &&
                connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON) {
            extraKeysToolbar.setVisibility(View.VISIBLE);
            if (extraKeysPageIndicator != null)
                extraKeysPageIndicator.setVisibility(View.VISIBLE);
            extraKeysToolbar.invalidate();
            maybeRunExtraKeysTour();
            return;
        }

        if (visibility == View.GONE) {
            extraKeysToolbar.setVisibility(View.GONE);
            if (extraKeysPageIndicator != null)
                extraKeysPageIndicator.setVisibility(View.GONE);
            extraKeysToolbar.invalidate();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause called.");
        lastBackPressForDisconnect = 0;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(canvas.getWindowToken(), 0);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onPause");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called.");
        try {
            canvas.postInvalidateDelayed(600);
            if (canvas != null) {
                canvas.setEdgeThresholdDp(getEdgeThresholdDpPref());
            }
            if (remoteConnection != null && remoteConnection.getPointer() != null) {
                remoteConnection.getPointer().setAccelerationStrength(getMouseAccelerationStrength());
            }
            if (touchInputHandler != null && touchInputHandler instanceof TouchInputHandlerTouchpad) {
                ((TouchInputHandlerTouchpad) touchInputHandler).setFlingDamp(getFlingResistanceDamp());
                ((TouchInputHandlerTouchpad) touchInputHandler).setPointerAccel(
                        getRdpPointerAccelEnabled(), getRdpPointerAccelLowGain(), getRdpPointerAccelHighGain());
                ((TouchInputHandlerTouchpad) touchInputHandler).setPointerPreciseTracking(getRdpPreciseFingerTracking());
            }
            // Push the relaxed-slop double-tap prefs so a mid-session slider
            // change in Settings takes effect without reopening the connection
            // (matches the setFlingDamp pattern above).
            if (touchInputHandler != null) {
                int slopPx = (int) (getDoubleTapSlopDp() * canvas.getDisplayDensity() + 0.5f);
                touchInputHandler.setDoubleTapSlopPx(slopPx);
                touchInputHandler.setDoubleTapTimeoutMs(getDoubleTapTimeoutMs());
            }
            // Re-apply runtime row sizing so pref changes made via the
            // Settings screen mid-session take effect without reconnecting.
            // Same getter pattern as the three round-4 values above; the
            // handler / view setters are idempotent.
            if (rdpModifierRowHandler != null) {
                rdpModifierRowHandler.applyModifierRowSizing(
                        getRdpModifierKeyHeightDp(),
                        getRdpModifierKeySizeDp());
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onResume");
        }
    }

    /**
     * Set modes on start to match what is specified in the ConnectionBean;
     * color mode (already done) scaling, input mode
     */
    void setModes() {
        Log.d(TAG, "setModes");
        String inputMode = connection.getInputMode();
        // Safety net for legacy rows whose INPUTMODE column was never populated.
        // Newly-created RDP beans already receive TOUCHPAD_MODE from
        // ConnectionBean.getDefaultInputMode, so this branch usually hits only
        // rows imported from older installs or copy-from-template flows that
        // bypassed the default. We only fall back to the touchpad for RDP when
        // the stored value is null/empty — never for an explicit TOUCH_ZOOM_MODE
        // (the previous heuristic overrode users who had picked it deliberately).
        if ((inputMode == null || inputMode.isEmpty()) && Utils.isRdp(this)) {
            inputMode = TouchInputHandlerTouchpad.ID;
        }
        setInputHandler(getInputHandlerByName(inputMode));
        AbstractScaling.getByScaleType(connection.getScaleMode()).setScaleTypeForActivity(this);
        initializeExtraKeysView();
        try {
            COLORMODEL cm = COLORMODEL.valueOf(connection.getColorModel());
            remoteConnection.setColorModel(cm);
        } catch (NullPointerException | IllegalArgumentException e) {
            Log.w(TAG, "Could not set color model");
        }
        canvas.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.setFocusedByDefault(true);
        }
        canvas.requestFocus();
        canvas.setDrawingCacheEnabled(false);

        SamsungDexUtils.INSTANCE.dexMetaKeyCapture(this);
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onCreateDialog(int)
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == R.layout.entertext) {
            return new EnterTextDialog(this);
        } else if (id == R.id.itemHelpInputMode) {
            return createHelpDialog();
        }

        // Default to meta key dialog
        return new MetaKeyDialog(this);
    }

    /**
     * Creates the help dialog for this activity.
     */
    private Dialog createHelpDialog() {
        AlertDialog.Builder adb = new AlertDialog.Builder(this)
                .setMessage(R.string.input_mode_help_text)
                .setPositiveButton(R.string.close,
                        (dialog, whichButton) -> {
                            // We don't have to do anything.
                        });
        Dialog d = adb.setView(new ListView(this)).create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        Window window = d.getWindow();
        if (window != null) {
            lp.copyFrom(window.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.show();
            window.setAttributes(lp);
        }
        return d;
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onPrepareDialog(int, android.app.Dialog)
     */
    @SuppressWarnings("deprecation")
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        if (dialog instanceof ConnectionSettable)
            ((ConnectionSettable) dialog).setConnection(connection);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        controlImmersive();
        try {
            setExtraKeysVisibility(View.GONE, false);
            handler.postDelayed(rotationCorrector, 300);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onConfigurationChanged");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart called.");
        try {
            canvas.postInvalidateDelayed(800);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onStart");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop called.");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i(TAG, "onRestart called.");
        try {
            canvas.postInvalidateDelayed(1000);
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during onRestart");

        }
    }

    @Override
    public void onPanelClosed(int featureId, @NonNull Menu menu) {
        super.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (menu != null) {
            Log.i(TAG, "Menu opened");
            updateScalingMenu();
            updateInputMenu();
        }
        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Make sure extra keys stow item is gone if extra keys are disabled and vice versa.
        setKeyStowDrawableAndVisibility(menu.findItem(R.id.extraKeysToggle));
        menu.findItem(R.id.itemColorMode).setVisible(remoteConnection.canUpdateColorModelConnected());
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "OnCreateOptionsMenu called");
        try {
            getMenuInflater().inflate(R.menu.canvasactivitymenu, menu);

            Menu inputMenu = menu.findItem(R.id.itemInputMode).getSubMenu();
            inputModeMenuItems = new MenuItem[inputModeIds.length];
            for (int i = 0; i < inputModeIds.length; i++) {
                if (inputMenu != null) {
                    inputModeMenuItems[i] = inputMenu.findItem(inputModeIds[i]);
                }
            }
            updateInputMenu();

            Menu scalingMenu = menu.findItem(R.id.itemScaling).getSubMenu();
            scalingModeMenuItems = new MenuItem[scalingModeIds.length];
            for (int i = 0; i < scalingModeIds.length; i++) {
                if (scalingMenu != null) {
                    scalingModeMenuItems[i] = scalingMenu.findItem(scalingModeIds[i]);
                }
            }
            updateScalingMenu();

            // Set the text of the Extra Keys menu item appropriately.
            // TODO: Implement for Opaque
            if (connection != null && connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON)
                menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_disable);
            else
                menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_enable);

            // Always-on keyboard FAB: draggable + click dispatches the toggle handler.
            // Inline drag-vs-tap listener (mirrors OnTouchViewMover's dX/dY offset +
            // animate().x/y().setDuration(0) pattern) but dispatches a click on
            // ACTION_UP only when the finger stayed within the scaled touch slop.
            // OnTouchViewMover returns true for ACTION_DOWN/MOVE which marks the
            // gesture consumed, so View.onTouchEvent never runs and performClick()
            // is never invoked — that's the click-bug this fix replaces. We also
            // drive the pressed drawable manually so the button gives immediate
            // touch feedback (View.onTouchEvent is bypassed).
            if (keyboardToggleButton != null && handler != null) {
                final int touchSlop = ViewConfiguration.get(keyboardToggleButton.getContext()).getScaledTouchSlop();
                final float[] fingerDownXY = new float[2];
                final float[] dx = new float[1];
                final float[] dy = new float[1];
                final boolean[] movedBeyondSlop = new boolean[1];
                keyboardToggleButton.setOnTouchListener((v, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            fingerDownXY[0] = event.getRawX();
                            fingerDownXY[1] = event.getRawY();
                            dx[0] = v.getX() - event.getRawX();
                            dy[0] = v.getY() - event.getRawY();
                            movedBeyondSlop[0] = false;
                            v.setPressed(true);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (!movedBeyondSlop[0]) {
                                float totalDx = event.getRawX() - fingerDownXY[0];
                                float totalDy = event.getRawY() - fingerDownXY[1];
                                if (Math.abs(totalDx) > touchSlop || Math.abs(totalDy) > touchSlop) {
                                    movedBeyondSlop[0] = true;
                                }
                            }
                            if (movedBeyondSlop[0]) {
                                v.animate().x(event.getRawX() + dx[0])
                                        .y(event.getRawY() + dy[0])
                                        .setDuration(0).start();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            v.setPressed(false);
                            if (!movedBeyondSlop[0]) {
                                v.performClick();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            return true;
                        default:
                            return false;
                    }
                });
                keyboardToggleButton.setOnClickListener(v -> onKeyboardToggleButtonClicked());
            }
            setupToolbarToggleButton();

            // Set up scroll wheel button
            MenuItem scrollWheelItem = menu.findItem(R.id.actionScrollWheel);
            if (scrollWheelItem != null) {
                ScrollWheelButton scrollWheelButton = new ScrollWheelButton(this);
                scrollWheelButton.setRemoteInput(remoteConnection);
                scrollWheelButton.setTouchInputDelegate(this);
                scrollWheelItem.setActionView(scrollWheelButton);
                Log.d(TAG, "ScrollWheelButton added to toolbar");
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "onCreateOptionsMenu - NullPointerException: " + Log.getStackTraceString(e));
        }
        Log.d(TAG, "OnCreateOptionsMenu complete");
        return true;
    }

    /**
     * Change the scaling mode sub-menu to reflect available scaling modes.
     */
    void updateScalingMenu() {
        try {
            for (MenuItem item : scalingModeMenuItems) {
                // If the entire framebuffer is NOT contained in the bitmap, fit-to-screen is meaningless.
                if (item.getItemId() == R.id.itemFitToScreen) {
                    item.setEnabled(canvas == null || canvas.myDrawable == null ||
                            (canvas.myDrawable.getBitmapHeight() == canvas.myDrawable.getFramebufferHeight() &&
                                    canvas.myDrawable.getBitmapWidth() == canvas.myDrawable.getFramebufferWidth()));
                } else {
                    item.setEnabled(true);
                }

                AbstractScaling scaling = AbstractScaling.getById(item.getItemId());
                if (scaling.scaleType == connection.getScaleMode()) {
                    item.setChecked(true);
                }
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during updateScalingMenu");
        }
    }

    /**
     * Change the input mode sub-menu to reflect change in scaling
     */
    void updateInputMenu() {
        try {
            for (MenuItem item : inputModeMenuItems) {
                item.setEnabled(canvas.canvasZoomer.isValidInputMode(item.getItemId()));
                if (getInputHandlerById(item.getItemId()) == touchInputHandler)
                    item.setChecked(true);
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "Ignoring NullPointerException during updateInputMenu");
        }
    }

    /**
     * If id represents an input handler, return that; otherwise return null
     */
    TouchInputHandler getInputHandlerById(int id) {
        myVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        if (inputModeHandlers == null) {
            inputModeHandlers = new TouchInputHandler[inputModeIds.length];
        }
        float scrollRate = getScrollRate();
        remoteConnection.getPointer().setSensitivity(getTouchpadSensitivityMultiplier());
        remoteConnection.getPointer().setAccelerationStrength(getMouseAccelerationStrength());
        for (int i = 0; i < inputModeIds.length; ++i) {
            if (inputModeIds[i] == id) {
                if (inputModeHandlers[i] == null) {
                    if (id == R.id.itemInputTouchPanZoomMouse) {
                        inputModeHandlers[i] = new TouchInputHandlerDirectSwipePan(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else if (id == R.id.itemInputDragPanZoomMouse) {
                        inputModeHandlers[i] = new TouchInputHandlerDirectDragPan(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else if (id == R.id.itemInputTouchpad) {
                        TouchInputHandlerTouchpad touchpad = new TouchInputHandlerTouchpad(this, canvas, remoteConnection, App.debugLog, scrollRate);
                        // RDP gating: enable Microsoft-RDP-style touchpad gestures
                        // (fling / long-press=right-click / double-tap-and-hold=drag)
                        // for this instance. Non-RDP touchpad instances stay legacy.
                        touchpad.setRdp(Utils.isRdp(this));
                        inputModeHandlers[i] = touchpad;
                    } else if (id == R.id.itemInputSingleHanded) {
                        inputModeHandlers[i] = new TouchInputHandlerSingleHanded(this, canvas, remoteConnection, App.debugLog, scrollRate);
                    } else {
                        throw new IllegalStateException("Unexpected value: " + id);
                    }
                }
                if (inputModeHandlers[i] instanceof TouchInputHandlerTouchpad) {
                    ((TouchInputHandlerTouchpad) inputModeHandlers[i]).setFlingDamp(getFlingResistanceDamp());
                    ((TouchInputHandlerTouchpad) inputModeHandlers[i]).setPointerAccel(
                            getRdpPointerAccelEnabled(), getRdpPointerAccelLowGain(), getRdpPointerAccelHighGain());
                    ((TouchInputHandlerTouchpad) inputModeHandlers[i]).setPointerPreciseTracking(getRdpPreciseFingerTracking());
                }
                // Same push as the onResume block above, but applied to every
                // pre-built input-mode handler so a switch between modes
                // doesn't carry stale defaults from ctor-time prefs.
                int slopPx = (int) (getDoubleTapSlopDp() * canvas.getDisplayDensity() + 0.5f);
                inputModeHandlers[i].setDoubleTapSlopPx(slopPx);
                inputModeHandlers[i].setDoubleTapTimeoutMs(getDoubleTapTimeoutMs());
                return inputModeHandlers[i];
            }
        }
        return null;
    }

    TouchInputHandler getInputHandlerByName(String name) {
        TouchInputHandler result = null;
        for (int id : inputModeIds) {
            TouchInputHandler handler = getInputHandlerById(id);
            if (handler.getId().equals(name)) {
                result = handler;
                break;
            }
        }
        if (result == null) {
            result = getInputHandlerById(R.id.itemInputTouchPanZoomMouse);
        }
        return result;
    }

    int getModeIdFromHandler(TouchInputHandler handler) {
        for (int id : inputModeIds) {
            if (handler == getInputHandlerById(id))
                return id;
        }
        return R.id.itemInputTouchPanZoomMouse;
    }

    float getScrollRate() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.scrollSpeed, Constants.DEFAULT_SCROLL_SPEED);
        return (slider + 1) / 7f;
    }

    float getTouchpadSensitivityMultiplier() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.touchpadSensitivity, Constants.DEFAULT_TOUCHPAD_SENSITIVITY);
        // Coefficient tuned to MS-RDP feel (0.4 was too slow on 560dpi devices).
        return (slider + 1) * 0.6f;
    }

    float getEdgeThresholdDpPref() {
        return Utils.querySharedPreferencesInt(this, Constants.edgeThresholdDp, Constants.DEFAULT_EDGE_THRESHOLD_DP);
    }

    float getMouseAccelerationStrength() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.mouseAccelerationStrength, Constants.DEFAULT_MOUSE_ACCELERATION_STRENGTH);
        return slider / 10f; // default slider 10 -> 1.0f (legacy acceleration curve)
    }

    /** Whether the RDP pointer acceleration curve is enabled (setting ignored by non-RDP handlers). */
    boolean getRdpPointerAccelEnabled() {
        return Utils.querySharedPreferenceBoolean(this, Constants.rdpPointerAccelEnabled, Constants.DEFAULT_RDP_POINTER_ACCEL_ENABLED);
    }

    /** Whether the RDP touchpad tracks the finger precisely, bypassing touchpad sensitivity and pointer acceleration (setting ignored by non-RDP handlers). */
    boolean getRdpPreciseFingerTracking() {
        return Utils.querySharedPreferenceBoolean(this, Constants.rdpPreciseFingerTracking, Constants.DEFAULT_RDP_PRECISE_FINGER_TRACKING);
    }

    /** Slow-movement gain for the RDP pointer acceleration curve, slider percent clamped to 25-300 and divided by 100. */
    float getRdpPointerAccelLowGain() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.rdpPointerAccelLowGainPct, Constants.DEFAULT_RDP_POINTER_ACCEL_LOW_GAIN_PCT);
        return Math.max(25, Math.min(300, slider)) / 100f;
    }

    /** Fast-movement gain for the RDP pointer acceleration curve, never below the slow-movement gain. */
    float getRdpPointerAccelHighGain() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.rdpPointerAccelHighGainPct, Constants.DEFAULT_RDP_POINTER_ACCEL_HIGH_GAIN_PCT);
        return Math.max(getRdpPointerAccelLowGain(), Math.max(100, Math.min(600, slider)) / 100f);
    }

    float getFlingResistanceDamp() {
        int slider = Utils.querySharedPreferencesInt(this, Constants.flingResistance, Constants.DEFAULT_FLING_RESISTANCE);
        return 0.92f - slider * 0.01f; // default slider 6 -> 0.86 (legacy FLING_DAMP); higher slider = more resistance = shorter fling
    }

    int getRdpModifierKeyHeightDp() {
        return Utils.querySharedPreferencesInt(this, Constants.rdpModifierKeyHeightDp, Constants.DEFAULT_RDP_MODIFIER_KEY_HEIGHT_DP);
    }

    int getRdpModifierKeySizeDp() {
        return Utils.querySharedPreferencesInt(this, Constants.rdpModifierKeySizeDp, Constants.DEFAULT_RDP_MODIFIER_KEY_SIZE_DP);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        RemoteKeyboard k = remoteConnection.getKeyboard();
        if (k != null) {
            k.setAfterMenu(true);
        }
        int itemId = item.getItemId();
        if (itemId == R.id.itemInfo) {
            remoteConnection.showConnectionInfo();
            return true;
        } else if (itemId == R.id.itemSpecialKeys) {
            showDialog(R.layout.metakey);
            return true;
        } else if (itemId == R.id.itemColorMode) {
            selectColorModel();
            return true;
            // Following sets one of the scaling options
        } else if (itemId == R.id.itemZoomable || itemId == R.id.itemOneToOne || itemId == R.id.itemFitToScreen) {
            AbstractScaling.getById(item.getItemId()).setScaleTypeForActivity(this);
            item.setChecked(true);
            showPanningState(false);
            return true;
        } else if (itemId == R.id.itemCenterMouse) {
            remoteConnection.getPointer().movePointer(
                    canvas.absoluteXPosition + canvas.getVisibleDesktopWidth() / 2,
                    canvas.absoluteYPosition + canvas.getVisibleDesktopHeight() / 2);
            return true;
        } else if (itemId == R.id.itemDisconnect) {
            disconnectAndFinishActivity();
            return true;
        } else if (itemId == R.id.itemEnterText) {
            openSendTextPanel();
            return true;
        } else if (itemId == R.id.itemCtrlAltDel) {
            remoteConnection.getKeyboard().sendMetaKey(MetaKeyBean.keyCtrlAltDel);
            return true;
        } else if (itemId == R.id.itemSendKeyAgain) {
            sendSpecialKeyAgain();
            return true;
        } else if (itemId == R.id.itemExtraKeys) {
            if (connection.getExtraKeysToggleType() == Constants.EXTRA_KEYS_ON) {
                connection.setExtraKeysToggleType(Constants.EXTRA_KEYS_OFF);
                item.setTitle(R.string.extra_keys_enable);
                setExtraKeysVisibility(View.GONE, false);
            } else {
                connection.setExtraKeysToggleType(Constants.EXTRA_KEYS_ON);
                item.setTitle(R.string.extra_keys_disable);
                setExtraKeysVisibility(View.VISIBLE, false);
                extraKeysHidden = false;
            }
            invalidateOptionsMenu();
            connection.save(this);
            return true;
        } else if (itemId == R.id.itemHelpInputMode) {
            showDialog(R.id.itemHelpInputMode);
            return true;
        } else {
            boolean inputModeSet = setInputMode(item.getItemId());
            item.setChecked(inputModeSet);
            if (inputModeSet) {
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void disconnectAndFinishActivity() {
        remoteConnection.closeConnection();
        Utils.justFinish(this);
    }

    /**
     * Promote the current process to a foreground service so the OS does not kill
     * the in-flight RDP/SPICE/VNC/oVirt session when the user backgrounds the app
     * or locks the device. The notification is dismissed by
     * {@link RemoteConnection#closeConnection()} (single canonical close point,
     * covers user- and remote-initiated disconnects) and defensively by
     * {@link #onDestroy()}.
     */
    private void startRemoteSessionService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            // Android 13+ requires POST_NOTIFICATIONS at runtime. The service still
            // starts (foreground promotion + connection keepalive are independent
            // of notification visibility); on grant the launcher callback above
            // re-issues startForeground so the notification posts.
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        String nickname = "";
        String address = "";
        if (connection != null) {
            nickname = connection.getNickname() != null ? connection.getNickname() : "";
            address = connection.getAddress() != null ? connection.getAddress() : "";
        }
        RemoteSessionService.start(this, sessionLaunchExtras, nickname, address);
    }

    public boolean setInputMode(int id) {
        TouchInputHandler input = getInputHandlerById(id);
        if (input != null) {
            setInputHandler(input);
            connection.setInputMode(input.getId());
            if (input.getId().equals(TouchInputHandlerTouchpad.ID)) {
                connection.setFollowMouse(true);
                connection.setFollowPan(true);
            } else {
                connection.setFollowMouse(false);
                connection.setFollowPan(false);
                remoteConnection.getPointer().setRelativeEvents(false);
            }

            showPanningState(true);
            connection.save(this);
            return true;
        }
        return false;
    }

    private void setInputHandler(TouchInputHandler input) {
        touchInputHandler = input;
        inputListener = new RemoteClientsInputListener(
                this,
                remoteConnection,
                remoteConnection,
                touchInputHandler,
                this::resetOnScreenKeys,
                connection.getUseDpadAsArrows()
        );
        canvas.setOnKeyListener(inputListener);

        // WS2 integration: lazily attach the RDP modifier-row handler the
        // first time we wire up an input handler. By this point the
        // RemoteRdpKeyboard has been created (in RemoteRdpConnection.initializeConnection
        // which runs before setModes posts the Runnable that calls us). attach()
        // bails out for non-RDP flavors and when the container id is missing,
        // so the null-check here doubles as both gates. We then immediately
        // call onKeyboardReady() so the dispatched-key listener and extra-keys
        // grid client are wired in even if the keyboard was not yet available
        // at attach time (idempotent in either order).
        if (Utils.isRdp(this) && rdpModifierRowHandler == null) {
            rdpModifierRowHandler = RdpModifierRowHandler.attach(this,
                    state -> setInputAreaState(state));
            if (rdpModifierRowHandler != null) {
                rdpModifierRowHandler.onKeyboardReady();
                // Apply runtime user-preference row sizing to the freshly-built
                // row. Re-uses the round-4 getter pattern; idempotent on
                // ModifierRowView, so safe to also push from onResume.
                rdpModifierRowHandler.applyModifierRowSizing(
                        getRdpModifierKeyHeightDp(),
                        getRdpModifierKeySizeDp());
            }
        }
    }

    private void sendSpecialKeyAgain() {
        ArrayList<MetaKeyBean> keys = new ArrayList<>();
        Database.runReadable(this, db -> tryPopulateKeysInListWhereFieldMatchesValue(
                db, keys, MetaKeyBean.GEN_FIELD__ID, connection.getLastMetaKeyId(), false
        ));
        if (!keys.isEmpty()) {
            remoteConnection.getKeyboard().sendMetaKey(keys.get(0));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy called.");
        // Tear down the touchpad RDP gating before closeConnection so any
        // in-flight fling runnable is cancelled and the post-destroy handler
        // can't tick into a stale state.
        if (touchInputHandler instanceof TouchInputHandlerTouchpad) {
            ((TouchInputHandlerTouchpad) touchInputHandler).setRdp(false);
        }
        if (remoteConnection != null)
            remoteConnection.closeConnection();
        // Defensive: if closeConnection was skipped (shouldn't happen, but the
        // service would otherwise leak across the rare path where the activity
        // is destroyed without a clean connection close), stop the foreground
        // service directly. RemoteSessionService.stop is itself idempotent.
        RemoteSessionService.stop(this);
        System.gc();
    }

    public void showPanningState(boolean showLonger) {
        if (showLonger) {
            final Snackbar t = Utils.makeMessage(canvas, touchInputHandler.getDescription(), Snackbar.LENGTH_LONG);
            TimerTask tt = new TimerTask() {
                @Override
                public void run() {
                    t.show();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Ignored InterruptedException during showPanningState");
                    }
                    t.show();
                }
            };
            new Timer().schedule(tt, 2000);
            t.show();
        } else {
            Utils.showMessage(canvas, touchInputHandler.getDescription(), Snackbar.LENGTH_SHORT);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Activity#onTrackballEvent(android.view.MotionEvent)
     */
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onTrackballEvent(event);
        }
        if (!consumed) {
            consumed = super.onTrackballEvent(event);
        }
        return consumed;
    }

    // Send touch events or mouse events like button clicks to be handled.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onTouchEvent(event);
        }
        if (!consumed) {
            consumed = super.onTrackballEvent(event);
        }
        return consumed;
    }

    // Send e.g. mouse events like hover and scroll to be handled.
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean consumed = false;
        if (inputListener != null) {
            consumed = inputListener.onGenericMotionEvent(event);
        }
        if (!consumed) {
            consumed = super.onGenericMotionEvent(event);
        }
        return consumed;
    }

    private void selectColorModel() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ListView list = getColorsListView(dialog);
        dialog.setContentView(list);
        dialog.show();
    }

    @NonNull
    private ListView getColorsListView(Dialog dialog) {
        String[] choices = new String[COLORMODEL.values().length];
        int currentSelection = -1;
        for (int i = 0; i < choices.length; i++) {
            COLORMODEL cm = COLORMODEL.values()[i];
            choices[i] = cm.toString();
            if (remoteConnection.isColorModel(cm))
                currentSelection = i;
        }
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_checked, choices));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(currentSelection, true);
        list.setOnItemClickListener((arg0, arg1, arg2, arg3) -> {
            dialog.dismiss();
            COLORMODEL cm = COLORMODEL.values()[arg2];
            remoteConnection.setColorModel(cm);
            connection.setColorModel(cm.nameString());
            connection.save(RemoteCanvasActivity.this);
            Utils.showMessage(canvas, getString(R.string.info_update_color_model_to) + cm, Snackbar.LENGTH_LONG);
        });
        return list;
    }

    public void showActionBar() {
        // No-op: the toolbar is no longer auto-shown/hidden on touch input.
        // The toolbar (RemoteToolbar) is the action bar but is now anchored to
        // the @+id/toolbarToggleButton FAB and toggled by tapping that FAB.
        // Kept as a no-op so legacy callers (TouchInputDelegate touch handlers,
        // ScrollWheelButton) still compile.
    }

    public void showKeyboardIcon() {
        // TV-only: schedule the keyboard-icon shower; no longer touches the
        // action bar (the toolbar auto-show/hide on touch was removed).
        handler.removeCallbacks(keyboardIconShower);
        handler.postAtTime(keyboardIconShower, SystemClock.uptimeMillis() + 50);
    }

    @Override
    public void onTextSelected(String selectedString) {
        Log.i(TAG, "onTextSelected called with selectedString: " + selectedString);
        remoteConnection.pd.show();
        connection.setVmname(remoteConnection.vmNameToId.get(selectedString));
        connection.save(this);
        synchronized (remoteConnection.getRfbConn()) {
            remoteConnection.getRfbConn().notify();
        }
    }

    public void toggleKeyboard(MenuItem menuItem) {
        if (softKeyboardUp) {
            hideKeyboard();
        } else {
            showKeyboard();
        }
    }

    public void showKeyboard() {
        Log.i(TAG, "Showing keyboard and hiding action bar");
        canvas.requestFocus();
        Utils.showKeyboard(this, canvas);
        softKeyboardUp = true;
        // Keyboard takes the screen; collapse any visible toolbar so a stale
        // toolbarExpanded flag doesn't trap the next toggle-FAB tap as a no-op.
        toolbarExpanded = false;
        Objects.requireNonNull(getSupportActionBar()).hide();
    }

    public void hideKeyboard() {
        Log.i(TAG, "Hiding keyboard and hiding action bar");
        canvas.requestFocus();
        Utils.hideKeyboard(this, getCurrentFocus());
        softKeyboardUp = false;
        // Keep toolbarExpanded in sync with what the user can see.
        if (getSupportActionBar() != null && !getSupportActionBar().isShowing()) {
            toolbarExpanded = false;
        }
        Objects.requireNonNull(getSupportActionBar()).hide();
    }

    public void hideKeyboardAndExtraKeys() {
        hideKeyboard();
        if (extraKeysToolbar.getVisibility() == View.VISIBLE) {
            extraKeysHidden = true;
            setExtraKeysVisibility(View.GONE, false);
        }
        // INV-008: clear on-screen modifier state so a new RDP session does
        // not inherit Shift/Ctrl/Alt/Win locks from the previous one. The
        // handler also re-bridges any locked modifiers into the keyboard's
        // onScreenMetaState; resetAll + clearMetaState covers both paths.
        if (rdpModifierRowHandler != null) {
            rdpModifierRowHandler.resetRowState();
        }
    }

    /**
     * Returns the current state of the RDP input area (NONE/KEYBOARD/EXTRA).
     * Defaults to NONE for non-RDP flavors.
     */
    public InputAreaState getInputAreaState() {
        return inputAreaState;
    }

    /**
     * Sets the RDP input-area state machine and synchronizes the
     * {@code @+id/rdpInputAreaContainer} visibility accordingly. The actual
     * modifier-row / extra-keys-grid inflation into the container is left to
     * the WS2 integration pass via the {@link #updateRdpInputAreaVisibility()}
     * hook.
     */
    public void setInputAreaState(InputAreaState newState) {
        this.inputAreaState = newState == null ? InputAreaState.NONE : newState;
        // Forward to the modifier-row handler if it's been attached. The
        // handler owns the modifier-row / extra-keys-grid visibility and the
        // IME toggling on KEYBOARD<->EXTRA transitions; the activity still
        // owns the container visibility below.
        if (rdpModifierRowHandler != null) {
            rdpModifierRowHandler.onInputAreaStateChanged(this.inputAreaState);
        }
        updateRdpInputAreaVisibility();
        // RDP viewport math depends on the input-area container's measured
        // height (VISIBLE for KEYBOARD/EXTRA, GONE for NONE), so we must
        // recompute after the visibility flip above.
        if (Utils.isRdp(this) && canvas != null) {
            recomputeRdpViewport();
        }
    }

    /**
     * RDP-only viewport recompute driven by the IME insets listener and the
     * {@link #setInputAreaState} transition. Captures the physical (full-screen)
     * canvas height once when the IME is closed, then shrinks
     * {@code canvas.visibleDesktopHeight} by the IME height and the visible
     * RDP input-area container height so the canvas never overlaps the IME.
     * Lifts the container out of the IME region via {@code setTranslationY}
     * for the no-window-resize model; the resize model does not need a
     * translation because the container is already bottom-gravity inside the
     * resized window.
     */
    private void recomputeRdpViewport() {
        if (canvas == null || canvas.getDrawable() == null) return;
        int canvasH = canvas.getHeight();
        if (canvasH <= 0) return;
        if (lastImeHeightPx == 0) {
            // IME closed: canvas height IS the physical full-screen height. Refresh on
            // every recompute so rotation / multi-window resize update the zoom floor
            // even when the activity itself survives those configuration changes.
            canvas.setRdpFullViewHeight(canvas.getHeight());
        } else if (canvas.getRdpFullViewHeight() <= 0) {
            // First capture while IME is already open: add back the IME height so the
            // floor matches the pre-IME physical height.
            canvas.setRdpFullViewHeight(canvas.getHeight() + lastImeHeightPx);
        }
        int containerH = 0;
        if (rdpInputAreaContainer != null && rdpInputAreaContainer.getVisibility() == View.VISIBLE) {
            int ch = rdpInputAreaContainer.getHeight();
            containerH = (ch > 0) ? ch : 0; // first-pass-zero guard
        }

        // Reserve vertical space below the input area for the floating keyboard toggle
        // button so the toggle visually sits UNDERNEATH the modifier row / extra-keys
        // grid instead of overlapping its bottom-right corner. Reservation is the toggle
        // button's measured height + its bottom margin + an 8dp gap; applied only when
        // (a) the input area is visible, (b) the toggle is visible, and (c) the IME is
        // closed — when the IME is open the toggle is hidden behind it, so reserving space
        // for it would just create a 48dp gap between the input area and the IME top.
        int toggleReserved = 0;
        if (lastImeHeightPx == 0
                && rdpInputAreaContainer != null
                && rdpInputAreaContainer.getVisibility() == View.VISIBLE
                && keyboardToggleButton != null
                && keyboardToggleButton.getVisibility() == View.VISIBLE) {
            int toggleH = keyboardToggleButton.getHeight();
            if (toggleH > 0) {
                int gapPx = (int) (8 * getResources().getDisplayMetrics().density + 0.5f);
                int bottomMarginPx = 0;
                ViewGroup.LayoutParams lp = keyboardToggleButton.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    bottomMarginPx = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
                }
                toggleReserved = toggleH + bottomMarginPx + gapPx;
            }
        }

        // Canvas visible-height accounts for the input area AND the toggle-reserved gap so
        // the framebuffer is not drawn behind the translated input area's bottom edge.
        int usable = canvas.getRdpFullViewHeight() - lastImeHeightPx - containerH - toggleReserved;
        if (usable < 0) usable = 0;
        canvas.setVisibleDesktopHeight(usable);
        canvas.relativePan(0, 0);
        if (rdpInputAreaContainer != null) {
            // If the window did not resize, canvasH == full-height and the IME
            // overlaps the full canvas: lift the container by imeOverlap = ime.
            // If the window did resize, canvasH ≈ full-ime and imeOverlap ≈ 0,
            // leaving the bottom-gravity container in place with no translation.
            int imeOverlap = Math.max(0, lastImeHeightPx - (canvas.getRdpFullViewHeight() - canvasH));
            rdpInputAreaContainer.setTranslationY(-imeOverlap - toggleReserved);
        }
    }

    /**
     * Hook for the WS2 integration pass: shows/hides the
     * {@code @+id/rdpInputAreaContainer} based on the current
     * {@link #inputAreaState}. The container is visible for KEYBOARD and EXTRA
     * and gone for NONE. The modifier-row / extra-keys-grid inflate themselves
     * into the container; WS2 owns that wiring.
     */
    private void updateRdpInputAreaVisibility() {
        if (!Utils.isRdp(this) || rdpInputAreaContainer == null) {
            return;
        }
        int desired = (inputAreaState == InputAreaState.NONE) ? View.GONE : View.VISIBLE;
        if (rdpInputAreaContainer.getVisibility() != desired) {
            rdpInputAreaContainer.setVisibility(desired);
        }
    }

    /**
     * State-machine tap handler for the floating {@code @+id/keyboardToggleButton}.
     * Behavior:
     *   - state NONE -> open the IME, state -> KEYBOARD
     *   - state KEYBOARD or EXTRA -> open the IME, state -> KEYBOARD
     *     (the 123 toggle on the modifier row swaps IME <-> grid; this button
     *      always lands on the IME).
     */
    private void onKeyboardToggleButtonClicked() {
        if (Utils.isRdp(this)) {
            // RDP: land on the IME (and collapse the extra-keys grid if it is
            // showing); onBackToKeyboard calls Utils.showKeyboard internally
            // and requests canvas focus itself, so this method stays the single
            // entry point for IME-show on this button.
            if (rdpModifierRowHandler != null) {
                rdpModifierRowHandler.onBackToKeyboard();
            } else {
                setInputAreaState(InputAreaState.KEYBOARD);
            }
        } else if (softKeyboardUp) {
            hideKeyboard();
        } else {
            showKeyboard();
        }
    }

    /**
     * Wires the always-visible @+id/toolbarToggleButton FAB with a drag-vs-tap
     * touch listener and a click listener that expands/collapses the
     * RemoteToolbar action bar. Mirrors the keyboard FAB's click-bug-fix
     * pattern (ACTION_UP dispatches performClick only when the finger stayed
     * within scaledTouchSlop), and persists the new x/y to the same per-id
     * USELASTPOSITIONTOOLBAR_* columns the moveToolbar drag handle used.
     */
    private void setupToolbarToggleButton() {
        if (toolbarToggleButton == null || handler == null) return;
        final int touchSlop = ViewConfiguration.get(toolbarToggleButton.getContext()).getScaledTouchSlop();
        final float[] fingerDownXY = new float[2];
        final float[] dx = new float[1];
        final float[] dy = new float[1];
        final boolean[] movedBeyondSlop = new boolean[1];
        toolbarToggleButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fingerDownXY[0] = event.getRawX();
                    fingerDownXY[1] = event.getRawY();
                    dx[0] = v.getX() - event.getRawX();
                    dy[0] = v.getY() - event.getRawY();
                    movedBeyondSlop[0] = false;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!movedBeyondSlop[0]) {
                        float totalDx = event.getRawX() - fingerDownXY[0];
                        float totalDy = event.getRawY() - fingerDownXY[1];
                        if (Math.abs(totalDx) > touchSlop || Math.abs(totalDy) > touchSlop) {
                            movedBeyondSlop[0] = true;
                        }
                    }
                    if (movedBeyondSlop[0]) {
                        v.animate().x(event.getRawX() + dx[0])
                                .y(event.getRawY() + dy[0])
                                .setDuration(0).start();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (!movedBeyondSlop[0]) {
                        v.performClick();
                    } else {
                        saveToolbarTogglePosition((int) v.getX(), (int) v.getY());
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return false;
            }
        });
        toolbarToggleButton.setOnClickListener(v -> toggleToolbarExpansion());
    }

    /**
     * Toggles visibility of the action-bar RemoteToolbar next to the
     * @+id/toolbarToggleButton FAB. While the IME is up the toolbar stays
     * hidden (showKeyboard/hideKeyboard own the screen); a tap on the FAB
     * is a no-op in that state so we never overlap the keyboard with the
     * toolbar.
     */
    private void toggleToolbarExpansion() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) return;
        if (toolbarExpanded) {
            actionBar.hide();
            toolbarExpanded = false;
        } else if (!softKeyboardUp) {
            positionToolbarNextToToggle();
            actionBar.show();
            toolbarExpanded = true;
        }
    }

    /**
     * Anchors the action-bar toolbar to a position next to the toolbar-toggle
     * FAB. Prefers the left side of the FAB (free space on the left is the
     * dominant layout in this app), and falls back to the right side if there
     * is not enough room to fit the toolbar without clipping. Clamps the final
     * coordinates so the toolbar stays inside the canvasLayout bounds.
     */
    private void positionToolbarNextToToggle() {
        if (toolbar == null || toolbarToggleButton == null || canvasLayout == null) return;
        // Toolbar is GONE by default — measure explicitly with INVISIBLE so the
        // getWidth/getHeight / getMeasuredWidth calls return real numbers.
        if (toolbar.getVisibility() != View.VISIBLE) {
            toolbar.setVisibility(View.INVISIBLE);
        }
        int toolbarW = toolbar.getWidth();
        int toolbarH = toolbar.getHeight();
        if (toolbarW == 0 || toolbarH == 0) {
            int widthSpec = View.MeasureSpec.makeMeasureSpec(canvasLayout.getWidth(), View.MeasureSpec.AT_MOST);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(canvasLayout.getHeight(), View.MeasureSpec.AT_MOST);
            toolbar.measure(widthSpec, heightSpec);
            toolbarW = toolbar.getMeasuredWidth();
            toolbarH = toolbar.getMeasuredHeight();
            toolbar.layout(0, 0, toolbarW, toolbarH);
        }
        int toggleX = (int) toolbarToggleButton.getX();
        int toggleY = (int) toolbarToggleButton.getY();
        int toggleW = toolbarToggleButton.getWidth();
        int toggleH = toolbarToggleButton.getHeight();
        int canvasW = canvasLayout.getWidth();
        int canvasH = canvasLayout.getHeight();
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        int toggleCenterX = toggleX + toggleW / 2;
        int toggleCenterY = toggleY + toggleH / 2;

        // Prefer left of the FAB; fall back to right if there isn't room.
        int newX;
        if (toggleCenterX >= toolbarW + 2 * margin) {
            newX = toggleX - toolbarW - margin;
        } else {
            newX = toggleX + toggleW + margin;
        }
        int newY = toggleCenterY - toolbarH / 2;

        // Clamp to canvas bounds.
        newX = Math.max(margin, Math.min(newX, canvasW - toolbarW - margin));
        newY = Math.max(margin, Math.min(newY, canvasH - toolbarH - margin));

        toolbar.setX(newX);
        toolbar.setY(newY);
    }

    /**
     * Re-applies the saved toolbar-toggle FAB position on every layout pass
     * (same pattern as the legacy @code offsetOrRestoreSavedToolbarPosition)
     * so the FAB sticks to where the user dragged it. If the saved position
     * is no longer on the canvas (rotation, smaller window, etc.) we leave the
     * FAB at the gravity default.
     */
    private void restoreToolbarTogglePosition() {
        if (toolbarToggleButton == null || canvasLayout == null || connection == null) return;
        if (!connection.getUseLastPositionToolbar() || !connection.getUseLastPositionToolbarMoved()) return;
        int savedX = connection.getUseLastPositionToolbarX();
        int savedY = connection.getUseLastPositionToolbarY();
        int btnW = toolbarToggleButton.getWidth();
        int btnH = toolbarToggleButton.getHeight();
        if (btnW == 0 || btnH == 0) return; // not yet measured
        int canvasW = canvasLayout.getWidth();
        int canvasH = canvasLayout.getHeight();
        if (savedX >= 0 && savedY >= 0
                && savedX + btnW <= canvasW
                && savedY + btnH <= canvasH) {
            toolbarToggleButton.setX(savedX);
            toolbarToggleButton.setY(savedY);
        }
    }

    private void saveToolbarTogglePosition(int x, int y) {
        if (connection == null) return;
        connection.setUseLastPositionToolbarX(x);
        connection.setUseLastPositionToolbarY(y);
        connection.setUseLastPositionToolbarMoved(true);
        // connection.save() runs an SQLite UPDATE on the caller thread (see
        // Database.runWritable). Defer off the UI thread to match the pre-existing
        // ActionBarPositionSaver pattern (handler.postAtTime) the user removed.
        final Connection c = connection;
        handler.post(() -> c.save(RemoteCanvasActivity.this));
    }

    public Connection getConnection() {
        return connection;
    }

    public RemoteCanvas getCanvas() {
        return canvas;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("WORKAROUND_FOR_BUG_19917_KEY", "WORKAROUND_FOR_BUG_19917_VALUE");
        super.onSaveInstanceState(outState);
    }

    private boolean isMasterPasswordEnabled() {
        SharedPreferences sp = getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
        return sp.getBoolean(Constants.masterPasswordEnabledTag, false);
    }

    @Override
    public void onBackPressed() {
        if (GeneralUtils.isTv(this)) {
            disconnectAndFinishActivity();
            super.onBackPressed();
        }
        // RDP: if the input area is showing (modifier row above the IME, or the
        // "extra keys" grid in EXTRA state), back collapses it before the system
        // sees the press. When the IME is visible the OS normally consumes back
        // first (documented user-awareness); this gate covers the EXTRA case and
        // the IME-already-closed case.
        if (Utils.isRdp(this) && inputAreaState != InputAreaState.NONE) {
            hideKeyboard();
            setInputAreaState(InputAreaState.NONE);
            return;
        }
        if (Utils.isRdp(this)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastBackPressForDisconnect > DOUBLE_BACK_DISCONNECT_WINDOW_MS) {
                lastBackPressForDisconnect = now;
                MessageDialogs.displayToast2(this, getString(R.string.back_press_to_disconnect), Toast.LENGTH_SHORT);
                return;
            }
            disconnectAndFinishActivity();
            return;
        }
        if (inputListener != null) {
            inputListener.onKey(canvas, KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        }
    }

    public RemoteConnection getRemoteConnection() {
        return remoteConnection;
    }

    public Handler getHandler() { return handler; }

    private class KeyboardIconShower implements Runnable {
        public void run() {
            if (GeneralUtils.isTv(RemoteCanvasActivity.this)) {
                animateKeyboardIconForAndroidTv();
            }
        }

        private void animateKeyboardIconForAndroidTv() {
            keyboardIconForAndroidTv.setVisibility(View.VISIBLE);
            Log.d(TAG, "KeyboardIconShower: keyboardIconForAndroidTv X position to: " + keyboardIconForAndroidTvX);
            keyboardIconForAndroidTv.setX(keyboardIconForAndroidTvX);
            ObjectAnimator animation = ObjectAnimator.ofFloat(keyboardIconForAndroidTv, "translationX", -100f);
            animation.setDuration(1000);
            animation.start();
        }
    }
}
