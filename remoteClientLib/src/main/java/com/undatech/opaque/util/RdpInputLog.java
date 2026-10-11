package com.undatech.opaque.util;

import android.util.Log;
import android.view.KeyEvent;

import com.undatech.opaque.input.InputDownTracker;

import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end diagnostic logging for the RDP input pipeline.
 *
 * <p>When {@link #enabled} is true, every stage of a keyboard / pointer event is logged
 * under the single stable tag {@code RDPInput}, so the whole pipeline can be captured with
 * <pre>adb logcat -s RDPInput</pre>
 *
 * <p>Each line is prefixed with a process-wide monotonic sequence number. Logcat gives no
 * ordering guarantee between the UI / hardware-key thread and the single
 * {@code SendRdpInputThread}, so the sequence number plus the per-event
 * {@code SystemClock.uptimeMillis()} timestamps are what make the interleaving readable.
 *
 * <p>Stages:
 * <ul>
 *   <li>{@code KEY enter} — a key event entered {@code RemoteRdpKeyboard.processLocalKeyEvent}.</li>
 *   <li>{@code KEY drop} — the event was consumed before reaching the wire (silent-skip suspect).</li>
 *   <li>{@code KEY ime} — an IME-committed character batch was split for dispatch.</li>
 *   <li>{@code POINT enter} — a pointer event entered {@code RemoteRdpPointer.sendPointerEvent}.</li>
 *   <li>{@code WIRE ...} — the event reached the native boundary; {@code queued} is the time
 *       the task spent waiting in the single-thread executor before being sent. Backlog here
 *       is the prime suspect for "laggy / delayed" input.</li>
 *   <li>{@code WARN ...} — down/up pairing anomalies and the close-time leak dump. A leaked
 *       key/button (DOWN sent, UP never reached the wire) is the reported "stuck key" symptom.</li>
 * </ul>
 *
 * <p>When disabled this costs one volatile read per event and never builds a message string.
 *
 * <p>Toggle: RDP-only setting {@code rdpInputDiagnostics} (see {@code global_preferences_rdp.xml}).
 *
 * <p>Enabling mid-session starts the held-key tracking from empty, so a key/button already held
 * at that instant can produce one spurious pairing warning when it is finally released.
 */
public final class RdpInputLog {
    public static final String TAG = "RDPInput";

    /** Process-wide switch. Written at app start and from the Settings change listener. */
    public static volatile boolean enabled = false;

    private static final AtomicLong SEQ = new AtomicLong();

    private RdpInputLog() {
    }

    private static long seq() {
        return SEQ.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Entry stage (UI / hardware-key thread)
    // ------------------------------------------------------------------

    public static void keyEntry(int keyCode, int action, int metaState, int onScreenMetaState,
                                int repeatCount, long eventTime) {
        if (!enabled) return;
        Log.d(TAG, seq() + " KEY   enter kc=" + keyCode + " act=" + actionName(action)
                + " meta=" + hex(metaState) + " onScreen=" + hex(onScreenMetaState)
                + " repeat=" + repeatCount + " eventTime=" + eventTime);
    }

    public static void keyDrop(String reason, int keyCode, int action) {
        if (!enabled) return;
        Log.d(TAG, seq() + " KEY   drop reason=" + reason + " kc=" + keyCode
                + " act=" + actionName(action));
    }

    public static void keyChars(int count) {
        if (!enabled) return;
        // Count only — the committed characters can include typed passwords/OTPs, and the
        // wire stage already records exactly what was sent.
        Log.d(TAG, seq() + " KEY   ime chars=" + count);
    }

    public static void pointerEntry(int x, int y, int pointerMask, int prevPointerMask,
                                    int metaState, boolean moving) {
        if (!enabled) return;
        Log.d(TAG, seq() + " POINT enter x=" + x + " y=" + y + " mask=" + hex(pointerMask)
                + " prev=" + hex(prevPointerMask) + " meta=" + hex(metaState)
                + " moving=" + moving);
    }

    // ------------------------------------------------------------------
    // Wire stage (SendRdpInputThread)
    // ------------------------------------------------------------------

    public static void wireKey(int vk, boolean down, long queuedMs) {
        if (!enabled) return;
        Log.d(TAG, seq() + " WIRE  key   vk=" + hex(vk) + " down=" + down
                + " queued=" + queuedMs + "ms");
    }

    public static void wireUnicode(int codePoint, boolean down, long queuedMs) {
        if (!enabled) return;
        Log.d(TAG, seq() + " WIRE  uni   cp=" + codePoint + " down=" + down
                + " queued=" + queuedMs + "ms");
    }

    public static void wireMouse(int x, int y, int pointerMask, long queuedMs) {
        if (!enabled) return;
        Log.d(TAG, seq() + " WIRE  mouse x=" + x + " y=" + y + " mask=" + hex(pointerMask)
                + " queued=" + queuedMs + "ms");
    }

    // ------------------------------------------------------------------
    // Anomalies
    // ------------------------------------------------------------------

    /** Emit a warning line (no-op when diagnostics are disabled). */
    public static void warn(String message) {
        if (!enabled) return;
        Log.w(TAG, seq() + " WARN  " + message);
    }

    /** An UP was sent for an id that was never marked held on the wire (a skipped/lost DOWN). */
    public static void warnOrphanUp(String kind, int id) {
        if (!enabled) return;
        warn("orphan UP " + kind + " id=" + hex(id) + " (not held)");
    }

    /**
     * Dump every id still marked held at disconnect. Submitted as a task on the single input
     * thread, so it observes the trackers after every queued event has drained. A non-empty
     * result is the definitive "a DOWN was sent without its matching UP" (stuck key) signal.
     */
    public static void dumpLeaks(InputDownTracker keyTracker, InputDownTracker unicodeTracker) {
        if (!enabled) return;
        dumpOne("vk", keyTracker);
        dumpOne("unicode", unicodeTracker);
    }

    private static void dumpOne(String kind, InputDownTracker tracker) {
        int[] ids = tracker.drain();
        if (ids.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            sb.append(hex(id)).append(' ');
        }
        warn("LEAK at disconnect: " + ids.length + " " + kind + "(s) still held: "
                + sb.toString().trim());
    }

    private static String actionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "DOWN";
            case KeyEvent.ACTION_UP:
                return "UP";
            case 2: // KeyEvent.ACTION_MULTIPLE (constant deprecated since API 29)
                return "MULTIPLE";
            default:
                return "a" + action;
        }
    }

    private static String hex(int v) {
        return "0x" + Integer.toHexString(v);
    }
}
