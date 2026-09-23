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
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.iiordanov.bVNC.input.RemoteKeyboard;

import org.json.JSONException;

/**
 * Extra-keys grid panel for the RDP flavor's modifier row. Hosts an
 * {@link ExtraKeysView} configured with F1-F12 plus navigation keys, and
 * routes button clicks through {@link RemoteExtraKeysHandler} so the existing
 * modifier-consumption and metastate-injection logic is reused unchanged.
 *
 * <p>The grid is a child of the rdp_input_area.xml layout and starts hidden
 * (visibility GONE). The {@link RdpModifierRowHandler} toggles visibility in
 * response to the 123 button or to {@code onInputAreaStateChanged(EXTRA)}.
 */
public class RdpExtraGridPanel extends LinearLayout {

    private static final String TAG = "RdpExtraGridPanel";

    /**
     * Four-row, nine-column grid that mirrors the Microsoft RDP "123" extra-keys layout:
     *   Row 1: F1, F2, F3, mouse icon, backspace, /, Home, ▲, PgUp
     *   Row 2: F4, F5, F6, screenshot icon, enter, *, ◄, (empty), ►
     *   Row 3: F7, F8, F9, (empty), (empty), -, End, ▼, PgDn
     *   Row 4: F10, F11, F12, "Number Keys" (spans 2 cols), +, Insert, Delete, Enter
     *
     * <p>Cells whose key is the empty string render as gaps (see
     * {@link ExtraKeysView#reload}). The "Number Keys" cell uses {@code span:2} so the
     * {@link android.widget.GridLayout} gives it the width of two columns. The mouse and
     * screenshot cells reference drawable resources that {@link ExtraKeyButton#getIconResId}
     * resolves at render time; if the resource is missing, the cell renders as text-only.
     */
    private static final String GRID_JSON =
        "[" +
        // Row 1: F-keys | mouse | BKSP | arithmetic | navigation (text, up-arrow, PgUp)
        "[\"F1\",\"F2\",\"F3\",{\"key\":\"MOUSE\",\"icon\":\"mouse_icon\"},\"BKSP\",\"/\",\"HOME\",{\"key\":\"UP\",\"display\":\"\\u25B2\"},\"PGUP\"]," +
        // Row 2: F-keys | screenshot | enter | arithmetic | navigation (left, empty, right)
        "[\"F4\",\"F5\",\"F6\",{\"key\":\"SCREENSHOT\",\"icon\":\"ic_screen_black_48dp\"},\"ENTER\",\"*\",{\"key\":\"LEFT\",\"display\":\"\\u25C0\"},\"\",{\"key\":\"RIGHT\",\"display\":\"\\u25B6\"}]," +
        // Row 3: F-keys | (empty), (empty) | arithmetic | navigation (End, down-arrow, PgDn)
        "[\"F7\",\"F8\",\"F9\",\"\",\"\",\"-\",\"END\",{\"key\":\"DOWN\",\"display\":\"\\u25BC\"},\"PGDN\"]," +
        // Row 4: F-keys | Number Keys (wide, span:2) | arithmetic | Insert, Delete, Enter
        // Note: the empty string after NUMLOCK is required as a column placeholder so that the "+"
        // button lands at the correct grid column. ExtraKeysView skips rendering the empty cell.
        "[\"F10\",\"F11\",\"F12\",{\"key\":\"NUMLOCK\",\"display\":\"Number Keys\",\"span\":2},\"\",\"+\",\"INS\",\"DEL\",{\"key\":\"ENTER\",\"display\":\"Enter\"}]" +
        "]";

    private ExtraKeysView extraKeysView;

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
        try {
            ExtraKeysInfo info = new ExtraKeysInfo(
                GRID_JSON, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            extraKeysView.reload(info, 0);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build RDP extra-keys grid", e);
            // Hide the whole grid panel so a broken grid never appears
            // blank above the IME; the modifier row above stays usable.
            setVisibility(GONE);
        }
        // Propagate the parent's height to the GridLayout so its FILL rowSpecs
        // can distribute the 192dp panel across rows. With the default
        // WRAP_CONTENT the GridLayout collapses (reload() sets per-row
        // height=0 on non-Lollipop), which renders as an empty slab.
        addView(extraKeysView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * Wires the grid's button clicks to the supplied {@link RemoteKeyboard}.
     * Called by {@link RdpModifierRowHandler} once the keyboard is available;
     * a {@code null} keyboard (e.g. before the connection finishes) is ignored
     * — the grid will simply be inert until wired.
     */
    public void setRemoteKeyboard(@Nullable RemoteKeyboard keyboard) {
        if (extraKeysView == null) return;
        if (keyboard == null) {
            extraKeysView.setExtraKeysViewClient(null);
            return;
        }
        extraKeysView.setExtraKeysViewClient(new RemoteExtraKeysHandler(extraKeysView, keyboard));
    }
}
