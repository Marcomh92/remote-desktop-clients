/**
 * Copyright (C) 2026 Iordan Iordanov
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

package com.iiordanov.bVNC.extrakeys;

import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.undatech.remoteClientUi.R;
import com.iiordanov.bVNC.RemoteCanvasActivity;
import com.iiordanov.bVNC.Utils;
import com.iiordanov.bVNC.input.InputAreaState;
import com.iiordanov.bVNC.input.RemoteKeyboard;
import com.iiordanov.bVNC.input.RemoteRdpKeyboard;

/**
 * Orchestrator for the RDP flavor's modifier row + extra-keys grid.
 *
 * <p>The handler inflates {@code rdp_input_area.xml} into the activity's
 * {@code @+id/rdpInputAreaContainer} FrameLayout (created by WS1) and owns:
 * <ul>
 *   <li>The {@link ModifierRowView} with 8 horizontally-scrollable keys.</li>
 *   <li>The {@link RdpExtraGridPanel} (F-keys + navigation) toggled by 123.</li>
 *   <li>A {@link RemoteRdpKeyboard.KeyDispatchedListener} that drives
 *       one-shot modifier consumption on every dispatched non-modifier key.</li>
 * </ul>
 *
 * <p>The handler follows the canonical extra-keys mechanics exactly: it
 * reads / writes the keyboard's {@code onScreenMetaState} via the same
 * {@code clearMetaState} + {@code onScreen*Toggle} sequence used by
 * {@code ExtraKeysPagerAdapter.syncKeyboardModifierState} (INV-010), and
 * synthesizes Del / Esc / Tab events through {@link RemoteExtraKeysHandler}'s
 * established {@code sendKey} pattern.
 *
 * <p>The owning activity drives the handler through:
 * <ul>
 *   <li>{@link #setVisible(boolean)} - show / hide the whole input area.</li>
 *   <li>{@link #onInputAreaStateChanged(InputAreaState)} - sync from the
 *       activity's external state machine (NONE / KEYBOARD / EXTRA).</li>
 *   <li>{@link #onToggleExtraKeys()} / {@link #onBackToKeyboard()} - the
 *       hook methods that WS1 calls when the user picks 123 / IME back.</li>
 * </ul>
 * When the handler itself drives a transition (e.g. user taps 123), it
 * notifies the supplied {@link InputStateCallback} so WS1 can mirror the
 * change in its own state machine.
 */
public class RdpModifierRowHandler {

    /**
     * Activity-side hook so the WS1 state machine stays in sync when the
     * handler itself flips between KEYBOARD and EXTRA.
     */
    public interface InputStateCallback {
        void onInputAreaStateChanged(@NonNull InputAreaState state);
    }

    private final RemoteCanvasActivity activity;
    private final InputStateCallback callback;
    private final View rootView;
    private final ModifierRowView rowView;
    private final RdpExtraGridPanel gridPanel;

    @Nullable private RemoteRdpKeyboard rdpKeyboard;
    @NonNull private InputAreaState currentState = InputAreaState.NONE;
    private boolean rowVisible = false;

    private RdpModifierRowHandler(@NonNull RemoteCanvasActivity activity,
                                  @Nullable InputStateCallback callback,
                                  @NonNull View rootView,
                                  @NonNull ModifierRowView rowView,
                                  @NonNull RdpExtraGridPanel gridPanel) {
        this.activity = activity;
        this.callback = callback;
        this.rootView = rootView;
        this.rowView = rowView;
        this.gridPanel = gridPanel;

        rowView.setModifierStateListener(this::onRowModifierStateChanged);
        rowView.setActionKeyListener(this::onRowActionKeyClicked);
        rowView.setExtraKeysToggleListener(this::onRowExtraKeysToggleClicked);

        setVisible(false);
    }

