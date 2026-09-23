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
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.iiordanov.bVNC.input.RemoteKeyboard;

import org.json.JSONException;

/**
 * Extra-keys grid panel for the RDP flavor's modifier row. Hosts an
 * {@link ExtraKeysView} configured with F1-F12 plus navigation keys, and
 * routes button clicks through {@link RemoteExtraKeysHandler} so the existing
 * modifier-consumption and metastate-injection logic is reused unchanged.
 *
 * <p>The panel itself implements {@link ExtraKeysView.IExtraKeysView} so it can intercept
 * the "Number Keys" / "Special keys" toggle button. All other keys delegate to the inner
 * {@link RemoteExtraKeysHandler}. Intercepting in the panel (rather than in
 * {@code RemoteExtraKeysHandler}) keeps the RDP-specific state here and avoids growing the
 * generic handler's API surface.
 *
 * <p>The grid is a child of the {@code rdp_input_area.xml} layout and starts hidden
 * (visibility GONE). The {@link RdpModifierRowHandler} toggles visibility in
 * response to the 123 button or to {@code onInputAreaStateChanged(EXTRA)}.
 */
public class RdpExtraGridPanel extends LinearLayout implements ExtraKeysView.IExtraKeysView {

    private static final String TAG = "RdpExtraGridPanel";

    /** Display text on the Number Keys / Special keys toggle cell in special-keys mode. */
    private static final String TOGGLE_LABEL_SPECIAL = "Number Keys";

    /** Display text on the Number Keys / Special keys toggle cell in numpad mode. */
    private static final String TOGGLE_LABEL_NUMPAD = "Special keys";

    /** Right section (cols 7-9) in special-keys mode, one entry per row. Mirrors Microsoft RDP's
     *  extra-keys grid. Col 2 of row 2 is intentionally empty so ◄/► form a diamond with ▲/▼. */
    private static final String[] RIGHT_ROW_SPECIAL = {
        "\"HOME\",{\"key\":\"UP\",\"display\":\"\\u25B2\"},\"PGUP\"",          // row 1
        "{\"key\":\"LEFT\",\"display\":\"\\u25C0\"},\"\",{\"key\":\"RIGHT\",\"display\":\"\\u25B6\"}", // row 2
        "\"END\",{\"key\":\"DOWN\",\"display\":\"\\u25BC\"},\"PGDN\"",          // row 3
        "\"INS\",\"DEL\",{\"key\":\"ENTER\",\"display\":\"Enter\"}"            // row 4
    };

    /** Right section (cols 7-9) in numpad mode, one entry per row. Literal-char dispatch in
     *  {@link RemoteExtraKeysHandler#sendKey} sends 1-9, 0, "." via
     *  {@link android.view.KeyCharacterMap#FULL}, and "ENTER" resolves to {@code KEYCODE_ENTER}. */
    private static final String[] RIGHT_ROW_NUMPAD = {
        "\"1\",\"2\",\"3\"",  // row 1
        "\"4\",\"5\",\"6\"",  // row 2
        "\"7\",\"8\",\"9\"",  // row 3
        "\"0\",\".\",\"ENTER\"" // row 4
    };


    private ExtraKeysView extraKeysView;

    /** Inner client that dispatches non-toggle button clicks to the remote session. */
    @Nullable
    private RemoteExtraKeysHandler delegate;

    /** Tracks which mode the right section is in. Flipped by the Number Keys toggle button. */
    private boolean numpadShown = false;

    public RdpExtraGridPanel(Context context) {
        super(context);
        init(context);
    }

