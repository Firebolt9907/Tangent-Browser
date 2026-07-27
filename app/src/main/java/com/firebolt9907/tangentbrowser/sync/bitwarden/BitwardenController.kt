package com.firebolt9907.tangentbrowser.sync.bitwarden

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry

/** Where the connection currently sits, for the settings/connect UI. */
enum class BitwardenConnectionState { DISCONNECTED, CONNECTED, NEEDS_TWO_FACTOR, NEEDS_CAPTCHA }

data class BitwardenUiState(
    val state: BitwardenConnectionState = BitwardenConnectionState.DISCONNECTED,
    val email: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val twoFactorProviders: List<Int> = emptyList(),
    val captchaSiteKey: String? = null,
    val lastSyncAtMs: Long? = null,
)

/**
 * Orchestrates the Bitwarden account: connect (password or API key), 2FA, unlock, sync, write-back,
 * and disconnect. Owns token freshness so callers ([com.firebolt9907.tangentbrowser.sync.MergedLoginsStorage],
 * the settings sheet) never touch raw tokens. Reads are served in-memory from [loginsStore]; writes
 * route through here so a valid access token is always ensured first.
 *
 * Modeled on [com.firebolt9907.tangentbrowser.addons.AddonController] — a StateFlow the Compose UI
 * observes plus fire-and-forget actions launched on [scope].
 */
