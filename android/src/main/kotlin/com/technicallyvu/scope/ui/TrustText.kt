package com.technicallyvu.scope.ui

/**
 * The two text decisions the About screen makes, kept out of the composable so a plain JVM test can
 * pin them down. Nothing here touches Android or Compose.
 */

/**
 * How many permissions this app asks the *system* for.
 *
 * [android.content.pm.PackageInfo.requestedPermissions] is not just what the manifest in
 * `src/main` declares: the manifest merger folds in whatever the libraries declare, and AndroidX
 * injects an app-private signature permission named `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
 * so that `ContextCompat.registerReceiver` can keep an internal broadcast to this process. It is
 * defined by the app, granted only to the app, and invisible to anything else — counting it makes
 * the About screen claim a permission the user can neither see nor be affected by, which is exactly
 * the sort of overstatement this screen exists to avoid.
 *
 * Anything whose name starts with the app's own package id followed by a dot is app-private by that
 * same argument, so the rule is the prefix rather than a hard-coded AndroidX name that a library
 * upgrade could rename out from under us. A permission that merely *begins* with the package id
 * without the dot (a different app id sharing a prefix, e.g. `com.technicallyvu.scopeX.FOO`) is a
 * different app's permission and is still counted.
 *
 * @return the number of entries in [requested] that are not app-private; 0 when [requested] is null
 * (the platform returns null, not an empty array, when nothing is requested).
 */
fun countSystemPermissions(packageName: String, requested: Array<String>?): Int {
    if (requested == null) return 0
    val prefix = "$packageName."
    return requested.count { !it.startsWith(prefix) }
}

/**
 * Un-wraps prose that was hard-wrapped in a source file so it can be re-wrapped to whatever width it
 * is actually being shown at.
 *
 * `TrustInfo.licenseText` is the verbatim MIT text, hard-wrapped at ~80 columns because that is how
 * the licence is distributed. Rendering those newlines literally on a phone gives a ragged column
 * that breaks a second time wherever the screen is narrower than 80 characters, and reads as a
 * defect. Single newlines inside a paragraph therefore become spaces; a blank line stays a paragraph
 * break, because that is the only structure the licence actually has.
 *
 * Idempotent: text with no hard wraps comes back unchanged apart from trimming.
 */
fun reflowParagraphs(text: String): String =
    text.replace("\r\n", "\n")
        .split(PARAGRAPH_BREAK)
        .map { paragraph -> paragraph.replace(WHITESPACE_RUN, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/** A blank line — optionally carrying indentation — is the one break worth keeping. */
private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n[ \t\n]*")

/** Inside a paragraph every run of whitespace, newlines included, collapses to a single space. */
private val WHITESPACE_RUN = Regex("[ \t\n]+")
