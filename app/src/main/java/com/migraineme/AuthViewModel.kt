// FILE: app/src/main/java/com/migraineme/AuthViewModel.kt
package com.migraineme

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AuthState(
    val accessToken: String? = null,
    val userId: String? = null,
    val error: String? = null
)

class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    fun setSession(accessToken: String?, userId: String?) {
        _state.update { it.copy(accessToken = accessToken, userId = userId, error = null) }
    }

    fun clearSession() {
        _state.update { AuthState() }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    /**
     * UI-safe token accessor.
     *
     * The core problem you hit is: workers refresh tokens via SessionStore.getValidAccessToken(),
     * but the UI continues using authVm.state.accessToken which may have expired.
     *
     * This method:
     *  - fetches a valid token (refreshing if needed),
     *  - ensures userId is persisted + present,
     *  - updates AuthViewModel state if token/userId changed.
     *
     * Use this for any UI fetch path (tables/cards/screens) that currently relies on authState.accessToken.
     */
    suspend fun getValidAccessToken(context: Context): String? {
        val appCtx = context.applicationContext

        val valid = SessionStore.getValidAccessToken(appCtx)
        if (valid.isNullOrBlank()) {
            // If we cannot refresh, reflect that to UI by clearing session.
            // (LogoutScreen already clears SessionStore; this mirrors that state.)
            _state.update { it.copy(accessToken = null, userId = null) }
            return null
        }

        // Ensure user id is present and stable.
        var uid = SessionStore.readUserId(appCtx)
        if (uid.isNullOrBlank()) {
            uid = JwtUtils.extractUserIdFromAccessToken(valid)
            if (!uid.isNullOrBlank()) {
                SessionStore.saveUserId(appCtx, uid)
            }
        }

        // If SessionStore refreshed/rotated the access token, push it into UI state.
        _state.update { st ->
            val tokenChanged = st.accessToken != valid
            val userChanged = (!uid.isNullOrBlank() && st.userId != uid)
            if (tokenChanged || userChanged) {
                st.copy(accessToken = valid, userId = uid, error = null)
            } else {
                st
            }
        }

        return valid
    }

    /**
     * Convenience: update state from persisted SessionStore without forcing an immediate refresh.
     * (Still uses getValidAccessToken under the hood so it stays correct if token is expired.)
     */
    suspend fun syncFromSessionStore(context: Context) {
        getValidAccessToken(context)
    }

    fun signOut() {
        // UI-only signout state. Callers already clear SessionStore explicitly (see LogoutScreen).
        clearSession()
    }
}
