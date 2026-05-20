package com.webuntis.dashboard.ui.settings

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.databinding.FragmentSettingsBinding
import com.webuntis.dashboard.ui.login.LoginState
import com.webuntis.dashboard.ui.login.LoginViewModel
import com.webuntis.dashboard.ui.login.SecondAccountState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // Share the same LoginViewModel as MainActivity so logout propagates
    private val loginViewModel: LoginViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Populate fields from stored session/credentials
        val creds = loginViewModel.sessionManager.storedCredentials
        binding.inputPassword.hint = if (creds != null)
            getString(R.string.login_password_saved_hint)
        else getString(R.string.login_password_hint)

        binding.btnSave.setOnClickListener { saveAndReLogin() }
        binding.btnLogout.setOnClickListener { loginViewModel.logout() }

        // ── Primary account type display ──────────────────────────────────────
        val session = loginViewModel.sessionManager.session
        if (session != null) {
            binding.inputServer.setText(session.server)
            binding.inputSchoolname.setText(session.schoolname)
            binding.inputUsername.setText(loginViewModel.sessionManager.storedCredentials?.first ?: session.username)
        }

        // ── Second account ────────────────────────────────────────────────────
        val second = loginViewModel.sessionManager.secondAccount
        if (second != null) {
            binding.inputSecondLabel.setText(second.label)
            binding.inputSecondUsername.setText(second.username)
            binding.inputSecondPassword.hint = "Gespeichert – leer lassen zum Beibehalten"
            binding.btnRemoveSecond.isVisible = true
            // Show resolved type if known
            if (second.accountTypeLabel.isNotBlank()) {
                binding.statusSecond.text = "✓ ${second.personName} · ${second.accountTypeLabel}"
                binding.statusSecond.isVisible = true
            }
        }

        binding.btnSaveSecond.setOnClickListener {
            val label    = binding.inputSecondLabel.text.toString().trim()
            val username = binding.inputSecondUsername.text.toString().trim()
            val typed    = binding.inputSecondPassword.text.toString()
            val password = typed.ifBlank {
                loginViewModel.sessionManager.secondAccount?.password ?: ""
            }
            if (username.isBlank() || password.isBlank()) {
                binding.statusSecond.text = "Benutzername und Passwort erforderlich."
                binding.statusSecond.isVisible = true
                return@setOnClickListener
            }
            loginViewModel.saveSecondAccount(username, password, label)
        }

        binding.btnRemoveSecond.setOnClickListener {
            loginViewModel.removeSecondAccount()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.secondAccountState.collect { state ->
                    when (state) {
                        is SecondAccountState.Loading -> {
                            binding.btnSaveSecond.isEnabled = false
                            binding.statusSecond.text = "Verbinde…"
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Saved -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.btnRemoveSecond.isVisible = true
                            binding.statusSecond.text = "✓ Gespeichert: ${state.info}"
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Removed -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.btnRemoveSecond.isVisible = false
                            binding.inputSecondLabel.text?.clear()
                            binding.inputSecondUsername.text?.clear()
                            binding.inputSecondPassword.text?.clear()
                            binding.statusSecond.text = "Zweiter Account entfernt."
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Error -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = "Fehler: ${state.message}"
                            binding.statusSecond.isVisible = true
                        }
                        else -> {}
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.loginState.collect { state ->
                    binding.progressBar.isVisible = state is LoginState.Loading
                    binding.btnSave.isEnabled = state !is LoginState.Loading
                    when (state) {
                        is LoginState.Success -> {
                            binding.statusText.text = getString(R.string.settings_saved_ok)
                            binding.statusText.isVisible = true
                        }
                        is LoginState.Error -> {
                            binding.statusText.text = state.message
                            binding.statusText.isVisible = true
                        }
                        else -> binding.statusText.isVisible = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.isLoggedIn.collect { loggedIn ->
                    if (!loggedIn) {
                        // Logged out → navigate back to login
                        findNavController().navigate(R.id.loginFragment)
                    }
                }
            }
        }
    }

    private fun saveAndReLogin() {
        val server     = binding.inputServer.text.toString().trim()
        val schoolname = binding.inputSchoolname.text.toString().trim()
        val username   = binding.inputUsername.text.toString().trim()
        val typed      = binding.inputPassword.text.toString()
        val password   = if (typed.isBlank())
            loginViewModel.sessionManager.storedCredentials?.second ?: ""
        else typed

        if (server.isBlank() || schoolname.isBlank() || username.isBlank() || password.isBlank()) {
            // Password may be blank if user wants to keep stored password - that's fine
            val effectivePassword = password.ifBlank {
                loginViewModel.sessionManager.storedCredentials?.second
            }
            if (server.isBlank() || schoolname.isBlank() || username.isBlank() || effectivePassword.isNullOrBlank()) {
                binding.statusText.text = getString(R.string.error_fill_all_fields)
                binding.statusText.isVisible = true
                return
            }
        }
        // Clear old session so RetrofitFactory uses the new server
        loginViewModel.sessionManager.clearSession()
        loginViewModel.login(server, schoolname, username, password)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
