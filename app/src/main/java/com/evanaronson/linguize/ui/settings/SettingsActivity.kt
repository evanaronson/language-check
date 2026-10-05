package com.evanaronson.linguize.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.evanaronson.linguize.App
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.theme.AppTheme

/** The launcher icon's screen: settings, plus a place to try a check. */
class SettingsActivity : ComponentActivity() {
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

    /** Going home or to recents. Links this screen opens say they're not the user leaving. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (application as App).settingsLeft.value++
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) (application as App).settingsLeft.value++
    }
}
