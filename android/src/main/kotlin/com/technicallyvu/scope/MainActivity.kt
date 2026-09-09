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
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.technicallyvu.scope.ui.ScopeScreen
import com.technicallyvu.scope.ui.ScopeViewModel

class MainActivity : ComponentActivity() {
    private val vm: ScopeViewModel by viewModels()

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme {
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
    }
}
