package de.corespace.shroud.ui.contacts

import androidx.compose.runtime.Composable

/**
 * The Contacts tab: requests, contacts with presence, add contact, QR (contacts §6; iOS `ContactsView`).
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-CONTACTS-UI**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ContactsScreen(query: String, onQueryChange: (String) -> Unit) {
}
