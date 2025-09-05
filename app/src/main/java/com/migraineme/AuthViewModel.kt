package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val accessToken: String? = null,
    val userId: String? = null,
    val error: String? = null
)

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    private val authService = SupabaseAuthService()

    fun setSession(accessToken: String?, userId: String?) {
        _state.value = _state.value.copy(
            accessToken = accessToken,
            userId = userId,
            error = null
        )
    }

    fun signOut() {
        val token = _state.value.accessToken
        viewModelScope.launch {
            try {
                if (token != null) authService.signOut(token)
            } catch (_: Exception) {
                // optional: surface error
            } finally {
                _state.value = AuthState(accessToken = null, userId = null, error = null)
            }
        }
    }
}
