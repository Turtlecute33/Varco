# Varco — design

## Fixed decisions

| Area | Decision |
|---|---|
| Platform | Kotlin, Jetpack Compose, Material 3 Expressive, minSdk 35, target/compile 37 |
| Release | Sideload + GitHub. Public repo, GPL-3.0. |
| SSH | mwiede JSch + Bouncy Castle (lightweight classes only, no provider registration). |
| Terminal | Termux `terminal-emulator` + `terminal-view` v0.118.3, copied into `:terminal` and changed for remote byte streams (see `terminal/NOTICE.md`). |
| YubiKey | YubiKit `android` + `piv`. PIV only. |
| Storage | One file, `store.bin`: JSON encrypted with AES-256-GCM by a Keystore key (`Vault`). No database. |
| Not in v1 | Port forwarding, jump hosts, Mosh, agent forwarding, tmux auto-attach, FIDO2 security keys, per-server themes. |

## Modules and packages

- `:terminal` — Termux engine. `TerminalSession` couples the emulator to a `Transport` (typed input, size
  changes) and takes remote output through `feed()`. A session can change its transport, so a reconnect
  keeps the screen and the scrollback.
- `app.varco.data` — `AppState` (servers, keys, snippets, trusted host keys, imported known_hosts lines,
  colour schemes, settings), `Store` (encrypted file, writes grouped within 300 ms), `Vault`.
- `app.varco.ssh` — `SshConnection` (one login: algorithms, auth, host keys), `Shell` (one terminal tab),
  `Sessions` (all tabs, in the app process), `Sftp`, `KnownHosts`, `Prompts` (questions from connection
  threads to the UI).
- `app.varco.keys` — `ChipKeys` (Keystore: make, import, sign), `Piv` (YubiKey), `SshWire` (SSH encoding),
  `OpenSshKey` (reads unencrypted `openssh-key-v1`).
- `app.varco.importer` — `Importer` (detects file type by content), `SshConfig`, `TermuxColors`.
- `app.varco.service.SessionService` — `specialUse` foreground service while a tab is open. The
  notification shows only the number of sessions.
- `app.varco.ui` — screens (navigation3), dialogs for prompts, app lock, theme from the terminal scheme.

## Security rules

- Host keys: Varco's own list first. A key of the same type that differs is "changed" and needs an explicit
  "Replace key". Lines imported from `known_hosts` are used only when Varco has no entry of that type;
  `@revoked` lines block a key. No host certificates are negotiated.
- Private keys: Keystore keys sign inside the hardware (`Signature` with the Keystore key). YubiKey keys sign
  on the YubiKey after a check that the slot holds the expected public key, and only then is the PIN sent.
  Imported keys that the Keystore cannot hold are stored in the encrypted store (kind `FILE`).
- Keystore keys need an unlocked device. Keys with "fingerprint for each use" are bound to one operation:
  the connection thread calls `initSign`, the UI shows BiometricPrompt with that `Signature`, then the thread signs.
- Ed25519 import: Android takes Ed25519 imports only as algorithm "EC" with an `EdECPrivateKey`, and
  `KeyStore.PrivateKeyEntry` needs the same algorithm name on the certificate key. `ChipKeys.EcNamed` wraps
  the real self-signed Ed25519 certificate and only renames the key algorithm.
- No logs of terminal data, the Termux engine's log calls are dropped. Terminal copies are marked sensitive and
  cleared after 60 s (`SensitiveClip`, the same mechanism as Ombra).
  OSC 52 from servers is off by default. The keyboard gets `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- `allowBackup=false` and data-extraction rules exclude everything.

## Tests

- JVM: `./gradlew :app:testDebugUnitTest` (config, colours, known_hosts, key formats, PIV padding, store codec).
- Live test (2026-10-17, API 35 emulator against OpenSSH 10.3 on localhost): Keystore Ed25519 key, host key
  change by key-type switch (MITM case), prompt closed with its session, SFTP with symlinks and upload,
  imported Ed25519 (with passphrase) / RSA-3072 / ECDSA keys, per-use auth with device PIN, app lock,
  host-key prompt, reconnect, vim, truecolor, extra keys, snippets, SFTP list and download, Termux import,
  release build with R8. Negotiated: `mlkem768x25519-sha256`, `chacha20-poly1305@openssh.com`.
- Not testable on the emulator: YubiKey over NFC/USB, StrongBox, pinch zoom.
