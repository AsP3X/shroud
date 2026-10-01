package de.corespace.shroud.core.storage

/**
 * Process-wide write stop for a device wipe (web `storageSeal.ts`, `web/src/storageSeal.ts:1-16`;
 * crypto spec §14; web-parity §3.3; plan §1.4).
 *
 * The wipe controller calls [seal] as its very first step. From then on every sealed-record
 * writer, media cache write and preference write checks [isSealed] and drops the write, so a
 * socket event, a push or a coroutine that outlives its screen cannot recreate a file the wipe is
 * deleting. iOS gets the same effect by halting its controllers first
 * (`ios/shroud/Services/Auth/DeviceWipeController.swift:68-81`); Android does both — the halt and
 * this flag.
 *
 * The web clears the flag by reloading the page; Android has no reload, so the wipe controller
 * calls [unseal] explicitly once the verify pass (`leftovers()`) came back empty and Welcome shows.
 * A wipe whose verify fails leaves the store sealed until the next process start.
 *
 * One instance per process: `AppContainer.storageSeal`. Modules pass it to their writers; nobody
 * else constructs one (a writer holding a second instance would never see the wipe's seal).
 * Reads and writes are volatile: a writer on any thread sees [seal] as soon as it returns. A writer
 * that already passed its check may still finish that one write; the wipe deletes locations after
 * sealing and verifies afterwards, which is what catches that window.
 */
class StorageSeal {
    @Volatile
    private var sealed = false

    /** True from [seal] until [unseal]: every writer drops its write. */
    val isSealed: Boolean
        get() = sealed

    /** Stops every write. Idempotent. */
    fun seal() {
        sealed = true
    }

    /** Lets writes through again (after a verified wipe). Idempotent. */
    fun unseal() {
        sealed = false
    }
}
