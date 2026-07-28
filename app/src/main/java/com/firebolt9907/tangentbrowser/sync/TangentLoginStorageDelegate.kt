package com.firebolt9907.tangentbrowser.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginStorageDelegate
import mozilla.components.concept.storage.LoginsStorage

/**
 * Bridges GeckoView's login autofill to our [PlacesLoginsStorage]. An instance is attached to the
 * GeckoRuntime (as part of [mozilla.components.browser.engine.gecko.autofill.GeckoAutocompleteStorageDelegate]);
 * Gecko then calls:
 *
 * - [onLoginFetch] when it thinks a login field should be autofilled — we return the stored logins
 *   for that domain so Gecko can offer/insert them;
 * - [onLoginSave] when the user submits credentials on a form — we upsert them into storage (which
 *   then syncs to the account, E2E-encrypted, on the next sync pass);
 * - [onLoginUsed] after a stored login is autofilled — we bump its usage metadata.
 *
 * All storage calls are suspend (blocking SQLite + per-record decryption under the hood), so the
 * fire-and-forget writes are dispatched on [scope] and the fetch returns a [Deferred] Gecko awaits.
 */
class TangentLoginStorageDelegate(
    private val storage: LoginsStorage,
    private val scope: CoroutineScope,
) : LoginStorageDelegate {

    override fun onLoginFetch(domain: String): Deferred<List<Login>> =
        scope.async(Dispatchers.IO) {
            runCatching { storage.getByBaseDomain(domain) }.getOrDefault(emptyList())
        }

    override fun onLoginSave(login: LoginEntry) {
        scope.launch(Dispatchers.IO) {
            // addOrUpdate dedups against an existing record (same origin + username) internally, so a
            // password change updates in place instead of piling up duplicate entries.
            runCatching { storage.addOrUpdate(login) }
        }
    }

    override fun onLoginUsed(login: Login) {
        scope.launch(Dispatchers.IO) {
            runCatching { storage.touch(login.guid) }
        }
    }
}
