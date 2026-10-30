# Optimizations

Review of Varco on 2026-10-30, at commit 47162e3 on branch main.
Status: review only. No change is applied yet. The owner decisions of 2026-10-30 are in the table and in each item.

Rules for this list: the security-review changes stay as they are. The security review is the audit of 2026-10-27. Its changes are the commits with a 'Report: <ID>' line (run: `git log --grep='Report:' --format='%h %s'`). /Users/user/Documents/Github/android/NEXT-STEPS.md has the status of each item (sections 5 and 6, and the appendix section for this app). No change to storage formats, database schemas, preference keys or encryption. Each applied item is one commit, so that you can revert it alone.

Effort: S = under 1 hour, M = up to half a day, L = more. Status: proposed, approved, applied (<commit>), rejected. applied = done; git log --grep=<ID> shows the commit.

## Summary

Varco is in good shape. It has no background work except the session foreground service. The terminal hot path stays off the main thread. The build is lean (R8 full mode, every dependency used). The real wins are three crash paths (a failed store write, a USB YubiKey with no CCID, a repeated name in an SFTP listing), about 135 KB of unused locale data and *.version files in the APK (estimate), two text-selection traps in the terminal, and about 200 lines of dead vendored Termux code.
Areas in good shape: battery, cold start, terminal hot path, main thread, lifecycle, dependencies and UI structure. Build and app resources are in good shape, except the locale data and *.version files in VARCO-OPT-03.

## Findings