class BitwardenController(
    private val accountStore: BitwardenAccountStore,
    private val scope: CoroutineScope,
    httpClient: Client,
) {
    private val api = BitwardenApi(httpClient)
    val loginsStore = BitwardenLoginsStore(api, accountStore)

    /** The FIDO2/passkey view over the same vault (reads `fido2Credentials`, encodes new ones). */
    val passkeyStore = BitwardenPasskeyStore(accountStore) { loginsStore.currentUserKey }

    /** The unlocked user key, or null while locked — needed to wrap a new passkey cipher's key. */
    val currentUserKey: BitwardenCrypto.SymmetricKey? get() = loginsStore.currentUserKey

    private val _uiState = MutableStateFlow(BitwardenUiState())
    val uiState: StateFlow<BitwardenUiState> = _uiState.asStateFlow()

    /** A pending password-grant attempt awaiting a 2FA code (never persisted). */
    private data class Pending(
        val credentials: BitwardenCredentials,
        val masterKey: ByteArray,
        val kdf: BitwardenCrypto.KdfParams,
        val endpoints: BitwardenEndpoints,
    )
    @Volatile private var pending: Pending? = null

    val isUsable: Boolean
        get() = _uiState.value.state == BitwardenConnectionState.CONNECTED && loginsStore.isUnlocked

    init {
        if (accountStore.isConnected) {
            // Silent open: unseal the user key (already protected at rest) so autofill works across
            // restarts, consistent with the Firefox/local store. Biometric gating happens at reveal/
            // fill time in the UI, not here.
            val secrets = accountStore.loadSecrets()
            val key = secrets?.userKey
            if (key != null) loginsStore.unlock(key)
            _uiState.update {
                it.copy(state = BitwardenConnectionState.CONNECTED, email = accountStore.email)
            }
            if (key != null) scope.launch { runCatching { syncInternal() } }
        }
    }

    // --- connect / 2FA ------------------------------------------------------

    fun connect(credentials: BitwardenCredentials) {
        scope.launch {
            busy("Connecting to Bitwarden…")
            runCatching { doConnect(credentials) }.onFailure { fail(it.message ?: "Connection failed") }
        }
    }

    fun submitTwoFactor(code: String, provider: Int) {
        val p = pending ?: run { fail("No sign-in is awaiting a code"); return }
        scope.launch {
            busy("Verifying code…")
            val creds = p.credentials.copy(twoFactorToken = code.trim(), twoFactorProvider = provider)
            val hash = BitwardenCrypto.masterPasswordHash(p.masterKey, creds.masterPassword)
            runCatching {
                when (val r = api.token(p.endpoints, creds, hash, accountStore.deviceIdentifier)) {
                    is BitwardenTokenResult.Success ->
                        finishConnect(creds, p.masterKey, p.kdf, p.endpoints, r.token)
                    is BitwardenTokenResult.TwoFactorRequired -> setTwoFactor(r.providers)
                    is BitwardenTokenResult.CaptchaRequired -> setCaptcha(r.siteKey)
                    is BitwardenTokenResult.Error -> fail(r.message)
                }
            }.onFailure { fail(it.message ?: "Verification failed") }
        }
    }

    private suspend fun doConnect(credentials: BitwardenCredentials) {
        val endpoints = BitwardenEndpoints.forRegion(credentials.region, credentials.selfHostedBaseUrl)
        val kdf = api.prelogin(endpoints, credentials.email)
        val masterKey = withContext(Dispatchers.Default) {
            BitwardenCrypto.deriveMasterKey(credentials.masterPassword, credentials.email, kdf)
        }
        val hash = if (credentials.method == BitwardenAuthMethod.PASSWORD) {
            BitwardenCrypto.masterPasswordHash(masterKey, credentials.masterPassword)
        } else {
            null
        }
        when (val r = api.token(endpoints, credentials, hash, accountStore.deviceIdentifier)) {
            is BitwardenTokenResult.Success -> finishConnect(credentials, masterKey, kdf, endpoints, r.token)
            is BitwardenTokenResult.TwoFactorRequired -> {
                pending = Pending(credentials, masterKey, kdf, endpoints)
                setTwoFactor(r.providers)
            }
            is BitwardenTokenResult.CaptchaRequired -> setCaptcha(r.siteKey)
            is BitwardenTokenResult.Error -> fail(r.message)
        }
    }

    private suspend fun finishConnect(
        credentials: BitwardenCredentials,
        masterKey: ByteArray,
        kdf: BitwardenCrypto.KdfParams,
        endpoints: BitwardenEndpoints,
        token: BitwardenToken,
    ) {
        // The protected key comes back on the token for most accounts; if not, /sync carries it.
        val protectedKey = token.protectedKey ?: api.sync(endpoints, token.accessToken).protectedKey
        ?: error("Account is missing its encryption key")
        val stretched = BitwardenCrypto.stretchMasterKey(masterKey)
        val userKey = BitwardenCrypto.decryptUserKey(protectedKey, stretched)

        accountStore.saveMetadata(
            credentials.email, credentials.region, credentials.selfHostedBaseUrl, credentials.method, kdf,
        )
        accountStore.saveSecrets(
            BitwardenAccountStore.Secrets(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtMs = token.expiresAtMs,
                userKey = userKey,
                clientId = credentials.clientId,
                clientSecret = credentials.clientSecret,
            ),
        )
        pending = null
        loginsStore.unlock(userKey)
        loginsStore.sync(token.accessToken)
        _uiState.update {
            it.copy(
                state = BitwardenConnectionState.CONNECTED,
                email = accountStore.email,
                isBusy = false,
                message = "Bitwarden connected",
                isError = false,
                twoFactorProviders = emptyList(),
                captchaSiteKey = null,
                lastSyncAtMs = System.currentTimeMillis(),
            )
        }
    }

    // --- sync / write-back --------------------------------------------------

    fun syncNow() {
        scope.launch {
            busy("Syncing vault…")
            runCatching { syncInternal() }
                .onSuccess { status("Vault synced") }
                .onFailure { fail(it.message ?: "Sync failed") }
        }
    }

    private suspend fun syncInternal() {
        val token = freshAccessToken() ?: return
        loginsStore.sync(token)
        _uiState.update { it.copy(lastSyncAtMs = System.currentTimeMillis()) }
    }

    /** Create or update a login in the vault (matched by origin host + username). Null if unusable. */
    suspend fun saveLogin(entry: LoginEntry): Login? {
        if (!isUsable) return null
        val token = freshAccessToken() ?: return null
        val host = hostOf(entry.origin)
        val existing = loginsStore.getByBaseDomain(host)
            .firstOrNull { it.username.equals(entry.username, ignoreCase = true) }
        return if (existing != null) {
            loginsStore.update(token, existing.guid, entry)
        } else {
            loginsStore.add(token, entry)
        }
    }

    suspend fun updateLogin(id: String, entry: LoginEntry): Login? {
        val token = freshAccessToken() ?: return null
        return loginsStore.update(token, id, entry)
    }

    suspend fun deleteLogin(id: String): Boolean {
        val token = freshAccessToken() ?: return false
        return loginsStore.delete(token, id)
    }

    /**
     * POST a freshly-encoded passkey cipher (from [BitwardenPasskeyStore.encodeNewCipher]) to the
     * vault and refresh the local cache so the new passkey is immediately usable for a get(). Returns
     * the new cipher id, or null if the account is unusable / the write failed.
     */
    suspend fun createPasskeyCipher(body: org.json.JSONObject): String? {
        if (!isUsable) return null
        val token = freshAccessToken() ?: return null
        return runCatching {
            val id = api.createCipher(accountStore.endpoints, token, body)
            runCatching { syncInternal() } // best-effort: refresh cache so findForRp sees it
            id
        }.getOrNull()
    }

    fun disconnect() {
        pending = null
        loginsStore.lock()
        accountStore.clear()
        _uiState.value = BitwardenUiState()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, isError = false) }
    }

    /** Ensure a non-expired access token, refreshing (password) or re-authing (API key) as needed. */
    private suspend fun freshAccessToken(): String? {
        val secrets = accountStore.loadSecrets() ?: return null
        if (secrets.expiresAtMs > System.currentTimeMillis() + 30_000L) return secrets.accessToken

        val endpoints = accountStore.endpoints
        val refreshed: BitwardenToken? = when {
            secrets.refreshToken != null ->
                (api.refresh(endpoints, secrets.refreshToken) as? BitwardenTokenResult.Success)?.token
            secrets.clientId != null && secrets.clientSecret != null ->
                (
                    api.token(
                        endpoints,
                        BitwardenCredentials(
                            method = BitwardenAuthMethod.API_KEY,
                            region = accountStore.region,
                            selfHostedBaseUrl = accountStore.selfHostedBaseUrl,
                            email = accountStore.email.orEmpty(),
                            masterPassword = "",
                            clientId = secrets.clientId,
                            clientSecret = secrets.clientSecret,
                        ),
                        masterPasswordHash = null,
                        deviceIdentifier = accountStore.deviceIdentifier,
                    ) as? BitwardenTokenResult.Success
                    )?.token
            else -> null
        }
        if (refreshed == null) return secrets.accessToken // best effort with the stale token
        accountStore.saveSecrets(
            secrets.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: secrets.refreshToken,
                expiresAtMs = refreshed.expiresAtMs,
            ),
        )
        return refreshed.accessToken
    }

    private fun hostOf(origin: String): String =
        runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: origin.lowercase()

    // --- state helpers ------------------------------------------------------

    private fun busy(msg: String) = _uiState.update { it.copy(isBusy = true, message = msg, isError = false) }
    private fun status(msg: String) = _uiState.update { it.copy(isBusy = false, message = msg, isError = false) }
    private fun fail(msg: String) = _uiState.update { it.copy(isBusy = false, message = msg, isError = true) }
    private fun setTwoFactor(providers: List<Int>) = _uiState.update {
        it.copy(isBusy = false, state = BitwardenConnectionState.NEEDS_TWO_FACTOR, twoFactorProviders = providers, message = null)
    }
    private fun setCaptcha(siteKey: String?) = _uiState.update {
        it.copy(
            isBusy = false,
            state = BitwardenConnectionState.NEEDS_CAPTCHA,
            captchaSiteKey = siteKey,
            isError = true,
            message = "Bitwarden requires a captcha for password sign-in. Use the API key option instead (more reliable).",
        )
    }
}
