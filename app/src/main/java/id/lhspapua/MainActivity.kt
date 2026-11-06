package id.lhspapua

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import id.lhspapua.ui.LhsApp

class MainActivity: ComponentActivity() {
    override fun attachBaseContext(newBase:android.content.Context) {
        val config=android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("id-ID"))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LhsApp(onExit=::finish) }
    }
}
