package com.neurostenographer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.frontdebug.FrontExportDemo
import app.frontdebug.FrontPipelineDemo

@Composable
internal fun AppContent() {
    var exportDemo by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { exportDemo = !exportDemo }) {
            Text(if (exportDemo) "DEBUG: к импорту" else "DEBUG: проверить сохранение JSON")
        }
        if (exportDemo) FrontExportDemo { exportDemo = false } else FrontPipelineDemo()
    }
}
