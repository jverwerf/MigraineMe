// FILE: app/src/main/java/com/migraineme/AuthViewModel.kt
package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /**
     * Fixes "needs two taps" logout:
     * - Clear local session immediately so UI updates / navigation guards stop treating user as signed in
     * - Perform remote signout in background (best-effort)
     */
    fun signOut() {
        val token = _state.value.accessToken

        // Clear local session immediately (prevents Login screen from auto-routing back to Home)
        clearSession()

        // Best-effort remote sign-out (does not block UI)
        if (!token.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    SupabaseAuthService.signOut(token)
                } catch (_: Exception) {
                    // ignore remote signout errors
                }
            }
        }
    }
}
