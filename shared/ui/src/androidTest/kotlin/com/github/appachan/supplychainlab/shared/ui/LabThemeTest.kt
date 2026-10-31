package com.github.appachan.supplychainlab.shared.ui

import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LabThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun themeShowsItsContent() {
        compose.setContent { LabTheme { Text(stringResource(R.string.subject)) } }
        compose.onNodeWithText("supply chain").assertIsDisplayed()
    }
}
