package de.corespace.shroud.core.push.unifiedpush

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * A Play push reaches the registrar in-process. The library's report is a broadcast to this
 * package, which our manifest receiver never gets: before, every push was lost while the app was
 * not open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayPushReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPlayPushBecomesAMessageEventAndIsNotBroadcast() {
        installPlayServices()
        val connector = InProcessConnector(context)
        val body = byteArrayOf(1, 2, 3)
        val push = Intent("com.google.android.c2dm.intent.RECEIVE")
            .putExtra("subtype", "wp:connection-token")
            .putExtra("rawData", body)
            .putExtra("google.message_id", "0:42")

        EmbeddedFcmBridge.receive(connector, push)

        val event = connector.events.single()
        assertEquals(UnifiedPushProtocol.ACTION_MESSAGE, event.action)
        assertEquals("connection-token", event.token)
        assertArrayEquals(body, event.bytes)
        assertEquals("0:42", event.id)
        val sent = shadowOf(context as Application).broadcastIntents
            .filter { it.action == UnifiedPushProtocol.ACTION_MESSAGE }
        assertEquals(emptyList<Intent>(), sent)
    }

    @Test
    fun otherBroadcastsStillGoOut() {
        val connector = InProcessConnector(context)
        connector.sendBroadcast(Intent(UnifiedPushProtocol.ACTION_MESSAGE).setPackage("io.heckel.ntfy"))
        connector.sendBroadcast(Intent("some.other.ACTION").setPackage(context.packageName))

        assertEquals(emptyList<DistributorEvent>(), connector.events)
        assertEquals(2, shadowOf(context as Application).broadcastIntents.size)
    }

    @Test
    fun manifestRoutesPlayPushesThroughOurReceiver() {
        val receivers = manifestReceivers()
        val library = receivers.single {
            it.getAttribute("android:name") == "org.unifiedpush.android.embedded_fcm_distributor.impl.FirebaseReceiver"
        }
        assertEquals("remove", library.getAttribute("tools:node"))
        val ours = receivers.single { it.getAttribute("android:name") == ".core.push.unifiedpush.PlayPushReceiver" }
        assertEquals("com.google.android.c2dm.permission.SEND", ours.getAttribute("android:permission"))
        val actions = ours.getElementsByTagName("action").let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }
        }
        assertTrue("com.google.android.c2dm.intent.RECEIVE" in actions)
        assertTrue("com.google.android.c2dm.intent.REGISTRATION" in actions)
    }

    /** An old Play Services: the library skips its second acknowledgement. */
    private fun installPlayServices() {
        val info = PackageInfo().apply {
            packageName = "com.google.android.gms"
            longVersionCode = 1
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun manifestReceivers(): List<Element> {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile } ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val receivers = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("receiver")
        return (0 until receivers.length).map { receivers.item(it) as Element }
    }
}
