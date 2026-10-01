package de.corespace.shroud.core.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.model.deviceUuid
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.Locale

/**
 * Keeps this phone's sealed name on the server in step with its real name
 * (`ios/shroud/Services/Auth/DeviceNameSync.swift:5-46`; settings-lock §5.3; web-parity §4.3–4.4;
 * crypto §17.2; 00-plan C37).
 *
 * The app does not ask for a name; it uses the one the phone already has. The name only leaves the
 * phone sealed ([DeviceNameSeal]) after an unlock, because the key comes from the phrase. One
 * `/auth/me` per unlock; a write only when the name changed. A name someone chose (Settings ›
 * Devices, here or on another client) is kept — except that a custom name whose kind an older client
 * dropped gets kind 4 back ([labelToWrite], web-parity §4.4). Never logs the name.
 */
object DeviceNameSync {
    private val inFlight = Mutex()

    /**
     * Best effort, single-flight (`syncIfNeeded`, `DeviceNameSync.swift:17-33`): offline or a server
     * error leaves it for the next unlock. The shell calls it after every unlock and at launch when
     * unlocked (through `AuthModule.syncDeviceName`, which supplies [wanted] = [currentLabel]).
     *
     * @param historyKey the account's history key; read only, never kept.
     */
    suspend fun syncIfNeeded(api: ShroudApi, session: Session, historyKey: ByteArray, wanted: DeviceNameSeal.Label) {
        if (!inFlight.tryLock()) return
        try {
            val deviceId = session.deviceUuid
            val me = api.me(session.token)
            if (me.device.id != deviceId) return
            val stored = DeviceNameSeal.open(me.device.sealedName, deviceId, historyKey)
            val label = labelToWrite(stored, wanted) ?: return
            val sealed = DeviceNameSeal.seal(label, deviceId, historyKey)
            api.putDeviceName(session.token, deviceId, sealed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Next unlock tries again.
        } finally {
            inFlight.unlock()
        }
    }

    /**
     * What to write, or null for nothing (web-parity §21.2, the iOS rule `stored == wanted ||
     * stored.custom` plus the kind-4 repair): a name somebody typed wins, but if an older iOS or web
     * client renamed this phone it wrote back kind "other"; the same name, still custom, is re-sealed
     * with kind [DeviceNameSeal.Kind.Android].
     */
    fun labelToWrite(stored: DeviceNameSeal.Label?, wanted: DeviceNameSeal.Label): DeviceNameSeal.Label? = when {
        stored == wanted -> null
        stored?.custom == true && stored.kind == DeviceNameSeal.Kind.Android -> null
        stored?.custom == true -> stored.copy(kind = DeviceNameSeal.Kind.Android)
        else -> wanted
    }

    /**
     * The phone's own name, else its model, kind [DeviceNameSeal.Kind.Android] (`currentLabel()`,
     * `DeviceNameSync.swift:35-45`): `Settings.Global.DEVICE_NAME` — the name the user set in *About
     * phone* (API 25+, no permission) — else the manufacturer and model. Never the Bluetooth name (it
     * needs a permission). Tablets use the same kind (P4).
     */
    fun currentLabel(context: Context): DeviceNameSeal.Label {
        val assigned = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return label(assigned, Build.MANUFACTURER, Build.MODEL)
    }

    /**
     * [currentLabel] from its inputs: [assigned] normalised; when nothing is left, [model] prefixed with
     * the capitalised [manufacturer] unless it already starts with it ("Samsung SM-S918B"; "Pixel 9a"
     * on a phone whose manufacturer is not part of the model is "Google Pixel 9a" — Pixels set
     * `DEVICE_NAME` to the model, so this fallback is rare); "Android" if even that is empty.
     */
    fun label(assigned: String?, manufacturer: String?, model: String?): DeviceNameSeal.Label {
        val name = DeviceNameSeal.normalize(assigned.orEmpty()).ifEmpty { DeviceNameSeal.normalize(modelName(manufacturer, model)) }
        return DeviceNameSeal.Label(name.ifEmpty { FALLBACK_NAME }, DeviceNameSeal.Kind.Android)
    }

    private fun modelName(manufacturer: String?, model: String?): String {
        val maker = manufacturer.orEmpty().trim()
        val device = model.orEmpty().trim()
        if (maker.isEmpty() || device.startsWith(maker, ignoreCase = true)) return device
        val capitalised = maker.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return "$capitalised $device".trim()
    }

    private const val FALLBACK_NAME = "Android"
}