| ID | Finding | Area | Effort | Risk | Status |
|---|---|---|---|---|---|
| VARCO-OPT-01 | Catch a failed store.bin.new create in Store.save, as the encrypt step already does | stability | S | low | applied |
| VARCO-OPT-02 | Remove the dead "More…" item from the terminal text-selection toolbar | ui-ux | S | low | applied |
| VARCO-OPT-03 | Ship only English resources and exclude META-INF/*.version files | minimalism | S | low | applied |
| VARCO-OPT-04 | Make Back end the terminal text selection, not leave the terminal | ui-ux | S | low | applied |
| VARCO-OPT-05 | Show the end of the path in the Files top bar | ui-ux | S | low | applied |
| VARCO-OPT-06 | Answer the YubiKey prompt with an error, not a crash, when the USB key has no smart-card interface | stability | S | low | applied |
| VARCO-OPT-07 | Drop duplicate names from an SFTP listing before the LazyColumn uses them as keys | stability | S | low | applied |
| VARCO-OPT-08 | Delete the unused autofill hooks in TerminalView | minimalism | S | low | approved |
| VARCO-OPT-09 | Let the missing-key guard do the only back step after a key delete | stability | S | low | approved |
| VARCO-OPT-10 | Apply the colour scheme only when it changes, so that colours set by the server stay | ui-ux | S | low | approved |
| VARCO-OPT-11 | Put the arrow keys on the right of the key bar, in the inverted-T layout of a computer keyboard | ui-ux | S to M | low | approved |

### VARCO-OPT-01: Catch a failed store.bin.new create in Store.save, as the encrypt step already does

- **What**: Store.save calls file.startWrite() outside its try block. AtomicFile.startWrite throws IOException when it cannot create store.bin.new, for example when storage is full or the files folder is not writable. The save coroutine runs in VarcoApp.scope. The app has no CoroutineExceptionHandler and no uncaught-exception handler. So the exception stops the process, and all open SSH sessions close. Commit 147347a (VARCO-UX-002) fixed the same problem, but only for the encrypt step.
- **Where**: app/src/main/kotlin/app/varco/data/Store.kt:84 (Store.save()); app/src/main/kotlin/app/varco/data/Store.kt:66-75 (Store.update()); app/src/main/kotlin/app/varco/VarcoApp.kt:27 (scope); app/src/main/kotlin/app/varco/ssh/Sessions.kt:39 (Sessions.open())
- **Change**: Replace line 84 with `val out = runCatching { file.startWrite() }.getOrElse { e -> Log.w(TAG, "Store write failed: ${e.javaClass.simpleName}"); return@withLock }`. Keep the rest of save() as it is.
- **Benefit**: When storage is full, Varco does not crash 300 ms (COALESCE_MS) after each server connect (Sessions.open writes lastUsed). It also does not crash after a settings, key or host-key change. Open sessions stay connected, and the next change tries the write again.
- **Evidence**: git blame: Store.kt:84 `val out = file.startWrite()` comes from ^62010b9. Lines 79-83 come from 147347a (Report: VARCO-UX-002, VARCO-PERF-007). Its message names this cause: an exception in the app scope with no handler ends the process with all SSH sessions. store.update has about 20 callers, including Sessions.kt:39 on each connect. `grep -rn 'CoroutineExceptionHandler|UncaughtExceptionHandler' app/src` finds nothing. AtomicFile.finishWrite and failWrite do not throw, so startWrite is the only unguarded throw.
- **Safety**: The store.bin format, the Vault key and alias, and the AtomicFile write sequence stay the same. The review lines 79-83 do not change. The fix applies their rule to one more line. Line 84 is not from a Report commit. The log has only the exception class, as the owner rules for release logs require. No dependency.
- **Verify**: Test on the emulator only. Make a debug build, run `adb shell run-as app.varco.debug chmod 500 files`, then open a server. Before the fix, the process stops. After the fix, logcat shows only 'Store write failed: IOException'. Then run `chmod 700 files`. The trigger is rare, but while it is true, each connect and each setting change crashes the app.
- **Effort**: S. **Risk**: low. **Net lines**: about +2 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures). Not checked: no unit test for the failed create (AtomicFile is an Android stub in unit tests), and no emulator run of the chmod 500 test in Verify.
- **Status**: applied

### VARCO-OPT-02: Remove the dead "More…" item from the terminal text-selection toolbar

- **What**: A long press shows a toolbar with Copy, Paste and an overflow button with "More…". "More…" stores the selection in mStoredSelectedText, ends the selection and calls showContextMenu(). No code in app/ or terminal/ makes a context menu, so nothing shows. The user taps the overflow, then More…, and loses the selection with no result. The stored copy of the terminal text stays on the heap, because nothing calls onContextMenuClosed().
- **Where**: terminal/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java:26 (mStoredSelectedText), :36 (ACTION_MORE), :120 (menu.add(ACTION_MORE) in onCreateActionMode), :146-155 (case ACTION_MORE), :373-382 (get/unsetStoredSelectedText); terminal/src/main/java/com/termux/view/TerminalView.java:505-511 (onContextMenuClosed), :1389-1398 (get/unsetStoredSelectedText); terminal/src/main/res/values/strings.xml:4 (text_selection_more)
- **Change**: Delete the ACTION_MORE constant, menu item and case, the mStoredSelectedText field, the stored-text accessors in the controller and in TerminalView, onContextMenuClosed, the text_selection_more string, and the imports that become unused. Add one line to terminal/NOTICE.md. Do NOT delete the right-click branch at TerminalView.java:619-621. Without its `return true`, a right click goes to the mouse-tracking branch and sends left-button events to the server.
- **Benefit**: The selection toolbar shows only Copy and Paste, with no dead overflow button, so a tap can no longer lose the selection. About 40 lines of upstream code go. No copy of selected terminal text stays in memory after a tap on More.
- **Evidence**: `grep 'ContextMenu|StoredSelectedText|text_selection_more'` over app/src and terminal/src finds only the listed lines. There is no onCreateContextMenu, listener or registerForContextMenu. The item has the default SHOW_AS_ACTION_NEVER (TextSelectionCursorController.java:120), so it goes to the overflow. R8 output for 0.2.1 (same as app/build.gradle.kts:30): usage.txt:27487 removes onContextMenuClosed, and :27492/:27512 remove unsetStoredSelectedText. But mapping.txt:272426-272428 keeps TerminalView.getStoredSelectedText (View getter keep rule), and :273119 keeps mStoredSelectedText. resources.txt:125 keeps text_selection_more as 'reachable from dex'. git log: TextSelectionCursorController.java and strings.xml have only 62010b9.
- **Safety**: Copy still goes through copySelection to onCopyTextToClipboard and SensitiveClip. The menu Paste still calls onPasteTextFromClipboard. Both lines stay. No security-review lines, storage, preference or key code change. It removes code and adds no dependency.
- **Verify**: Build and unit tests, then on the emulator: long-press in a session. The toolbar shows Copy and Paste and no overflow button. Copy and Paste still work. A right click with a mouse in vim does not send a click.
- **Effort**: S. **Risk**: low. **Net lines**: about -40 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). `./gradlew :app:assembleRelease` passed: mapping.txt has no StoredSelectedText, onContextMenuClosed or ACTION_MORE, and resources.txt and `apkanalyzer resources names` have no text_selection_more. Not checked: no emulator run of the toolbar, Copy, Paste and right-click test in Verify.
- **Status**: applied

### VARCO-OPT-03: Ship only English resources and exclude META-INF/*.version files

- **What**: The UI is English only (strings.xml holds only app_name). But the release APK carries the AndroidX, Compose and YubiKit translations for 85 locales. resources.arsc is stored uncompressed, so all of it counts. The APK also holds 56 META-INF/androidx.*.version files that only tools read. dependenciesInfo.includeInApk = false already keeps the dependency list out on purpose. Both fixes are lines in the same android {} block.
- **Where**: app/build.gradle.kts:21-83 (android {}, no androidResources block); app/build.gradle.kts:61-72 (android.packaging.resources); README.md:129 (APK size line)
- **Change**: Add `androidResources { localeFilters += "en" }` with a one-line comment, as btcpayapp/app/build.gradle.kts:129-137 does on the same AGP 9.4.1. Add a separate statement `excludes += "META-INF/*.version"` with a short comment after the existing setOf(...). Do not edit lines 63-70 (they come from 590213f and 60b3a13). Update the README size line after the build. The two lines are independent, so they can be two commits.
- **Benefit**: resources.arsc goes from 156,548 B to about 32 KB (about 124 KB less, estimate). The *.version entries remove 10,617 B. The release APK goes from 3,649,940 B to about 3.51 MB (estimate). The APK no longer lists the AndroidX library versions. Side effect: Compose's own TalkBack words (Close sheet, On, Off) are in English on a non-English phone, the same as the rest of the UI.
- **Evidence**: The artifact is current: output-metadata.json has versionName 0.2.1 / versionCode 5, the same as the build file, built 1 min after HEAD 47162e3. `apkanalyzer resources configs --type string` gives 86 configs. aapt2 dump chunks: the non-English string chunks total 75,520 B, and 1,765 of the 1,848 pool strings (48,582 B) are used only by non-English configs. The en-rAU/CA/GB/IN/XC chunks (5 x 944 B) stay. `unzip -Zl app-release.apk | grep -c 'META-INF/.*\.version$'` = 56, sum 10,617 B. The same exclude is in Ombra/app/build.gradle.kts:97, Plico:78, MegaGlass-Android:73 and btcpayapp:145.
- **Safety**: Build settings only. No storage format, schema, preference key or key handling. A new excludes statement keeps every line of 60b3a13 (VARCO-PERF-006) and 590213f (VARCO-UX-014) unchanged. No Varco doc records a decision on locales. MegaGlass-Android eb730fa (Report: MEGAGLASS-PERF-006) records the opposite decision (see Owner check).
- **Verify**: After the build, `apkanalyzer resources configs --type string` on the new APK lists only default plus en-rAU/CA/GB/IN/XC. `unzip -l` shows no *.version entry, and META-INF/services stays. Install on the emulator and open the main screens.
- **Effort**: S. **Risk**: low. **Net lines**: about +7 (estimate).
- **Owner decision**: 2026-10-30. English-only library strings in all apps, MegaGlass included. Apply it. Add a separate excludes line for META-INF/*.version. Do not edit the existing excludes lines from the security review. Make two commits: the locale filter, then the version-files line.
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). `./gradlew :app:assembleRelease` passed: `apkanalyzer resources configs --type string` lists only default (en-rAU/CA/GB/IN/XC also go, as in Fonte; the default strings are English). resources.arsc is 14,400 B (was 156,148 B). `unzip -l` shows no *.version entry, and META-INF/services stays. The release APK is 3,497,084 B (was 3,649,452 B, local build). Not checked: no emulator run of the main screens, and the README size line is not updated here.
- **Status**: applied

### VARCO-OPT-04: Make Back end the terminal text selection, not leave the terminal

- **What**: The manifest sets enableOnBackInvokedCallback="true", and targetSdk is 37. So Back goes to the NavDisplay callback and not to the KEYCODE_BACK branch in TerminalView.onKeyPreIme. That selection-cancel branch never runs. With text selected and the keyboard hidden, Back pops the terminal to Home. With the keyboard shown, the first Back closes the keyboard and the second Back leaves the terminal. The session stays open, but the user must open it again from the session row.
- **Where**: terminal/src/main/java/com/termux/view/TerminalView.java:650-657 (onKeyPreIme, dead KEYCODE_BACK branch), :1408-1423 (start/stopTextSelectionMode); app/src/main/kotlin/app/varco/ui/terminal/TermClient.kt:64 (copyModeChanged = Unit); app/src/main/kotlin/app/varco/ui/terminal/TerminalScreen.kt:121-143 (AndroidView factory in TerminalScreen())
- **Change**: Give TermClient an `onCopyMode: (Boolean) -> Unit` parameter and call it from copyModeChanged. In TerminalScreen, add `var selecting by remember { mutableStateOf(false) }`, pass `onCopyMode = { selecting = it }`, and add `BackHandler(enabled = selecting) { view?.stopTextSelectionMode() }` after the early return for a missing shell. FilesScreen.kt:239 uses the same BackHandler pattern.
- **Benefit**: Back during a selection ends the selection, as in a platform text field, and the terminal stays open. This applies each time the user selects terminal output and then changes their mind.
- **Evidence**: AndroidManifest.xml:20 enableOnBackInvokedCallback="true"; app/build.gradle.kts:28 targetSdk = 37. TermClient.kt:60 shouldBackButtonBeMappedToEscape() = false, and :64 copyModeChanged is a no-op. grep for OnBackInvoked/BackHandler/onBackPressed finds only FilesScreen.kt:239. Each end of a selection goes through stopTextSelectionMode, which calls copyModeChanged, so the Compose state stays correct. git blame: the TermClient.kt and TerminalScreen.kt lines are from 62010b9.
- **Safety**: Back handling in the UI only. Copy (SensitiveClip), paste (cc91e7f), the input queue, host keys, app lock, storage and preferences do not change. BackHandler comes from activity-compose, which the app already uses.
- **Verify**: Not run. Emulator check: long-press to select, then Back with the keyboard hidden. The selection ends and the terminal stays. With the keyboard shown, the first Back closes the keyboard and the second ends the selection. Back with no selection still goes to Home.
- **Effort**: S. **Risk**: low. **Net lines**: about +5 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). No unit test: the change only connects a callback to Compose state and a BackHandler. Not checked: no run-time check on an emulator or device of the Back steps in Verify.
- **Status**: applied

### VARCO-OPT-05: Show the end of the path in the Files top bar

- **What**: The Files screen shows the full remote path as the top bar title. Bar cuts long titles at the end, and the end is the name of the open folder. With back plus three action buttons, the title has room for about 18-24 characters (estimate). So '/home/alice/projects/varco' shows as '/home/alice/projects/va…', and the user cannot see which folder is open.
- **Where**: app/src/main/kotlin/app/varco/ui/files/FilesScreen.kt:243 (FilesScreen, `Bar(model.path ?: server?.name ?: "Files", ...)`); app/src/main/kotlin/app/varco/ui/components/Common.kt:45-47 (Bar)
- **Change**: Add `overflow: TextOverflow = TextOverflow.Ellipsis` to Bar and use it in the Text. FilesScreen passes TextOverflow.StartEllipsis, so the title reads '…/projects/varco/app'. Other screens keep the end ellipsis.
- **Benefit**: In folders at the third level and deeper, the open folder name is always visible, without 'Go to folder'.
- **Evidence**: Common.kt:47: `Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)`. FilesScreen.kt:243-261: back plus three action icons. TextOverflow.StartEllipsis is in the cached ui-text jar and works for single-line text. Bar sets maxLines = 1. git blame: Common.kt:43-54 and FilesScreen.kt:236-262 are 62010b9.
- **Safety**: Text layout only. Path handling, SFTP, glob escaping (5099230) and storage do not change.
- **Verify**: Emulator check: open a deep folder in Files. The title starts with '…' and ends with the folder name. Other screens with long titles still cut at the end.
- **Effort**: S. **Risk**: low. **Net lines**: about +1 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). No unit test: the change is text layout only. No release build: the change touches no build file, resource or manifest. Not checked: no run-time check on an emulator or device of the title in a deep folder, or of the end cut on other screens.
- **Status**: applied

### VARCO-OPT-06: Answer the YubiKey prompt with an error, not a crash, when the USB key has no smart-card interface

- **What**: TouchDialog catches only IOException around device.openConnection(SmartCardConnection). In YubiKit 3.2.1, UsbYubiKeyDevice.openConnection throws IllegalStateException("Unsupported connection type") when the device has no CCID interface. YubiKit calls onDevice on the main thread, so the exception stops the process and all SSH sessions close. This happens with a FIDO-only Security Key, or with a YubiKey 5 with CCID off over USB. AddYubiKeyDialog already wraps the same call in runCatching.
- **Where**: app/src/main/kotlin/app/varco/ui/prompts/PromptDialogs.kt:251-265 (TouchDialog, onDevice lambda); compare app/src/main/kotlin/app/varco/ui/keys/KeysScreen.kt:202 (AddYubiKeyDialog)
- **Change**: Replace the try/catch/finally at lines 252-264 with: `busy = true; val result = runCatching { device.openConnection(SmartCardConnection::class.java).use { ask.use(it) } }; busy = false; if (result.exceptionOrNull() is IOException) again = true else ask.answer(result)`. Keep the comment about a link that ends early.
- **Benefit**: The sign-in ends with an error line in that tab, not with a crash of the whole app. The handler gets about 8 lines shorter. Retry on IOException (NFC key moved away, or close() fails) works as before.
- **Evidence**: javap on the yubikit android-3.2.1 classes.jar: UsbYubiKeyDevice.openConnection calls verifyAccess, which throws `new IllegalStateException("Unsupported connection type")` when ConnectionManager.supportsConnection is false (no CCID). UsbConfiguration$YubicoVendorFilter accepts any Yubico vendor ID. UsbDeviceManager uses no Executor or Handler, so the callback runs on the main thread. The failure Result then goes through SshConnection.getSignature and Shell.describe, the same path as the 'PIN is blocked' ISE. git blame: lines 249-265 come from 57022244, not a Report commit.
- **Safety**: The PIN prompt and PIN wipe in pivSign, the slot and public-key check in Piv.sign, the IOException retry, YubiKeyDiscovery, ChipKeys and key data do not change. The fix only turns an uncaught exception into the failure Result that pivSign already handles. No storage, preference or key-format change. No dependency.
- **Verify**: The emulator cannot test USB keys. The owner must check on a device: plug in a FIDO-only key during a PIV prompt. The tab shows 'Unsupported connection type' and the app stays open. A normal YubiKey sign-in over NFC and USB still works.
- **Effort**: S. **Risk**: low. **Net lines**: about -8 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). javap on the cached yubikit android-3.2.1 aar shows that UsbYubiKeyDevice.verifyAccess throws IllegalStateException("Unsupported connection type"). No unit test: the change is in a Compose dialog callback that needs a YubiKit device. No release build: the change touches no build file, resource or manifest. Not checked: no run-time check on a device with a FIDO-only key, or of a normal YubiKey sign-in over NFC and USB.
- **Status**: applied

### VARCO-OPT-07: Drop duplicate names from an SFTP listing before the LazyColumn uses them as keys

- **What**: FilesScreen keys each row by entry.path. Sftp.list passes on each name that the server sends and removes no duplicates. When a listing has the same name two times, two rows get the same key, and Compose throws IllegalArgumentException("Key ... was already used") on the main thread. The process stops and all SSH sessions close. The listing is untrusted server data.
- **Where**: app/src/main/kotlin/app/varco/ssh/Sftp.kt:31 (Sftp.list()); app/src/main/kotlin/app/varco/ui/files/FilesScreen.kt:280 (`items(model.entries, key = { it.path })`)
- **Change**: In Sftp.list, change `return found.map { e ->` to `return found.distinctBy { it.filename }.map { e ->` (Sftp.kt:31).
- **Benefit**: A buggy or hostile server can no longer crash Varco by a repeated name in a folder listing, and with it the terminal sessions to other servers. A duplicate link also no longer uses one of the 50 link stats.
- **Evidence**: Sftp.kt:26-29 adds each LsEntry except . and .., and lines 31-38 map and sort it. Entry.path = join(dir, filename), so equal names give equal keys. javap of ChannelSftp.ls(String, LsEntrySelector) in jsch-2.28.8 shows only a selector.select call and no Set or Map. The compose ui-release jar has the string '" was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.' git blame: lines 27, 31-33 and 37-38 come from 57022244. Line 26 (lit, 5099230) and lines 30 and 34-36 (LINK_STATS, 7eef695) are review lines and stay unchanged.
- **Safety**: lit(), the link-stat limit, upload and download do not change. The change only removes a repeated name from the list that the screen shows. No storage, preference or key data. No dependency.
- **Verify**: OpenSSH on a normal Linux filesystem rarely sends a name two times, so the trigger is a hostile or unusual server. A unit test needs a fake ChannelSftp. If there is no such test, rely on code review and a normal Files listing on the emulator.
- **Effort**: S. **Risk**: low. **Net lines**: about 0 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :terminal:testDebugUnitTest :app:assembleDebug` passed (51 tests, 0 failures; :terminal has no unit tests). join() puts the name after a fixed folder path, so different names give different keys. No unit test: Sftp opens a jsch channel in its constructor, and a fake ChannelSftp is not a few lines. No release build: the change touches no build file, resource or manifest. Not checked: no run-time check of a Files listing on a device or emulator, and no test with a server that sends a name two times.
- **Status**: applied

### VARCO-OPT-08: Delete the unused autofill hooks in TerminalView

- **What**: TerminalView keeps the Termux autofill support. Nothing calls requestAutoFill* or isAutoFillEnabled, so mAutoFillType stays NONE. The autofill(AutofillValue) override writes raw text with mTermSession.write(). That path skips the paste filter that cc91e7f (VARCO-SEC-009) added for keyboard pastes (ESC/C1 removal, bracketed paste). Today the path cannot really run, because the view has type NONE and is not important for autofill. So it is dead code with a small defence-in-depth gain.
- **Where**: terminal/src/main/java/com/termux/view/TerminalView.java:11, 30-31 and 38 (imports android.os.Build, AutofillManager, AutofillValue and RequiresApi, used only by the autofill code), 88-124 (mAutoFillType/mAutoFillImportance/mAutoFillHints), 654 (cancelRequestAutoFill() in onKeyPreIme), 1062-1178 (autofill, getAutofillType/Hints/Value, getImportantForAutofill, resetAutoFill, getAutoFillManagerService, isAutoFillEnabled, requestAutoFill*, cancelRequestAutoFill)
- **Change**: Delete these members and the call at line 654. Keep setTopRow() at lines 1056-1058, because TextSelectionCursorController.java:253 and :293 call it. Add `setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);` as a new line after line 257 in the constructor, so that the flags stay as today (AUTOFILL_TYPE_NONE is the View default). Do not edit the 71af0aa lines 256-257. Add one line to terminal/NOTICE.md.
- **Benefit**: About 160 fewer lines of vendored code and about 360 B of dex. No code path is left that writes autofill text into the remote shell around the paste filter.
- **Evidence**: usage.txt (TerminalView section): isAutoFillEnabled(), requestAutoFill(String[]), requestAutoFillPassword() and requestAutoFillUsername() are removed as unused. apkanalyzer dex, class s42: autofill(AutofillValue) 118 B, getAutoFillManagerService() 91 B, getAutofillHints 36 B, getAutofillType 34 B, getAutofillValue 44 B and getImportantForAutofill 36 B stay in the dex. TerminalView.java:1065-1073: autofill() calls `mTermSession.write(value.getTextValue().toString())` directly. grep: app/ has no requestAutoFill caller.
- **Safety**: It touches code next to security-review code. git blame: lines 88-124 and 1062-1178 are ^62010b9. The new setImportantForAutofill(NO) line goes directly after the 71af0aa lines 256-257 (Report: VARCO-PRIV-001), and it re-implements the terminal's autofill exclusion (a privacy control) as a flag in place of the getImportantForAutofill override. The change also closes a path around the cc91e7f paste filter (Report: VARCO-SEC-009, lines 360-380). The 71af0aa and cc91e7f lines stay unchanged. No storage, preference or key code. Trade-off: the vendored module moves a little further from upstream Termux, but this is one self-contained block.
- **Verify**: Build and unit tests, then on the emulator: press Back during a text selection, and type into a session. Make sure that the system shows no autofill suggestion over the terminal.
- **Effort**: S. **Risk**: low. **Net lines**: about -160 (estimate).
- **Owner decision**: 2026-10-30. The owner approves the setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO) flag in place of the getImportantForAutofill override. Do not edit the VARCO-PRIV-001 lines.
- **Status**: approved

### VARCO-OPT-09: Let the missing-key guard do the only back step after a key delete

- **What**: After Delete in KeyScreen, onConfirm removes the key from the store and then also calls nav.back(). The popped KeyScreen stays composed during the sharedAxis pop animation. The store change gets to its collector before the frame that recomposes for the back-stack change, so the outgoing KeyScreen composes with key == null. Its guard `LaunchedEffect(Unit) { nav.back() }` then pops a second screen, and [Home, Keys, Key] becomes [Home].
- **Where**: app/src/main/kotlin/app/varco/ui/keys/KeyScreen.kt:132 (onConfirm, nav.back()); app/src/main/kotlin/app/varco/ui/keys/KeyScreen.kt:51-53 (key == null guard); app/src/main/kotlin/app/varco/ui/nav/Navigator.kt:27-29 (back())
- **Change**: Remove `nav.back()` at KeyScreen.kt:132. The store update removes the key, and the existing guard at lines 51-53 then does exactly one back step from the top screen.
- **Benefit**: After a key delete, the user is back on the Keys list, not on Home. One navigation path remains, not two that compete.
- **Evidence**: KeyScreen.kt:125-132: store.update runs before nav.back() in the same click handler. AppNav.kt:55-57 sets transitionSpec and popTransitionSpec, so the outgoing entry stays composed. Navigator.kt:28 back() does not check which entry calls it. Line 132 comes from ^62010b9. Lines 123-124 (3e0d2e5, Report: VARCO-PERF-003) stay unchanged.
- **Safety**: The Keystore delete in the app scope (review lines 123-124) and the store update stay the same. Only one navigation call goes. No storage format, preference key or key handling change. No dependency.
- **Verify**: Code-path analysis only. Not run. Emulator check before and after: Keys > a key > Delete. Today it should show Home. After the change it must show the Keys list without the key.
- **Effort**: S. **Risk**: low. **Net lines**: about -1 (estimate).
- **Status**: approved

### VARCO-OPT-10: Apply the colour scheme only when it changes, so that colours set by the server stay

- **What**: LaunchedEffect(scheme) runs each time TerminalScreen enters composition: after a return from Home, Files, Settings or Snippets, and after each app-lock unlock. It calls TermLook.apply(), which calls mColors.reset() on the emulator of every session. So colours that the server set with OSC 4 (palette) or OSC 10/11/12 (foreground, background, cursor) are lost in every tab on each return, also when the scheme did not change. base16-shell and pywal set these at shell start.
- **Where**: app/src/main/kotlin/app/varco/ui/terminal/TerminalScreen.kt:108-111 (LaunchedEffect(scheme)); app/src/main/kotlin/app/varco/term/TermLook.kt:15-22 (apply()); terminal/src/main/java/com/termux/terminal/TerminalColors.java:28-30 (reset()); terminal/src/main/java/com/termux/terminal/TerminalEmulator.java:2044-2072 (OSC 4), :2082-2104 (OSC 10/11/12)
- **Change**: In TermLook, add `private var applied: Scheme? = null`. At the start of apply(), add `if (scheme === applied) return` and `applied = scheme`. Schemes.byId returns the same instance for the same scheme, so a new or re-imported scheme still resets all tabs. New tabs still get the current scheme, because a new emulator copies COLOR_SCHEME.mDefaultColors.
- **Benefit**: Colours that the server sets with OSC 4/10/11/12 stay when the user leaves the terminal and comes back. A change of scheme still resets all tabs as before.
- **Evidence**: TerminalColors.reset() (:28-30) copies all NUM_INDEXED_COLORS (259) from the shared defaults. TerminalRenderer:66 draws with mEmulator.mColors.mCurrentColors. TerminalScreen leaves the composition on navigation (AppNav.kt NavDisplay) and while the app lock shows (MainActivity.kt:56-61). grep: nothing else calls TermLook.apply. `git log -L 15,22` on TermLook.kt shows only 62010b9. In a new process, applied is null, so the first entry still applies the scheme.
- **Safety**: Only colour state in memory changes. No storage, preference key, stored scheme, schema or key handling. OSC 52 and the other sequences that 670f85e filters are not involved. No dependency.
- **Verify**: Emulator check: in a tab, run `printf '\e]11;#330000\a'`, open Files from the session sheet and go back. Today the background goes back to the scheme colour. After the change it stays dark red. Then select another scheme in Settings. All tabs take the new colours.
- **Effort**: S. **Risk**: low. **Net lines**: about +3 (estimate).
- **Status**: approved

### VARCO-OPT-11: Put the arrow keys on the right of the key bar, in the inverted-T layout of a computer keyboard

- **What**: New item from the owner. The key bar above the keyboard has two rows: [● server ▾] ESC TAB CTRL ALT, then ← ↓ ↑ → - / | ⚡. The four arrows are in one line on the left, so up is not above down. On a computer keyboard, the arrows are an inverted T on the right: ↑ on the top row, and ← ↓ → on the bottom row.
- **Where**: app/src/main/kotlin/app/varco/ui/terminal/ExtraKeys.kt:60-68 (KDoc with the layout), :83-107 (ExtraKeys(), the two Rows). The layout is fixed in code. No setting, preference key or stored format holds it (grep finds the key bar only in ExtraKeys.kt and its call at TerminalScreen.kt:169).
- **Change**: Make the bar one Row with two parts. On the left, a Column with the other keys in their current order, over two rows. On the right, an arrow block of three key columns, over the same two rows. The top row of the block is an empty cell (Spacer with weight 1f), ↑ and an empty cell. The bottom row is ←, ↓ and →. Give the block 3/8 of the bar width, so that each arrow keeps the width of a row 2 key today (about 40 dp on a 360 dp screen, estimate). To keep the other keys at that width too, row 1 is the chip, ESC, TAB and CTRL, and row 2 is ALT, -, /, | and ⚡. If ALT stays in row 1, ESC, TAB, CTRL and ALT go to about 33 dp (estimate). Keep each Key, Mod and IconKey call as it is, and keep the 48 dp row height. Update the layout lines in the KDoc.
- **Benefit**: The arrows have the layout that users know from a computer keyboard, so they find ↑ and ↓ without a look. Each key keeps a 48 dp high touch target and about the width of today's narrowest key. Trade-off: the server chip gets narrower (about 87 dp, from 119 dp on a 360 dp screen, estimate), so a long server name is cut sooner. CTRL and ALT are in different rows.
- **Safety**: UI layout only. Lines 90 and 92-104 come from 085a13f (Report: VARCO-UX-005: TalkBack names, long-press actions, CTRL/ALT state, 48 dp rows). The owner approved this item, so these calls can move, but their arguments and semantics do not change. TalkBack reads the left keys first, then the arrows. No storage, preference key or key handling. No dependency.
- **Verify**: Build and unit tests. Then the owner checks on a phone, in portrait and landscape: ↑ is directly above ↓, arrows repeat while held, a long press on -, / and | types _, \ and &, CTRL and ALT lock, and TalkBack speaks each key name.
- **Effort**: S to M. **Risk**: low. **Net lines**: about +10 (estimate).
- **Owner decision**: 2026-10-30. Put the arrow keys on the right side of the key bar, in the layout of a computer keyboard: two rows, ↑ on the top row above ↓, and ←, ↓ and → on the bottom row. The other keys stay on the left in their current order. Keep the touch targets. The layout is not stored, so no stored format or preference key changes.
- **Status**: approved

## Areas in good shape

- Battery: no wake locks, alarms, WorkManager jobs or polling. SessionService starts with the first tab and stops after the last tab (SessionService.kt:30-41). Its notification is IMPORTANCE_LOW.
- Cold start: the store read and the Keystore decrypt run on Dispatchers.IO (VarcoApp.kt:49-53). Icons, ChipKeys and Importer are lazy. Debug builds have StrictMode.
- Terminal hot path: an IO thread reads the SSH stream, and each main-thread message gets one 64 KiB chunk. Typed input goes through the non-blocking InputQueue (4 MiB cap). The renderer allocates nothing per frame. append() drops a bad sequence and does not stop the app.
- Main thread: SSH connect, PTY resize, store writes (grouped within 300 ms), the font read, Keystore delete and SFTP all run off the main thread.
- Lifecycle: no Context leaks. Sessions.view, YubiKey discovery, the service collector and the per-tab threads stop on dispose. Prompts close with their connection. Reconnect has a double-tap guard.
- Build: R8 full mode, shrinkResources, Log.v/d/i stripped. The APK has no lib/, kotlin/**, DebugProbesKt.bin or licence copies. dependenciesInfo and vcsInfo are off.
- Dependencies: each declared library is used, and no library can be replaced with less code.
- App code: only reachable resources ship (1 string, 3 drawables, all 25 icons used). Screens share Bar, ListRow, SwitchRow, ConfirmDialog and EmptyState, with no extra layers.
- UI: one Navigator with predictive back, empty states with actions, red confirm dialogs for destructive actions, focused prompt dialogs, an accessible extra-key bar and lazy-list keys. The Files keys can collide (VARCO-OPT-07).
- Recorded decisions stay as they are: VARCO-PERF-001 (JSch keep rule), VARCO-UX-010 (keepalive, device test first) and the one-tap tab close (VARCO-UX-008).
- Artifact: app-release.apk 0.2.1 (3,649,940 B) matches HEAD.
