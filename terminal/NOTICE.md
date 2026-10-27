# Terminal engine

The code in this module comes from [termux-app](https://github.com/termux/termux-app) tag `v0.118.3`
(commit `5b657c6adf4304e5198951ce815fe0205dcac29c`), modules `terminal-emulator` and `terminal-view`.

Licences: termux-app is released under GPLv3 only. Parts of these modules come from jackpal's
"Terminal Emulator for Android" (Apache License 2.0). Varco is GPL-3.0, so both apply here.

Changes made for Varco:

- `TerminalSession` no longer starts a local process (JNI, pty) and has no exit handling. It couples the
  emulator to a remote byte stream: `feed()` for remote output, a `Transport` for typed input and size
  changes. A session can change its transport, so the screen and scrollback stay after a reconnect.
- `JNI.java` and the native code are removed.
- Queues are 64 KiB (upstream 4 KiB), for faster output over SSH.
- `TerminalSessionClient.onCopyTextToClipboard` tells server copies (OSC 52) from user copies, so that the
  app can block the server from setting the clipboard. `onSessionFinished` is removed.
- `TerminalView` asks the keyboard not to learn typed text (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- `TerminalView` is marked accessibility-data-sensitive, so that only accessibility tools can read the screen text.
- `TerminalEmulator.mCellWidthPixels` / `mCellHeightPixels` are package-visible.
- XTGETTCAP: a request name that is not hex gets no reply, so server bytes never come back as typed input.
