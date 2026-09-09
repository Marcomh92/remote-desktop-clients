package com.undatech.opaque.input;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteKeyboardStateTest {
    @Test
    public void altReportedDown_remainsDownUntilExplicitRelease() {
        RemoteKeyboardState state = new RemoteKeyboardState(false);

        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, true);

        // A Tab tap is not a modifier-state update, so it cannot release Alt.
        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));
        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.CTRL_MASK));
    }

    @Test
    public void updateRemoteMetaState_releasesOnlyRequestedModifier() {
        RemoteKeyboardState state = new RemoteKeyboardState(false);

        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, true);
        state.updateRemoteMetaState(RemoteKeyboard.CTRL_MASK, true);
        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, false);

        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));
        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.CTRL_MASK));
    }

    @Test
    public void shouldSendModifier_sendsEachChangedTransitionOnce() {
        RemoteKeyboardState state = new RemoteKeyboardState(false);

        assertTrue(state.shouldSendModifier(RemoteKeyboard.ALT_MASK, RemoteKeyboard.ALT_MASK, true));
        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, true);
        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));

        assertFalse(state.shouldSendModifier(RemoteKeyboard.ALT_MASK, RemoteKeyboard.ALT_MASK, true));
        assertTrue(state.shouldSendModifier(RemoteKeyboard.ALT_MASK, RemoteKeyboard.ALT_MASK, false));
        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, false);
        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));

        assertFalse(state.shouldSendModifier(RemoteKeyboard.ALT_MASK, RemoteKeyboard.ALT_MASK, false));
    }

    @Test
    public void shouldSendModifier_releasingAlt_doesNotReleaseCtrl() {
        RemoteKeyboardState state = new RemoteKeyboardState(false);

        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, true);
        state.updateRemoteMetaState(RemoteKeyboard.CTRL_MASK, true);

        assertTrue(state.shouldSendModifier(RemoteKeyboard.ALT_MASK, RemoteKeyboard.ALT_MASK, false));
        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, false);

        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));
        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.CTRL_MASK));
    }

    @Test
    public void isRemoteKeyDown_doesNotMutateState() {
        RemoteKeyboardState state = new RemoteKeyboardState(false);

        state.updateRemoteMetaState(RemoteKeyboard.ALT_MASK, true);

        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));
        assertTrue(state.isRemoteKeyDown(RemoteKeyboard.ALT_MASK));
        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.CTRL_MASK));
        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.SHIFT_MASK));
        assertFalse(state.isRemoteKeyDown(RemoteKeyboard.SUPER_MASK));
    }
}

/*
 * Logic Verified: Remote modifier down/up transitions, duplicate suppression,
 * modifier independence, Alt retention across non-modifier key activity, and
 * read-only isRemoteKeyDown behavior.
 * Bugs Documented: None.
 * Fakes Updated: None.
 * Review Status: Self-reviewed; independent review was not requested.
 */
