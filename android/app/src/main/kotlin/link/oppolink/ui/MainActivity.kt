package link.oppolink.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Sprint 4 polish - branded splash on cold launch. The compat
        // library hand-off MUST happen before super.onCreate so it can
        // swap the launch theme (Theme.OppoLink.Splash) for the
        // post-splash theme declared via `postSplashScreenTheme`. We
        // have no async warm-up work to gate the dismissal on, so the
        // default fade-out runs as soon as the first frame is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OppoLinkTheme {
                MainScreen()
            }
        }
    }
}
