package com.github.appachan.supplychainlab.feature.greeting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.github.appachan.supplychainlab.shared.domain.greeting
import com.github.appachan.supplychainlab.shared.ui.LabTheme
import com.github.appachan.supplychainlab.shared.ui.R

@Composable
fun GreetingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) { Text(text = greeting(stringResource(R.string.subject))) }
    }
}

@Preview
@Composable
private fun GreetingScreenPreview() {
    LabTheme { GreetingScreen() }
}
