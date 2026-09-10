package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Presents one fake device whose video stream is a looping `.upkt` capture. The log is read through
 * [openLog] (a file on desktop, an asset on Android) and parsed once.
 */
class ReplayDeviceSource(
    private val openLog: () -> InputStream,
    info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF),
    private val videoEndpoint: Int = UseeplusDriver.EP_VIDEO_IN,
) : DeviceSource {
    constructor(log: Path, info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF), videoEndpoint: Int = UseeplusDriver.EP_VIDEO_IN) :
        this({ Files.newInputStream(log) }, info, videoEndpoint)

    private val ref = DeviceRef(info, bus = 0, address = 0)
    private val packets: List<LoggedPacket> by lazy { openLog().use { PacketLogReader.read(it) } }

    override fun list(): List<DeviceRef> = listOf(ref)

    override fun open(ref: DeviceRef): UsbTransport =
        ReplayTransport(packets, loop = true, sleep = Thread::sleep, videoEndpoint = videoEndpoint)
}
