# BUG-005 — Unmatched unicode key DOWN (host-side stuck key) when `preferSendingUnicode` is off

**Status: OPEN (latent — reachable only with the non-default `preferSendingUnicode=false` setting).**
Discovered during a code review of the new RDP input diagnostics (`RdpInputLog`, log tag `RDPInput`); not reproducible with the default configuration.

## Summary

With the per-connection setting `preferSendingUnicode` disabled, a key event whose
`getVirtualKeyCode()` is `0` but whose `getUnicodeChar()` is non-zero is sent to the host as a
unicode **DOWN only** — the matching **UP** is never sent, so the remote host treats the
character as held down (a stuck key). This is exactly the class of defect the new `RDPInput`
diagnostics are meant to surface: the close-time leak dump reports the code point as still held.

## Location

| File | Line | What is there |
|---|---|---|
| `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java` | `509-514` (ACTION_DOWN) | `vkcode==0 && event.getUnicodeChar()!=0` → `listener.processUnicodeKey(event.getUnicodeChar(), true, suppressMetaState)` — sends only the DOWN. |
| `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java` | `474-484` (ACTION_UP) | Handles the UNICODE flag, the SHIFT flag, and `vkcode > 0`; a `vkcode==0` UP falls through and sends nothing. |

## Why it is latent

With the default `preferSendingUnicode=true`, the rewrite at `RdpKeyboardMapper.java:469-471`
(`vkcode = unicode | KEY_FLAG_UNICODE` for any non-special key with a unicode char) makes DOWN
and UP symmetric, so this path is unreachable. Only `preferSendingUnicode=false` reaches it, and
then only for keycodes absent from `keymapAndroid` that still map to a character (in practice
hardware-numpad digits and exotic OEM keycodes).

## Fix (suggested, NOT applied)

Mirror the DOWN in the ACTION_UP chain:

```java
} else if (vkcode == 0 && event.getUnicodeChar() != 0) {
    listener.processUnicodeKey(event.getUnicodeChar(), false, suppressMetaState);
} else if (vkcode > 0 && !isRepeat) {
    listener.processVirtualKey(vkcode, false);
}
```

## Impact

- **Severity: Low (latent).** Not reachable with the default configuration; affects only users
  who disable `preferSendingUnicode`. When it does fire it is a genuine host-side stuck key.
- **Behavioral surface:** RDP input path only.

## Testing

Disable `preferSendingUnicode` for a connection, enable the `rdpInputDiagnostics` setting,
connect to an RDP host, and press an affected key.

- **Expected after a fix:** the `RDPInput` WIRE log shows a matched `uni` DOWN/UP pair and the
  close-time dump reports no leak.
- Note: the `WARN orphan UP` line does not fire for this path (the defect is DOWN-without-UP, so
  it is caught by the close-time leak dump instead).

## Related

- `BUG-004` (click-and-drag broken on Linux/xrdp) — same Linux-host "stuck input" family,
  different mechanism.
- The new `RdpInputLog` diagnostics (tag `RDPInput`) were added to make exactly this class of
  issue diagnosable.
