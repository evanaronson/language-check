package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * Opened from the text-selection menu (ACTION_PROCESS_TEXT) through one of the
 * entries in [com.evanaronson.linguize.data.MenuEntry]. Floats the result card over the app the text came
 * from, and hands back the text with any accepted changes when it closes.
 */
class CheckActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels()
    private var selection: Selection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        // Whitespace around the selection isn't checked, and stays where it was.
        val selection = Selection.of(selected).takeIf { it.text.isNotEmpty() } ?: return finish()
        this.selection = selection
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        // Which menu entry was tapped decides the language: Catalanize, Castilianize, or Linguize to detect it.
        val entry = (application as App).menu.entryFor(intent.component)

        // Start the request before the first frame is drawn.
        if (check.state == null) check.check(selection.text, entry.language, Origin.Menu, callingPackage)

        val actions = CardActions.of(
            check,
            context = this,
            onClose = ::finish,
            onOpenSettings = {
                startActivity(HomeActivity.settingsIntent(this))
                finish()
            },
            canReplace = !readOnly,
        )
        setContent {
            AppTheme {
                check.state?.let { FloatingCard(it, actions, onDismiss = ::finish) }
            }
        }
    }

    /** However the card closes (Done, back, tapping outside, Copy), accepted changes go back to the app. */
    override fun finish() {
        val accepted = check.workingText
        val selection = selection
        if (accepted != null && selection != null) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, selection.with(accepted)))
        }
        super.finish()
    }
}
