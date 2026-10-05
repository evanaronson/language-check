package com.evanaronson.languagecheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.card.CheckViewModel
import com.evanaronson.languagecheck.ui.settings.SettingsScreen
import com.evanaronson.languagecheck.ui.settings.SettingsViewModel

/** The launcher icon's screen: settings, plus a place to try a check. */
class MainActivity : ComponentActivity() {
    private val settings: SettingsViewModel by viewModels()
    private val check: CheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(settings, check)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as App).settingsClosed.value++
    }
}
