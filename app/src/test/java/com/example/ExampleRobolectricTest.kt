package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.HRConnectTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun ensureFirebaseInitialized() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context) ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:1234567890:android:test")
                    .setApiKey("test-api-key")
                    .setProjectId("gen-lang-client-0782301491")
                    .build()
            )
        }
    }

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("HRConnect", appName)
    }

    @Test
    fun `auth screen renders branding tagline and google sign in cta`() {
        composeRule.setContent {
            HRConnectTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }

        composeRule.onNodeWithText("Your HR Expertise Deserves to Earn More.").assertIsDisplayed()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    }
}
