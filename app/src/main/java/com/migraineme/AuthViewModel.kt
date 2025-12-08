// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\AuthViewModel.kt
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

    fun signOut() {
        val token = _state.value.accessToken
        if (token != null) {
            viewModelScope.launch {
                try {
                    SupabaseAuthService.signOut(token)
                } catch (_: Exception) {
                    // ignore remote signout errors
                } finally {
                    clearSession()
                }
            }
        } else {
            clearSession()
        }
    }
}
