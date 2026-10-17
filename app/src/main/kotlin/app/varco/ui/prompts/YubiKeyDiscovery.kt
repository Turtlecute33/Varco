package app.varco.ui.prompts

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice

/**
 * Listens for a YubiKey on NFC and USB while it is in the composition. [onDevice] runs on a YubiKit thread.
 * [onNfcOff] is called when the phone has NFC but it is off (USB still works).
 */
@Composable
fun YubiKeyDiscovery(onDevice: (YubiKeyDevice) -> Unit, onNfcOff: () -> Unit = {}) {
    val activity = LocalActivity.current ?: return
    val device by rememberUpdatedState(onDevice)
    val nfcOff by rememberUpdatedState(onNfcOff)
    DisposableEffect(activity) {
        val manager = YubiKitManager(activity)
        manager.startUsbDiscovery(UsbConfiguration().handlePermissions(true)) { device(it) }
        try {
            manager.startNfcDiscovery(NfcConfiguration().timeout(15_000), activity) { device(it) }
        } catch (e: NfcNotAvailable) {
            if (e.isDisabled) nfcOff()
        }
        onDispose {
            manager.stopNfcDiscovery(activity)
            manager.stopUsbDiscovery()
        }
    }
}
