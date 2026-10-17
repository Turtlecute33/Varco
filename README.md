# Varco

A private, minimal SSH client and terminal for Android.

- **Terminal:** the Termux terminal engine (true colour, mouse, vim, htop, tmux), full screen, with an
  extra-keys bar (ESC, TAB, CTRL, ALT, arrows, `- / |`, snippets). Pinch to zoom.
- **Modern SSH:** post-quantum key exchange first (`mlkem768x25519-sha256`, `sntrup761x25519-sha512`),
  then `curve25519`. Ciphers: `chacha20-poly1305`, AES-GCM, AES-CTR. No SHA-1, no CBC.
  Strict host key check: a new or changed host key needs your yes.
- **Keys in the secure chip:** new keys are made in the phone's Keystore (StrongBox or TEE) and can never
  be copied out, also not by Varco. Imported keys move into the chip when the phone can hold their type.
  Optional: fingerprint or screen lock on each use.
- **YubiKey:** PIV keys (RSA, ECDSA P-256, Ed25519 on firmware 5.7+) over NFC and USB-C, with the PIN.
- **Import from Termux:** private keys (also with passphrase), `~/.ssh/config`, `known_hosts`,
  `~/.termux/colors.properties` and `~/.termux/font.ttf`, straight from Termux's home folder.
- **Saved servers, snippets, SFTP file browser, 13 colour schemes** (Catppuccin, Tokyo Night, Dracula,
  Nord, Gruvbox, One Dark, Rosé Pine, Solarized, Termux). The app colours follow the terminal scheme.
- **Privacy:** no analytics, no network use except SSH, no backups. All data is in one file, encrypted with
  a Keystore key. Screenshots and the Recents preview are blocked by default. The keyboard does not learn
  what you type. Servers cannot set the clipboard unless you allow it. Optional app lock.

## Import from Termux

1. In Varco: Settings → Import from Termux → Choose files.
2. In the file picker, open the menu (⋮) and turn on "Show hidden files".
3. Open "Termux" in the side panel, then `.ssh`. Select your keys, `config` and `known_hosts`.

No file goes through shared storage, and Termux needs no change.

## YubiKey (PIV)

Keys → Add YubiKey → hold the key to the phone (or plug it in). Varco reads the public keys of slots
9a, 9c, 9d and 9e (no PIN needed) and you pick one. Then set this key on the server. On each connection,
Varco asks for the PIN and then for the YubiKey.

## Build

Requirements: JDK 21, Android SDK 37.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Release builds are signed with the key that `scripts/make-release-key.sh` makes (passwords only in the
macOS Keychain). Without that key, `assembleRelease` makes an unsigned APK.

## Licence

GPL-3.0. The terminal engine comes from Termux (GPLv3, with Apache-2.0 parts); see `terminal/NOTICE.md`.
Other parts: JSch (BSD-3-Clause), Bouncy Castle (MIT-style), YubiKit (Apache-2.0),
JetBrains Mono (OFL-1.1, `docs/licences/`), Material Symbols (Apache-2.0).
