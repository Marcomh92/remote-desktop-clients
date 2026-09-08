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

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Horizontally-scrollable row of 8 Microsoft-RDP-style keys:
 * <pre>
 * [ Win | Shift | Ctrl | Alt | Del | Esc | Tab | 123 ]
 * </pre>
 *
 * <p>The first four are toggle modifiers (OFF / ON one-shot / LOCKED).
 * Del / Esc / Tab are momentary action keys that fire a click without any
 * state. The 123 button is a toggle that the handler interprets as
 * "show / hide the extra-keys grid".
 *
 * <p>Double-tap detection (within {@link #DOUBLE_TAP_WINDOW_MS}) reuses the
 * exact 800 ms interval used by
 * {@code RdpKeyboardMapper.checkToggleModifierLock}. Tap semantics:
 * <ul>
 *   <li>OFF &rarr; ON</li>
 *   <li>ON &rarr; LOCKED (double-tap)</li>
 *   <li>ON &rarr; OFF (single tap, not preceded by another tap within 800 ms)</li>
 *   <li>LOCKED &rarr; OFF</li>
 * </ul>
 *
 * <p>Horizontal scroll position is held in a session field (not persisted).
 */
public class ModifierRowView extends HorizontalScrollView {

    /** Same window as {@code RdpKeyboardMapper.checkToggleModifierLock}. */
    public static final long DOUBLE_TAP_WINDOW_MS = 800L;

    /** The four toggle modifiers we expose to listeners. */
    public enum ModifierKey { SUPER, SHIFT, CTRL, ALT }

    /** Momentary action keys in declaration order. */
    enum ActionKey { DEL, ESC, TAB }

    /** Three-state visual + flag state for a single toggle modifier. */
    private static final class RowState {
        boolean isOn;     // one-shot, consumed on next dispatched key
        boolean isLocked; // explicit lock, survives one-shot consumption
    }

    /** Callback to drive the keyboard's on-screen metastate. */
    public interface ModifierStateListener {
        /** Fired whenever any toggle modifier transitions. */
        void onModifierStateChanged(@NonNull ModifierRowView view);
    }

    /** Callback for momentary action key clicks. */
    public interface ActionKeyListener {
        void onActionKeyClicked(@NonNull ActionKey key);
    }

    /** Callback for the 123 toggle button. */
    public interface ExtraKeysToggleListener {
        void onExtraKeysToggleClicked();
    }

    // Colors borrowed from the existing ExtraKeysView palette, kept inline so
    // we do not modify ExtraKeysView.java.
    private static final int COLOR_OFF_BG = 0xFF212121;
    private static final int COLOR_OFF_FG = 0xFFFFFFFF;
    private static final int COLOR_ON_BG  = 0xFF7F7F7F;
    private static final int COLOR_ON_FG  = 0xFF80DEEA;
    private static final int COLOR_LOCK_BG = 0xFF80DEEA;
    private static final int COLOR_LOCK_FG = 0xFF212121;
    private static final int COLOR_TOGGLE_OFF_BG = 0xFF00838B; // teal accent
    private static final int COLOR_TOGGLE_OFF_FG = 0xFFFFFFFF;
    private static final int COLOR_TOGGLE_ON_BG  = 0xFF80DEEA; // highlighted
    private static final int COLOR_TOGGLE_ON_FG  = 0xFF212121;

    private final EnumMap<ModifierKey, RowState> rowStates = new EnumMap<>(ModifierKey.class);
    private final Map<ModifierKey, MaterialButton> modButtons = new LinkedHashMap<>();
    private final Map<ActionKey, MaterialButton> actionButtons = new LinkedHashMap<>();
    private MaterialButton toggleButton;

    @Nullable private ModifierStateListener modListener;
    @Nullable private ActionKeyListener actionListener;
    @Nullable private ExtraKeysToggleListener toggleListener;

    private boolean extraKeysHighlighted = false;

    // Double-tap bookkeeping per-modifier (key + last-tap time).
    private ModifierKey lastTappedKey = null;
    private long lastTapTime = 0L;

    public ModifierRowView(Context context) {
        super(context);
        init(context);
    }

    public ModifierRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ModifierRowView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);

        for (ModifierKey k : ModifierKey.values()) {
            rowStates.put(k, new RowState());
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        addModifierButton(context, row, ModifierKey.SUPER,  "Win");
        addModifierButton(context, row, ModifierKey.SHIFT, "Shift");
        addModifierButton(context, row, ModifierKey.CTRL,  "Ctrl");
        addModifierButton(context, row, ModifierKey.ALT,   "Alt");

        addActionButton(context, row, ActionKey.DEL, "Del");
        addActionButton(context, row, ActionKey.ESC, "Esc");
        addActionButton(context, row, ActionKey.TAB, "Tab");

        addToggleButton(context, row, "123");

        addView(row);
        refreshAllVisuals();
    }

    private void addModifierButton(Context ctx, LinearLayout parent, ModifierKey key, String label) {
        MaterialButton b = makeButton(ctx, label);
        b.setOnClickListener(v -> handleModifierClick(key));
        modButtons.put(key, b);
        parent.addView(b, buttonLayoutParams());
    }

    private void addActionButton(Context ctx, LinearLayout parent, ActionKey key, String label) {
        MaterialButton b = makeButton(ctx, label);
        b.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onActionKeyClicked(key);
        });
        actionButtons.put(key, b);
        parent.addView(b, buttonLayoutParams());
    }

    private void addToggleButton(Context ctx, LinearLayout parent, String label) {
        MaterialButton b = makeButton(ctx, label);
        b.setOnClickListener(v -> {
            if (toggleListener != null) toggleListener.onExtraKeysToggleClicked();
        });
        toggleButton = b;
        parent.addView(b, buttonLayoutParams());
    }

    private static MaterialButton makeButton(Context ctx, String label) {
        MaterialButton b = new MaterialButton(ctx, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setCornerRadius(0);
        b.setPadding(0, 0, 0, 0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout.LayoutParams buttonLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            dp(56), LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(1), 0, dp(1), 0);
        return lp;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    /** Wire the callback for toggle-modifier state transitions. */
    public void setModifierStateListener(@Nullable ModifierStateListener l) {
        this.modListener = l;
    }

    /** Wire the callback for Del / Esc / Tab momentary clicks. */
    public void setActionKeyListener(@Nullable ActionKeyListener l) {
        this.actionListener = l;
    }

    /** Wire the callback for the 123 button (handler decides what it means). */
    public void setExtraKeysToggleListener(@Nullable ExtraKeysToggleListener l) {
        this.toggleListener = l;
    }

    public boolean isOn(@NonNull ModifierKey key) {
        RowState s = rowStates.get(key);
        return s != null && s.isOn;
    }

    public boolean isLocked(@NonNull ModifierKey key) {
        RowState s = rowStates.get(key);
        return s != null && s.isLocked;
    }

    /** True if the modifier is contributing to {@code onScreenMetaState}. */
    public boolean isOnOrLocked(@NonNull ModifierKey key) {
        RowState s = rowStates.get(key);
        return s != null && (s.isOn || s.isLocked);
    }

    /**
     * One-shot consumption: clears every ON (non-locked) modifier. Locked
     * modifiers remain active. Notifies the listener once if anything changed.
     */
    public void consumeOnModifiers() {
        boolean changed = false;
        for (RowState s : rowStates.values()) {
            if (s.isOn && !s.isLocked) {
                s.isOn = false;
                changed = true;
            }
        }
        if (changed) {
            refreshAllVisuals();
            if (modListener != null) modListener.onModifierStateChanged(this);
        }
    }

    /** Reset every toggle modifier to OFF. Called on session reconnect. */
    public void resetAll() {
        boolean changed = false;
        for (RowState s : rowStates.values()) {
            if (s.isOn || s.isLocked) {
                s.isOn = false;
                s.isLocked = false;
                changed = true;
            }
        }
        if (changed) {
            lastTappedKey = null;
            refreshAllVisuals();
            if (modListener != null) modListener.onModifierStateChanged(this);
        }
    }

    /** Drives the 123 button's visual highlighted state. */
    public void setExtraKeysToggleHighlighted(boolean on) {
        if (this.extraKeysHighlighted == on && toggleButton != null) return;
        this.extraKeysHighlighted = on;
        refreshToggleVisual();
    }

    private void handleModifierClick(ModifierKey key) {
        long now = SystemClock.uptimeMillis();
        boolean doubleTap = lastTappedKey == key && (now - lastTapTime) <= DOUBLE_TAP_WINDOW_MS;
        lastTappedKey = key;
        lastTapTime = now;

        RowState s = rowStates.get(key);
        if (s == null) return;

        // Snapshot whether this modifier contributed to onScreenMetaState before
        // the click so we can suppress the listener for state-machine transitions
        // that don't change the keyboard's view of the world (e.g. ON -> LOCKED).
        boolean wasActive = (s.isOn || s.isLocked);

        if (s.isLocked) {
            // LOCKED -> OFF
            s.isLocked = false;
            s.isOn = false;
        } else if (s.isOn && doubleTap) {
            // ON + double-tap -> LOCKED
            s.isLocked = true;
            // keep isOn true so isOnOrLocked() stays true across the bridge.
        } else if (s.isOn) {
            // ON + single tap -> OFF
            s.isOn = false;
        } else {
            // OFF -> ON (one-shot)
            s.isOn = true;
        }

        refreshAllVisuals();
        boolean isActive = (s.isOn || s.isLocked);
        if (modListener != null && wasActive != isActive) {
            modListener.onModifierStateChanged(this);
        }
    }

    private void refreshAllVisuals() {
        for (Map.Entry<ModifierKey, MaterialButton> e : modButtons.entrySet()) {
            MaterialButton b = e.getValue();
            RowState s = rowStates.get(e.getKey());
            int bg, fg;
            if (s.isLocked) {
                bg = COLOR_LOCK_BG;
                fg = COLOR_LOCK_FG;
            } else if (s.isOn) {
                bg = COLOR_ON_BG;
                fg = COLOR_ON_FG;
            } else {
                bg = COLOR_OFF_BG;
                fg = COLOR_OFF_FG;
            }
            b.setBackgroundColor(bg);
            b.setTextColor(fg);
        }
        for (MaterialButton b : actionButtons.values()) {
            b.setBackgroundColor(COLOR_OFF_BG);
            b.setTextColor(COLOR_OFF_FG);
        }
        refreshToggleVisual();
    }

    private void refreshToggleVisual() {
        if (toggleButton == null) return;
        if (extraKeysHighlighted) {
            toggleButton.setBackgroundColor(COLOR_TOGGLE_ON_BG);
            toggleButton.setTextColor(COLOR_TOGGLE_ON_FG);
        } else {
            toggleButton.setBackgroundColor(COLOR_TOGGLE_OFF_BG);
            toggleButton.setTextColor(COLOR_TOGGLE_OFF_FG);
        }
    }
}