    public RdpExtraGridPanel(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RdpExtraGridPanel(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        extraKeysView = new ExtraKeysView(context, null);
        // Enable RDP-only button styling (2dp per-side margins + 1.5× height-to-width ratio).
        // Must precede reload() so the first reload pass applies the margins.
        extraKeysView.setRdpGridMode(true);
        rebuildGrid();
        addView(extraKeysView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * Override the XML's fixed panel height (192dp) with the value required for 4 rows of
     * 1.5x-cellWidth buttons. The XML value was sized for the legacy 3-row layout; with the
     * 4-row 1.5x ratio the panel needs ~ (2/3) * screen_width + ~16dp of vertical margins,
     * otherwise row 1 fills the panel and rows 2-4 overflow below it.
     *
     * <p>Overriding {@code setLayoutParams} (rather than computing in {@code init}) is required
     * because during XML inflation the constructor runs before {@code setLayoutParams} is
     * called with the XML-derived LayoutParams — a {@code getLayoutParams()} call from inside
     * {@code init()} returns null and any dynamic adjustment would be silently dropped.
     * Intercepting the inflation-time call lets us apply the dynamic height once and have it
     * stick. Skips WRAP_CONTENT / MATCH_PARENT (negative sentinel values) so callers that
     * want auto-sizing are not overridden.
     */
    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        if (params != null && params.height > 0) {
            float density = getResources().getDisplayMetrics().density;
            int widthPx = getResources().getDisplayMetrics().widthPixels;
            int marginPx = (int) (2 * density + 0.5f); // matches ExtraKeysView.CELL_MARGIN_DP
            int cellWidthPx = (widthPx - 9 * 2 * marginPx) / 9;
            int buttonHeightPx = (int) (cellWidthPx * 1.5f + 0.5f);
            params.height = 4 * buttonHeightPx + 4 * 2 * marginPx;
        }
        super.setLayoutParams(params);
    }

    /**
     * (Re)builds the grid from the JSON corresponding to {@link #numpadShown}. The toggle
     * cell label is set from {@link #TOGGLE_LABEL_NUMPAD} or {@link #TOGGLE_LABEL_SPECIAL}
     * depending on mode. Any JSON parse failure hides the panel so a broken grid never
     * appears blank above the IME; the modifier row above stays usable.
     */
    private void rebuildGrid() {
        if (extraKeysView == null) return;
        String[] right = numpadShown ? RIGHT_ROW_NUMPAD : RIGHT_ROW_SPECIAL;
        String toggleLabel = numpadShown ? TOGGLE_LABEL_NUMPAD : TOGGLE_LABEL_SPECIAL;
        // Built directly to avoid String.format() over-replacing %s in nested JSON literals —
        // LEFT_SECTION_TEMPLATE has its own %s for the toggle label and was being filled
        // with a 12-cell right section per row, doubling cells (round 12 bug).
        StringBuilder sb = new StringBuilder("[");
        sb.append("[\"F1\",\"F2\",\"F3\",\"\",\"BKSP\",\"/\",").append(right[0]).append("],");
        sb.append("[\"F4\",\"F5\",\"F6\",{\"key\":\"SCREENSHOT\",\"icon\":\"ic_screen_black_48dp\"},\"ENTER\",\"*\",").append(right[1]).append("],");
        sb.append("[\"F7\",\"F8\",\"F9\",\"\",\"\",\"-\",").append(right[2]).append("],");
        sb.append("[\"F10\",\"F11\",\"F12\",{\"key\":\"NUMLOCK\",\"display\":\"").append(toggleLabel)
          .append("\",\"span\":2},\"\",\"+\",").append(right[3]).append("]");
        sb.append("]");
        try {
            ExtraKeysInfo info = new ExtraKeysInfo(
                sb.toString(), "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            extraKeysView.reload(info, 0);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build RDP extra-keys grid", e);
            setVisibility(GONE);
        }
    }

    /**
     * Wires the grid's button clicks to the supplied {@link RemoteKeyboard}.
     * Called by {@link RdpModifierRowHandler} once the keyboard is available;
     * a {@code null} keyboard (e.g. before the connection finishes) is ignored
     * — the grid will simply be inert until wired.
     *
     * <p>The panel installs itself as the {@link ExtraKeysView} client so it can intercept
     * the Number Keys toggle button (see {@link #onExtraKeyButtonClick}). All other keys
     * delegate to the inner {@link RemoteExtraKeysHandler}.
     */
    public void setRemoteKeyboard(@Nullable RemoteKeyboard keyboard) {
        if (extraKeysView == null) return;
        if (keyboard == null) {
            extraKeysView.setExtraKeysViewClient(null);
            delegate = null;
            return;
        }
        delegate = new RemoteExtraKeysHandler(extraKeysView, keyboard);
        extraKeysView.setExtraKeysViewClient(this);
    }

    @Override
    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        // Intercept the Number Keys toggle. NUMLK is the post-alias key (see
        // CONTROL_CHARS_ALIASES: NUMLOCK -> NUMLK) — without this intercept the literal-char
        // dispatch path would type "NUMLK" into a focused text field. The toggle itself is
        // excluded from PRIMARY_REPETITIVE_KEYS so long-press does not flicker the panel.
        if ("NUMLK".equals(buttonInfo.getKey())) {
            toggleNumpad();
            return;
        }
        if (delegate != null) {
            delegate.onExtraKeyButtonClick(view, buttonInfo, button);
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        // Returning false lets ExtraKeysView fall through to its system-settings-based haptic
        // so the toggle still gets a tap feedback under the same conditions as normal keys.
        return false;
    }

    /** Flips {@link #numpadShown} and rebuilds the grid so the right section swaps modes. */
    private void toggleNumpad() {
        numpadShown = !numpadShown;
        rebuildGrid();
    }
}
