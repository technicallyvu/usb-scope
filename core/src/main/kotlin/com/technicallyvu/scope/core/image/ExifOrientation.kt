package com.technicallyvu.scope.core.image

/** EXIF Orientation (1..8) for "mirror horizontally, then rotate clockwise by N degrees". */
object ExifOrientation {
    fun of(rotationDegrees: Int, mirror: Boolean): Int = when (((rotationDegrees % 360) + 360) % 360) {
        0 -> if (mirror) 2 else 1
        90 -> if (mirror) 7 else 6
        180 -> if (mirror) 4 else 3
        270 -> if (mirror) 5 else 8
        else -> 1
    }
}
