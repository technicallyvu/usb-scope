package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import java.nio.file.Files
import java.nio.file.Path

/** Presents one fake useeplus device whose video stream is a looping `.upkt` capture. */
class ReplayDeviceSource(
    private val log: Path,
    info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF),
) : DeviceSource {
    private val ref = DeviceRef(info, bus = 0, address = 0)

    override fun list(): List<DeviceRef> = listOf(ref)

    override fun open(ref: DeviceRef): UsbTransport {
        val packets = Files.newInputStream(log).use { PacketLogReader.read(it) }
        return ReplayTransport(packets, loop = true, sleep = Thread::sleep)
    }
}
