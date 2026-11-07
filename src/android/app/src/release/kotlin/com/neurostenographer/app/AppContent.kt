package com.neurostenographer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** No demo results in release; the real pipeline is connected in F3. */
@Composable
internal fun AppContent() {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Нейро-стенографист", style = MaterialTheme.typography.headlineMedium)
        Text("Обработка аудио ещё не подключена.")
    }
}
