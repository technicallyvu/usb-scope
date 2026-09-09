package com.technicallyvu.scope.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.fixture.ReplayDeviceSource
import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.desktop.ui.ScopeScreen
import com.technicallyvu.scope.desktop.ui.ScopeViewModel
import com.technicallyvu.scope.desktop.usb.LibUsbDevices
import com.technicallyvu.scope.desktop.usb.WindowsDeviceCheck
import java.nio.file.Path
import java.nio.file.Paths

/**
 * USB Scope dev bench.
 *   run                                  talk to real hardware via libusb
 *   run --replay <file.upkt> [--layout yuv|jpeg]   loop a recorded capture (no hardware needed)
 */
fun main(args: Array<String>) {
    val replay = args.indexOf("--replay").takeIf { it >= 0 && it + 1 < args.size }?.let { Paths.get(args[it + 1]) }
    val layout = args.indexOf("--layout").takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] } ?: "yuv"
    val replayInfo = if (layout == "jpeg")
        UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 0), UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))
    else UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
    val videoEndpoint = if (layout == "jpeg") UseeplusDriver.EP_VIDEO_IN else I4seasonYuvDriver.EP_IN
    val devices: DeviceSource = replay?.let { ReplayDeviceSource(it, replayInfo, videoEndpoint) } ?: LibUsbDevices()

    application {
        val scope = rememberCoroutineScope()
        val vm = remember {
            ScopeViewModel(
                devices = devices,
                drivers = DriverRegistry.all,
                scope = scope,
                outputDir = defaultOutputDir(),
                driverHintCheck = { replay == null && WindowsDeviceCheck.isPresentWithoutDriver(I4seasonYuvDriver.SUPPORTED_IDS) },
            ).also { it.start() }
        }
        DisposableEffect(Unit) {
            onDispose {
                vm.stop()
                (devices as? AutoCloseable)?.close()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "USB Scope (dev bench)" + (replay?.let { "  —  replay: ${it.fileName}" } ?: ""),
            state = rememberWindowState(width = 900.dp, height = 760.dp),
        ) {
            MaterialTheme { ScopeScreen(vm) }
        }
    }
}

private fun defaultOutputDir(): Path = Paths.get(System.getProperty("user.home"), "Pictures", "USB Scope")
