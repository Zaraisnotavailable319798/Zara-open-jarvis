package com.openjarvis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openjarvis.ui.settings.SettingsScreen

class SettingsActivity : ComponentActivity() {

    private lateinit var masterKey: MasterKey
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            this,
            "jarvis_encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val composeView = ComposeView(this)

        composeView.setContent {
            MaterialTheme {
                SettingsScreen(
                    onNavigateBack = { finish() },
                    onSaveProvider = { name, baseUrl, apiKey, model ->
                        saveProviderSettings(
                            name,
                            baseUrl,
                            apiKey,
                            model
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        setContentView(composeView)
    }

    private fun saveProviderSettings(
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        prefs.edit().apply {
            putString("provider_name", name)
            putString("provider_base_url", baseUrl)
            putString("provider_api_key", apiKey)
            putString("provider_model", model)
            apply()
        }
    }
}
