package com.technicallyvu.scope.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R

/**
 * Confirms a saved photo: a 64 dp thumbnail, the word "Saved" and the file name, for the three
 * seconds the view model keeps [thumb] around.
 *
 * @param visible false while the controls are auto-hidden; the toast fades with them.
 */
@Composable
fun SnapshotToast(thumb: Bitmap?, name: String?, visible: Boolean, modifier: Modifier = Modifier) {
    // The exit animation outlives the state that triggered it: hold on to the last thumbnail so the
    // toast fades out with its picture instead of blinking to an empty box. Written from a
    // SideEffect (after the composition commits), never during composition.
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastName by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if (thumb != null) {
            lastThumb = thumb
            lastName = name
        }
    }
    val shownThumb = thumb ?: lastThumb
    val shownName = if (thumb != null) name else lastName
    AnimatedVisibility(
        visible = visible && thumb != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shownThumb?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_snapshot_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column {
                    Text(stringResource(R.string.snapshot_saved), style = MaterialTheme.typography.titleSmall)
                    shownName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
