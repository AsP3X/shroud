package de.corespace.shroud.core.push.unifiedpush

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import de.corespace.shroud.core.push.Distributor
import de.corespace.shroud.core.push.EmbeddedFcm

/** Installed apps that receive [UnifiedPushProtocol.ACTION_REGISTER]. */
fun interface DistributorDirectory {
    fun distributors(): List<Distributor>
}

class AndroidDistributorDirectory(private val context: Context) : DistributorDirectory {
    override fun distributors(): List<Distributor> {
        val manager = context.packageManager
        val intent = Intent(UnifiedPushProtocol.ACTION_REGISTER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryBroadcastReceivers(intent, 0)
        }
        val playServices = playServicesInstalled()
        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val className = info.activityInfo?.name
            val embedded = packageName == context.packageName && className == EmbeddedFcm.RECEIVER
            if (packageName == context.packageName && !embedded) return@mapNotNull null
            if (embedded && !playServices) return@mapNotNull null
            val label = if (embedded) {
                EmbeddedFcm.LABEL
            } else {
                info.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            }
            Distributor(packageName, label, embedded)
        }.distinctBy { it.packageName }
    }

    /** Play Services is a package on the device, not a library in this app. */
    private fun playServicesInstalled(): Boolean {
        val manager = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(EmbeddedFcm.PLAY_SERVICES, PackageManager.PackageInfoFlags.of(0))
            } else {
                manager.getPackageInfo(EmbeddedFcm.PLAY_SERVICES, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
