package de.corespace.shroud.core.push

import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.push.unifiedpush.AndroidDistributorDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The embedded distributor is offered only when Play Services is installed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DistributorDirectoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun playServicesAbsentHidesTheEmbeddedDistributor() {
        val found = AndroidDistributorDirectory(context).distributors()
        assertTrue(found.none { it.embedded })
    }

    @Test
    fun playServicesOffersTheEmbeddedDistributorByName() {
        val info = PackageInfo()
        info.packageName = EmbeddedFcm.PLAY_SERVICES
        shadowOf(context.packageManager).installPackage(info)
        val found = AndroidDistributorDirectory(context).distributors().filter { it.embedded }
        assertEquals(listOf(EmbeddedFcm.LABEL), found.map { it.label })
        assertEquals(listOf(context.packageName), found.map { it.packageName })
    }
}
