package com.technicallyvu.scope.ui

import com.technicallyvu.scope.core.TrustInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CountSystemPermissionsTest {

    private val pkg = "com.technicallyvu.scope"

    @Test
    fun `null means the platform reported nothing requested`() {
        assertEquals(0, countSystemPermissions(pkg, null))
    }

    @Test
    fun `an empty array counts zero`() {
        assertEquals(0, countSystemPermissions(pkg, emptyArray()))
    }

    @Test
    fun `the AndroidX app-private receiver permission is not counted`() {
        val requested = arrayOf("$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        assertEquals(0, countSystemPermissions(pkg, requested))
    }

    @Test
    fun `a real system permission is counted`() {
        assertEquals(1, countSystemPermissions(pkg, arrayOf("android.permission.INTERNET")))
    }

    @Test
    fun `a mixed list counts only the system ones`() {
        val requested = arrayOf(
            "android.permission.INTERNET",
            "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.CAMERA",
        )
        assertEquals(2, countSystemPermissions(pkg, requested))
    }

    @Test
    fun `another app whose id merely shares our prefix is still counted`() {
        // "com.technicallyvu.scopeX.FOO" is not ours: the rule is the package id plus a dot.
        assertEquals(1, countSystemPermissions(pkg, arrayOf("${pkg}X.FOO")))
    }
}

class ReflowParagraphsTest {

    @Test
    fun `single newlines inside a paragraph become spaces`() {
        assertEquals("one two three", reflowParagraphs("one\ntwo\nthree"))
    }

    @Test
    fun `a blank line stays a paragraph break`() {
        assertEquals("one two\n\nthree four", reflowParagraphs("one\ntwo\n\nthree\nfour"))
    }

    @Test
    fun `indentation from a trimIndent-style block is dropped`() {
        val src = "    MIT License\n\n    Copyright (c) 2026 Anthony Vu\n    All rights reserved.\n"
        assertEquals("MIT License\n\nCopyright (c) 2026 Anthony Vu All rights reserved.", reflowParagraphs(src))
    }

    @Test
    fun `windows line endings are handled`() {
        assertEquals("one two\n\nthree", reflowParagraphs("one\r\ntwo\r\n\r\nthree"))
    }

    @Test
    fun `text with no hard wraps is unchanged and the function is idempotent`() {
        val flat = "One sentence.\n\nAnother sentence."
        assertEquals(flat, reflowParagraphs(flat))
        assertEquals(flat, reflowParagraphs(reflowParagraphs(flat)))
    }

    @Test
    fun `the MIT licence reflows to its five paragraphs with no stray newlines`() {
        val out = reflowParagraphs(TrustInfo.licenseText)
        val paragraphs = out.split("\n\n")
        assertEquals(5, paragraphs.size, "MIT has a title, a copyright line and three paragraphs")
        assertTrue(paragraphs.none { it.contains('\n') }, "no hard wraps may survive inside a paragraph")
        assertTrue(out.startsWith("MIT License"))
        assertTrue(out.endsWith("DEALINGS IN THE SOFTWARE."))
        assertFalse(out.contains("  "), "no double spaces from joining wrapped lines")
    }
}