    /**
     * Inflate {@code rdp_input_area.xml} into the activity's
     * {@code @+id/rdpInputAreaContainer}, build the {@link ModifierRowView}
     * and {@link RdpExtraGridPanel}, wire listeners, and register a
     * {@link RemoteRdpKeyboard.KeyDispatchedListener} on the keyboard if
     * one is already available. Returns {@code null} if the container
     * id is not present in the activity layout (it will be added by WS1).
     */
    @MainThread
    @Nullable
    public static RdpModifierRowHandler attach(@NonNull RemoteCanvasActivity activity,
                                               @Nullable InputStateCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("RdpModifierRowHandler.attach must be called on the main thread");
        }
        FrameLayout container = activity.findViewById(R.id.rdpInputAreaContainer);
        if (container == null) return null;

        // Avoid re-inflating if attach() is called more than once.
        container.removeAllViews();
        View inflated = LayoutInflater.from(activity)
            .inflate(R.layout.rdp_input_area, container, true);

        ModifierRowView rowView = inflated.findViewById(R.id.modifierRowView);
        RdpExtraGridPanel gridPanel = inflated.findViewById(R.id.rdpExtraGridPanel);
        if (rowView == null || gridPanel == null) return null;

        RdpModifierRowHandler handler = new RdpModifierRowHandler(
            activity, callback, inflated, rowView, gridPanel);
        // Attach + sync are split so the keyboard can be re-attached later
        // (see {@link #onKeyboardReady()}); the recursion path that previously
        // existed between attach and sync is removed.
        handler.attachKeyboardIfAvailable();
        handler.syncRowStateToKeyboard();
        return handler;
    }

    /**
     * Re-attaches to the current keyboard and re-bridges the modifier state.
     * Call from the activity after {@code RemoteRdpKeyboard} is created so
     * the dispatched-key listener and extra-keys grid client are wired in
     * even if the keyboard was not yet available at {@link #attach} time.
     */
    @MainThread
    public void onKeyboardReady() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("RdpModifierRowHandler.onKeyboardReady must be called on the main thread");
        }
        attachKeyboardIfAvailable();
        syncRowStateToKeyboard();
    }

    /**
     * Show / hide the entire input area (row + grid). When shown the row is
     * visible; the grid is shown only when the current state is EXTRA.
     */
    @MainThread
    public void setVisible(boolean visible) {
        this.rowVisible = visible;
        applyVisibility();
    }

    /**
     * Sync handler state from the activity's external state machine.
     * Convenience mapping:
     * <ul>
     *   <li>{@code NONE} - hide both row and grid.</li>
     *   <li>{@code KEYBOARD} - show row, hide grid, request IME focus back.</li>
     *   <li>{@code EXTRA} - show row and grid, hide IME.</li>
     * </ul>
     */
    @MainThread
    public void onInputAreaStateChanged(@NonNull InputAreaState state) {
        this.currentState = state;
        switch (state) {
            case NONE:
                setVisible(false);
                break;
            case KEYBOARD:
                setVisible(true);
                gridPanel.setVisibility(View.GONE);
                rowView.setExtraKeysToggleHighlighted(false);
                break;
            case EXTRA:
                setVisible(true);
                gridPanel.setVisibility(View.VISIBLE);
                rowView.setExtraKeysToggleHighlighted(true);
                break;
        }
    }

    /**
     * Toggle the grid: EXTRA &lt;-&gt; KEYBOARD. The handler updates its
     * internal state, drives the grid visibility and IME, then notifies the
     * callback so WS1's state machine can mirror the change.
     */
    @MainThread
    public void onToggleExtraKeys() {
        if (currentState == InputAreaState.EXTRA) {
            switchTo(InputAreaState.KEYBOARD);
        } else {
            switchTo(InputAreaState.EXTRA);
        }
    }

    /** Force KEYBOARD: hide grid, show IME, notify callback. */
    @MainThread
    public void onBackToKeyboard() {
        switchTo(InputAreaState.KEYBOARD);
    }

    /**
     * Clear every toggle modifier (ON and LOCKED) and zero the keyboard's
     * on-screen metastate. Called from the activity on session teardown so
     * a new connection does not inherit Shift/Ctrl/Alt/Win state from the
     * previous one (INV-008). Safe to call when the keyboard is gone —
     * {@link ModifierRowView#resetAll()} handles its own listener firing
     * and {@link #rdpKeyboard} may be null.
     */
    @MainThread
    public void resetRowState() {
        rowView.resetAll();
        if (rdpKeyboard != null) {
            rdpKeyboard.clearMetaState();
        }
    }

    /**
     * Forward runtime user-preference sizing to the underlying
     * {@link ModifierRowView}. Each setter is independently idempotent on
     * the view (a no-op for non-positive values); callers may safely push
     * both on every attach / {@code onResume}.
     */
    @MainThread
    public void applyModifierRowSizing(int rowHeightDp, int keySizeDp) {
        rowView.setRowHeightDp(rowHeightDp);
        rowView.setKeySizeDp(keySizeDp);
    }

    private void switchTo(@NonNull InputAreaState next) {
        if (currentState == next) return;
        onInputAreaStateChanged(next);
        if (next == InputAreaState.EXTRA) {
            activity.hideKeyboard();
        } else if (next == InputAreaState.KEYBOARD) {
            // Re-show the IME when transitioning back from the grid. Prefer the
            // canvas (matches RemoteCanvasActivity.onKeyboardToggleButtonClicked);
            // fall back to current focus if the canvas isn't yet available.
            // Request canvas focus before showKeyboard so the IME's input
            // connection lands on the canvas and not the previously-focused
            // grid button.
            android.view.View canvas = activity.getCanvas();
            if (canvas != null) canvas.requestFocus();
            android.view.View focus = (canvas != null) ? canvas : activity.getCurrentFocus();
            Utils.showKeyboard(activity, focus);
        }
        if (callback != null) callback.onInputAreaStateChanged(next);
    }

    private void applyVisibility() {
        if (!rowVisible) {
            rootView.setVisibility(View.GONE);
            gridPanel.setVisibility(View.GONE);
            rowView.setExtraKeysToggleHighlighted(false);
            return;
        }
        rootView.setVisibility(View.VISIBLE);
        // Grid visibility is driven by state (EXTRA / KEYBOARD).
        gridPanel.setVisibility(
            currentState == InputAreaState.EXTRA ? View.VISIBLE : View.GONE);
        rowView.setExtraKeysToggleHighlighted(currentState == InputAreaState.EXTRA);
    }

    // ---------------------------------------------------------------------
    // Keyboard access
    // ---------------------------------------------------------------------

    private void attachKeyboardIfAvailable() {
        RemoteKeyboard kb = currentKeyboard();
        if (kb instanceof RemoteRdpKeyboard) {
            RemoteRdpKeyboard rk = (RemoteRdpKeyboard) kb;
            if (rk != rdpKeyboard) {
                // Detach the previous keyboard's listener so we do not leak it.
                if (rdpKeyboard != null) rdpKeyboard.setKeyDispatchedListener(null);
                rdpKeyboard = rk;
                rdpKeyboard.setKeyDispatchedListener(this::onKeyDispatched);
            }
        }
        gridPanel.setRemoteKeyboard(kb);
    }

    @Nullable
    private RemoteKeyboard currentKeyboard() {
        if (activity.getRemoteConnection() == null) return null;
        return activity.getRemoteConnection().getKeyboard();
    }

    // ---------------------------------------------------------------------
    // Row listeners
    // ---------------------------------------------------------------------

    private void onRowModifierStateChanged(@NonNull ModifierRowView view) {
        // User toggled a row modifier OFF / ON / LOCKED. Snapshot the keyboard's
        // previous on-screen state, re-bridge, then physically release any bit
        // that went away (so e.g. LOCKED Alt -> OFF fires LMENU up exactly once).
        attachKeyboardIfAvailable();
        RemoteRdpKeyboard kb = rdpKeyboard;
        int prev = (kb != null) ? kb.getOnScreenMetaState() : 0;
        syncRowStateToKeyboard();
        if (kb == null) return;
        int now = kb.getOnScreenMetaState();
        int released = prev & ~now;
        if (released != 0) {
            kb.releaseOnScreenModifiers(released);
        }
    }

    /**
     * Bring {@code onScreenMetaState} into agreement with the row's current
     * (ON-OR-LOCKED) modifier set. This is the INV-010 bridge:
     * clearMetaState() + onScreen*Toggle() per active modifier. LOCKED
     * modifiers remain because isOnOrLocked() returns true for them.
     */
    private void syncRowStateToKeyboard() {
        attachKeyboardIfAvailable();
        RemoteRdpKeyboard kb = rdpKeyboard;
        if (kb == null) return;
        kb.clearMetaState();
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.CTRL))  kb.onScreenCtrlToggle();
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.ALT))   kb.onScreenAltToggle();
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.SHIFT)) kb.onScreenShiftToggle();
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.SUPER)) kb.onScreenSuperToggle();
    }

    private void onRowActionKeyClicked(@NonNull ModifierRowView.ActionKey key) {
        attachKeyboardIfAvailable();
        RemoteRdpKeyboard kb = rdpKeyboard;
        if (kb == null) return;
        Integer keyCode = null;
        switch (key) {
            case DEL: keyCode = KeyEvent.KEYCODE_FORWARD_DEL; break;
            case ESC: keyCode = KeyEvent.KEYCODE_ESCAPE;      break;
            case TAB: keyCode = KeyEvent.KEYCODE_TAB;          break;
        }
        if (keyCode == null) return;
        sendKeyWithCurrentMetaState(kb, keyCode);
    }

    private void onRowExtraKeysToggleClicked() {
        onToggleExtraKeys();
    }

    // ---------------------------------------------------------------------
    // One-shot consumption (the key-dispatched hook)
    // ---------------------------------------------------------------------

    /**
     * Invoked by {@link RemoteRdpKeyboard} after a successful non-modifier
     * key dispatch. Clears every ON (non-locked) modifier and re-bridges
     * the remaining LOCKED modifiers into {@code onScreenMetaState}.
     * Runs synchronously on the main thread — the key-dispatched callback
     * itself already runs on the main thread, so re-posting would let the
     * next user tap land before consumption and re-introduce Ctrl+key for
     * the wrong next keystroke.
     */
    @MainThread
    private void onKeyDispatched() {
        rowView.consumeOnModifiers();
        // Re-bridge so LOCKED modifiers still flow into onScreenMetaState and
        // consumed one-shot bits fall out. consumeOnModifiers no longer fires
        // the listener (consumption is internal, not a user toggle).
        syncRowStateToKeyboard();
    }

    // ---------------------------------------------------------------------
    // Action-key synthesis (Del / Esc / Tab)
    // ---------------------------------------------------------------------

    /**
     * Send a synthetic ACTION_DOWN/ACTION_UP for {@code keyCode} carrying the
     * current modifier bits. We pull from the row state (NOT onScreenMetaState)
     * because this method may be invoked while one-shot modifiers are still
     * effectively active for this very keystroke; the listener will then clear
     * them via {@link #onKeyDispatched()}.
     *
     * <p>Both events reuse the same captured metastate. {@code
     * RemoteRdpKeyboard.processLocalKeyEvent} consumes the DOWN's metastate
     * into its {@code lastDownMetaState} slot and the UP replay path emits
     * the wire event with that same slot — re-capturing between DOWN and UP
     * is dead work that would only matter if the wire layer read the UP's
     * local event metastate, which it does not.
     */
    private void sendKeyWithCurrentMetaState(@NonNull RemoteRdpKeyboard kb, int keyCode) {
        long now = SystemClock.uptimeMillis();
        int metaState = buildMetaState();
        kb.processLocalKeyEvent(keyCode,
            new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState), metaState);
        kb.processLocalKeyEvent(keyCode,
            new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState), metaState);
    }

    /**
     * Build the metastate bits from the row's current ON-or-LOCKED modifiers,
     * mirroring {@code RemoteExtraKeysHandler.buildMetaState} so the RDP server
     * receives Ctrl+A the same way whether it came from the row modifier + a
     * real letter or from the pre-existing extra-keys toolbar.
     */
    private int buildMetaState() {
        int meta = 0;
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.CTRL)) {
            meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        }
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.ALT)) {
            meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.SHIFT)) {
            meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }
        if (rowView.isOnOrLocked(ModifierRowView.ModifierKey.SUPER)) {
            meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        }
        return meta;
    }
}
