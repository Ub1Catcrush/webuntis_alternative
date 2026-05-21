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
                    _loginState.value = LoginState.Error(it.message ?: context.getString(R.string.error_unknown))
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

    private val _secondAccountState = MutableStateFlow<SecondAccountState>(SecondAccountState.Idle)
    val secondAccountState: StateFlow<SecondAccountState> = _secondAccountState

    /**
     * Called by SettingsFragment on open when a second account already exists in
     * SessionManager. Primes the StateFlow with Saved so the UI collector renders
     * the stored account without requiring a Force Close / re-launch.
     */
    /** Re-fetches the timetable after a settings change (e.g. day count). */
    fun refreshTimetable() {
        viewModelScope.launch { repository.getTwoSchoolDays() }
    }

    fun primeSecondAccountState() {
        val second = sessionManager.secondAccount ?: return
        val info = buildString {
            if (second.personName.isNotBlank()) append(second.personName)
            if (second.accountTypeLabel.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(second.accountTypeLabel)
            }
            if (second.label.isNotBlank()) {
                if (isNotEmpty()) append(" (")
                append(second.label)
                append(")")
            }
            if (isEmpty()) append(second.username)
        }
        _secondAccountState.value = SecondAccountState.Saved(info)
    }

    fun saveSecondAccount(username: String, password: String, label: String) {
        viewModelScope.launch {
            _secondAccountState.value = SecondAccountState.Loading
            repository.verifyAndSaveSecondAccount(username, password, label).fold(
                onSuccess = { info -> _secondAccountState.value = SecondAccountState.Saved(info) },
                onFailure = { _secondAccountState.value = SecondAccountState.Error(it.message ?: context.getString(R.string.error_generic)) }
            )
        }
    }

    fun removeSecondAccount() {
        sessionManager.secondAccount = null
        _secondAccountState.value = SecondAccountState.Removed
    }
}

sealed class LoginState {
    object Idle    : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}

sealed class SecondAccountState {
    object Idle    : SecondAccountState()
    object Loading : SecondAccountState()
    object Removed : SecondAccountState()
    data class Saved(val info: String) : SecondAccountState()
    data class Error(val message: String) : SecondAccountState()
}
