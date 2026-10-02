package de.corespace.shroud.core.push.unifiedpush

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import de.corespace.shroud.core.push.Distributor

/** Installed apps that receive [UnifiedPushProtocol.ACTION_REGISTER]. This package is left out. */
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
        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val label = info.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            Distributor(packageName, label)
        }.distinctBy { it.packageName }
    }
}
