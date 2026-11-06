package com.neurostenographer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

// Заглушка bootstrap, чтобы APK собирался. Экраны делает front (Санёк) и заменяет этот файл.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("Нейро-стенографист")
            }
        }
    }
}
