package com.undatech.opaque.input;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks the set of RDP wire "keys" (virtual key codes, unicode code points, or mouse
 * button masks) currently held down. Used by the input diagnostics to surface the reported
 * "stuck key" symptom: a DOWN that reached the wire without its matching UP.
 *
 * <p><b>Thread confinement:</b> instances are only ever touched from
 * {@code RdpCommunicator}'s single {@code SendRdpInputThread}. This class is deliberately
 * not thread-safe.
 *
 * <p>Pure logic, no Android dependency — unit-tested in {@code InputDownTrackerTest}.
 */
public final class InputDownTracker {
    private final Set<Integer> down = new LinkedHashSet<>();

    /**
     * Record a DOWN for {@code id}. Idempotent — re-recording an already-held id is a no-op,
     * because key auto-repeat legitimately re-sends the DOWN without an intervening UP.
     */
    public void markDown(int id) {
        down.add(id);
    }

    /**
     * Record an UP for {@code id}.
     *
     * @return {@code true} if {@code id} was not currently marked down (an orphan UP that
     *         the caller should warn about), {@code false} on a normal UP.
     */
    public boolean markUp(int id) {
        return !down.remove(id);
    }

    /**
     * Return and clear every id still marked down. Called at disconnect to report leaked
     * (stuck) keys/buttons.
     */
    public int[] drain() {
        int[] ids = new int[down.size()];
        int i = 0;
        for (int id : down) {
            ids[i++] = id;
        }
        down.clear();
        return ids;
    }

}
