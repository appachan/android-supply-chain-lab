package com.github.appachan.supplychainlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.appachan.supplychainlab.feature.greeting.GreetingScreen
import com.github.appachan.supplychainlab.shared.ui.LabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LabTheme { GreetingScreen() } }
    }
}
