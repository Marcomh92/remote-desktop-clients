package com.iiordanov.bVNC.input;

/**
 * Tracks which on-screen input surface (if any) is currently active for an
 * RDP session. The session-scoped state machine is owned by
 * {@link com.iiordanov.bVNC.RemoteCanvasActivity}; this enum only enumerates
 * the three valid states.
 */
public enum InputAreaState {
    /** No input area visible, software keyboard hidden. */
    NONE,
    /** Software IME visible with the modifier-key row shown above it. */
    KEYBOARD,
    /** "Extra keys" grid visible (replaces the IME) with the modifier-key row shown above it. */
    EXTRA
}
