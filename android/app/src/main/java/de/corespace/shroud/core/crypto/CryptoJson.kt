package de.corespace.shroud.core.crypto

import kotlinx.serialization.json.Json

/**
 * The JSON of envelopes, ratchet messages and every sealed local record — **not**
 * `AppContainer.json`, whose `explicitNulls = true` would write `"t":null` / `"ek":null` (crypto
 * spec §1.4).
 *
 * - `explicitNulls = false`: nil optionals are left out, as Swift's `encodeIfPresent` does.
 * - `encodeDefaults = false`: a property at its default is left out too, so a record only
 *   carries what was set.
 * - `ignoreUnknownKeys = true`: a newer client's extra field never breaks an older reader.
 *
 * Swift's `JSONEncoder` writes keys in no fixed order and escapes `/` as `\/`; readers here must
 * not depend on either, and tests compare parsed structures, never raw envelope strings.
 */
val CryptoJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}
