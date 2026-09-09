package com.technicallyvu.scope.core.useeplus

class UseeplusDriver {
    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val IFACE_CONTROL = 0
        const val IFACE_VIDEO = 1
        const val EP_VIDEO_OUT = 0x01
        const val EP_VIDEO_IN = 0x81
        const val EP_CONTROL_OUT = 0x02
        const val EP_CONTROL_IN = 0x82
    }
}
