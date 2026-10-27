# Terminal engine

The code in this module comes from [termux-app](https://github.com/termux/termux-app) tag `v0.118.3`
(commit `5b657c6adf4304e5198951ce815fe0205dcac29c`), modules `terminal-emulator` and `terminal-view`.

Licences: termux-app is released under GPLv3 only. Parts of these modules come from jackpal's
"Terminal Emulator for Android" (Apache License 2.0). Varco is GPL-3.0, so both apply here. The licence
texts are in `LICENSE` (GPL-3.0) and `docs/licences/Apache-2.0.txt`. In the app, Settings > Open-source
licences shows the Apache text and refers to the GPL.

Changed for Varco, 2026-10:

- `TerminalSession` no longer starts a local process (JNI, pty) and has no exit handling. It couples the
  emulator to a remote byte stream: `feed()` for remote output, a `Transport` for typed input and size
  changes. A session can change its transport, so the screen and scrollback stay after a reconnect.
- `JNI.java` and the native code are removed.
- The output queue is 64 KiB (upstream 4 KiB), for faster output over SSH.
- Typed input goes through `InputQueue`, not a blocking `ByteQueue`: `write()` never blocks the main thread when
  the connection stalls. Input over 4 MiB that waits is dropped, and the terminal shows a short notice.
- `TerminalSessionClient.onCopyTextToClipboard` tells server copies (OSC 52) from user copies, so that the
  app can block the server from setting the clipboard. `onSessionFinished` is removed.
- `TerminalView` asks the keyboard not to learn typed text (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- `TerminalView` is marked accessibility-data-sensitive, so that only accessibility tools can read the screen text.
- `TerminalEmulator.mCellWidthPixels` / `mCellHeightPixels` are package-visible.
- XTGETTCAP: a request name that is not hex gets no reply, so server bytes never come back as typed input.
- `TerminalView.commitText()` sends a keyboard paste (more than one character, with a line break or ESC)
  through `TerminalEmulator.paste()`, as the menu paste: ESC and C1 are removed, and bracketed paste is used when the shell asks for it.
- Fixes from termux-app master: `30ebb2dee3` (the termcap names `kN` and `kP` give Page Down and Page Up),
  `47c473f9be` (an erase to the end of a line clears its line wrap), `5532cc4f88` (`TerminalRow.mSpaceUsed` is an `int`).
- `TerminalBuffer.resize()` with a column change makes only the screen rows, not a row for each transcript line.
