package autoconnect.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** In-app LOG viewer (Farzad's explicit request): connection attempts and
 *  results, bundle verification events, report queue activity. Presentation
 *  follows the shared [Ui] system; behavior is unchanged. */
class LogActivity : BaseActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Ui.palette(this)
        val root = Ui.screenRoot(this)

        root.addView(Ui.screenTitle(this, getString(R.string.settings_log)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Ui.cardButton(this, getString(R.string.refresh)) { render() }
        val copy = Ui.cardButton(this, getString(R.string.copy_log)) {
            val text = AppLog.all().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("AutoConnect log", text))
            Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
        }
        val clear = Ui.cardButton(this, getString(R.string.clear_log)) {
            AppLog.clear()
            render()
        }
        actions.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMarginStart(Ui.dp(this@LogActivity, Ui.SPACE_S))
        })
        actions.addView(clear, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMarginStart(Ui.dp(this@LogActivity, Ui.SPACE_S))
        })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@LogActivity, Ui.SPACE_M)
            bottomMargin = Ui.dp(this@LogActivity, Ui.SPACE_M)
        })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(pal.textPrimary)
            val p = Ui.dp(this@LogActivity, Ui.SPACE_M)
            setPadding(p, p, p, p)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            background = Ui.cardBackground(this@LogActivity)
            addView(logView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        val lines = AppLog.all()
        logView.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }
}
