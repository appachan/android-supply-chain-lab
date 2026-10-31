package com.github.appachan.supplychainlab.feature.greeting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class GreetingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screenShowsTheGreeting() {
        compose.setContent { GreetingScreen() }
        compose.onNodeWithText("Hello, supply chain!").assertIsDisplayed()
    }
}
