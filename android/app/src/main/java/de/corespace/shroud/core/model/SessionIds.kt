package de.corespace.shroud.core.model

import de.corespace.shroud.core.auth.Session
import java.util.UUID

// The persisted Session keeps its lower-case String ids (plan C1, api-realtime §5.1 migration note);
// code above the byte level reads them as UUIDs through these. A Session is only ever built from
// server ids (SessionController.adopt), so require() cannot fail on a stored one.

/** The signed-in account's user id. */
val Session.userUuid: UUID get() = Ids.require(userId)

/** This phone's device row on the account. */
val Session.deviceUuid: UUID get() = Ids.require(deviceId)
