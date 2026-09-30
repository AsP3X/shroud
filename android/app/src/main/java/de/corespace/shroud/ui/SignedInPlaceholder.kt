package de.corespace.shroud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.GroupedScreen
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Temporary stand-in for the main shell (Chats, Contacts, Calls, Settings), which is not built
 * yet. Not in the design on purpose: it goes away when the tab shell lands.
 */
@Composable
fun SignedInPlaceholder(username: String, onLogOut: () -> Unit) {
    val colors = ShroudTheme.colors
    GroupedScreen {
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenInset), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogoMark(64.dp)
                ShroudText("Signed in as @$username", inter(20f, FontWeight.Bold), colors.textPrimary, textAlign = TextAlign.Center)
                ShroudText("Messaging is unlocked on this phone. Chats arrive in a later build.", inter(14f), colors.textSecondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Log Out", onLogOut, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}
