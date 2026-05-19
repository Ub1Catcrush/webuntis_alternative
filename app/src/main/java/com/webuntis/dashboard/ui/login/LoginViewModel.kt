package com.webuntis.dashboard.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.WebUntisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    val sessionManager: SessionManager,
    val repository: WebUntisRepository
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    // Guard against concurrent login attempts (init + explicit call)
    @Volatile private var isLoggingIn = false

    init {
        // On app start: silently re-authenticate with stored credentials once.
        val session = sessionManager.session
        val creds   = sessionManager.storedCredentials
        if (session != null && creds != null && !isLoggingIn) {
            isLoggingIn = true
            viewModelScope.launch {
                _loginState.value = LoginState.Loading
                val result = repository.login(
                    session.server, session.schoolname,
                    creds.first, creds.second
                )
                _loginState.value = LoginState.Idle
                _isLoggedIn.value = result.isSuccess
                isLoggingIn = false
            }
        }
    }

    fun login(server: String, schoolname: String, username: String, password: String) {
        if (isLoggingIn) return
        isLoggingIn = true
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            val cleanServer = server.trim()
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
            val result = repository.login(cleanServer, schoolname.trim(), username.trim(), password)
            result.fold(
                onSuccess = {
                    _isLoggedIn.value = true
                    _loginState.value = LoginState.Success
                    isLoggingIn = false
                },
                onFailure = {
                    _loginState.value = LoginState.Error(it.message ?: "Unbekannter Fehler")
                    isLoggingIn = false
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _isLoggedIn.value = false
            _loginState.value = LoginState.Idle
        }
    }
}

sealed class LoginState {
    object Idle    : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}
