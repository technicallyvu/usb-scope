package com.technicallyvu.scope.core

/**
 * Static content for the app's trust/about screen: who the protocol work is credited to, what the
 * app does and does not do with the device and the network, and the full license text. Kept as
 * plain data in `core` so both the Android and desktop UIs render the same facts.
 */
object TrustInfo {
    data class Attribution(val name: String, val url: String, val license: String, val note: String)

    val attributions: List<Attribution> = listOf(
        Attribution(
            name = "hbens/geek-szitman-supercamera",
            url = "https://github.com/hbens/geek-szitman-supercamera",
            license = "CC0",
            note = "Original protocol proof-of-concept.",
        ),
        Attribution(
            name = "echase/ProbeView",
            url = "https://github.com/echase/ProbeView",
            license = "MIT",
            note = "macOS viewer and protocol write-up.",
        ),
        Attribution(
            name = "MAkcanca/useeplus-linux-driver",
            url = "https://github.com/MAkcanca/useeplus-linux-driver",
            license = "GPL-3",
            note = "Reference only; no code included.",
        ),
        Attribution(
            name = "ollyoid/useeplus-linux-v4l2-driver",
            url = "https://github.com/ollyoid/useeplus-linux-v4l2-driver",
            license = "GPL",
            note = "Reference only; no code included.",
        ),
        Attribution(
            name = "jmz3/EndoscopeCamera",
            url = "https://github.com/jmz3/EndoscopeCamera",
            license = "unspecified",
            note = "Reference only.",
        ),
        Attribution(
            name = "NinesLastGoal/supercamera_WIN10",
            url = "https://github.com/NinesLastGoal/supercamera_WIN10",
            license = "unspecified",
            note = "Reference only.",
        ),
    )

    /**
     * Where the source lives. Placeholder until the repository has a public home; Android renders
     * the same URL from the `source_url` string resource.
     */
    val sourceUrl: String = "https://technicallyvu.com/usb-scope"

    /**
     * "Support this project" on both About surfaces. Placeholder — Anthony will point this at the
     * real donation page (Ko-fi / GitHub Sponsors) before release; the Android copy of it is the
     * `donate_url` string resource. Opening it is a browser intent, so it costs no permission.
     */
    val donateUrl: String = "https://ko-fi.com/technicallyvu"

    val protocolNote: String =
        "The single-interface YUV protocol was recovered by observing the device and reading the " +
            "publicly distributed vendor app; no vendor code is included."

    /** Android: the claim rests on a permission the manifest does not ask for. */
    val noNetworkStatement: String =
        "This app does not request the INTERNET permission, so it cannot send anything anywhere. " +
            "It has no analytics, no crash reporting, and no accounts."

    /**
     * Desktop: there is no permission manifest to point at, so the claim is about the build itself.
     * The Android sentence would be a half-truth here — a desktop JVM can always open a socket.
     */
    val desktopNoNetworkStatement: String =
        "This build opens no network connections: no sockets, no telemetry, no updates check."

    val permissionsNotRequested: List<String> = listOf(
        "android.permission.INTERNET",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.POST_NOTIFICATIONS",
    )

    val licenseText: String = """
        MIT License

        Copyright (c) 2026 Anthony Vu (TechnicallyVu)

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
    """.trimIndent()
}
