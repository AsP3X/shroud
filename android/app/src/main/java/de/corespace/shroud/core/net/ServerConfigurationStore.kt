package de.corespace.shroud.core.net

import android.content.Context
import androidx.core.content.edit
import de.corespace.shroud.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Persists the endpoint in app-private preferences (`ServerConfigurationStore`, non-secret). */
class ServerConfigurationStore(context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("shroud.server", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val configuration: StateFlow<ServerConfiguration> = state.asStateFlow()

    fun save(configuration: ServerConfiguration) {
        prefs.edit { putString(KEY, json.encodeToString(ServerConfiguration.serializer(), configuration)) }
        state.value = configuration
    }

    private fun load(): ServerConfiguration =
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(ServerConfiguration.serializer(), it) }.getOrNull() }
            ?: default

    companion object {
        private const val KEY = "shroud.server.configuration"

        /** Debug builds default to the local Compose stack; release to the official server. */
        val default: ServerConfiguration
            get() = if (BuildConfig.DEBUG) {
                ServerConfiguration.localDevelopment(BuildConfig.DEFAULT_SELF_HOSTED_HOST, BuildConfig.DEFAULT_SELF_HOSTED_PORT)
            } else {
                ServerConfiguration.official
            }
    }
}
