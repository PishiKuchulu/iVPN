package autoconnect.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import java.util.Locale

/** Applies the in-app language (default Persian) and theme preferences. */
abstract class BaseActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = Prefs(newBase).language
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        when (Prefs(this).theme) {
            "light" -> setTheme(android.R.style.Theme_Material_Light)
            "dark" -> setTheme(android.R.style.Theme_Material)
            else -> {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) {
                    setTheme(android.R.style.Theme_Material)
                } else {
                    setTheme(android.R.style.Theme_Material_Light)
                }
            }
        }
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
    }
}
