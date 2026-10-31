package com.github.appachan.supplychainlab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityShowsTheGreeting() {
        compose.onNodeWithText("Hello, supply chain!").assertIsDisplayed()
    }
}
