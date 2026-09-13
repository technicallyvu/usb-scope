package com.technicallyvu.scope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.technicallyvu.scope.settings.AppSettings
import com.technicallyvu.scope.ui.ScopeScreen
import com.technicallyvu.scope.ui.ScopeViewModel
import com.technicallyvu.scope.ui.theme.ScopeTheme

class MainActivity : ComponentActivity() {
    // The one settings store: created here, owned by the view model (which outlives a rotation, and
    // keeps the instance it was given).
    private val vm: ScopeViewModel by viewModels {
        ScopeViewModel.Factory(application, AppSettings(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    device?.let { vm.onDeviceDetached(it.deviceName) }
                }
                ACTION_USB_PERMISSION -> vm.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            ScopeTheme {
                // The flag follows the "keep screen on while streaming" preference: the view model
                // combines it with the connection state, so this only recomposes when the answer
                // actually flips, not on every frame.
                val keepScreenOn by vm.keepScreenOn.collectAsState()
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                ScopeScreen(vm, onRequestPermission = ::requestUsbPermission)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usb = getSystemService(UsbManager::class.java)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        // FLAG_MUTABLE: the system fills in EXTRA_DEVICE/EXTRA_PERMISSION_GRANTED on this intent.
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(device, pi)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.technicallyvu.scope.USB_PERMISSION"
        /** SharedPreferences file backing [AppSettings]. */
        const val PREFS_NAME = "scope_settings"
    }
}
