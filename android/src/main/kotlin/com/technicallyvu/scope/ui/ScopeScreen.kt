package com.technicallyvu.scope.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text

/**
 * Temporary placeholder: renders enough of [ScopeViewModel.ui] to prove the wiring compiles and
 * updates. The real viewfinder UI arrives in Task 7.
 */
@Composable
fun ScopeScreen(vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val ui by vm.ui.collectAsState()
    Column {
        Text("Connection: ${ui.session.connection}")
        Text("Frame received: ${ui.image != null}")
    }
}
