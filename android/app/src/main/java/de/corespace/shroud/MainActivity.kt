package de.corespace.shroud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.corespace.shroud.ui.ShroudApp
import de.corespace.shroud.ui.theme.ShroudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ShroudApplication).container
        setContent {
            ShroudTheme {
                ShroudApp(container)
            }
        }
    }
}
