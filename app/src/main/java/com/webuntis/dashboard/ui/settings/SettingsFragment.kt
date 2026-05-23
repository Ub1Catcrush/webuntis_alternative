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

        // ── Timetable days slider ─────────────────────────────────────────────
        val current = loginViewModel.sessionManager.timetableDays
        binding.seekerTimetableDays.max = SessionManager.MAX_TIMETABLE_DAYS - SessionManager.MIN_TIMETABLE_DAYS
        binding.seekerTimetableDays.progress = current - SessionManager.MIN_TIMETABLE_DAYS
        binding.textTimetableDaysValue.text = current.toString()

        binding.seekerTimetableDays.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val days = progress + SessionManager.MIN_TIMETABLE_DAYS
                    binding.textTimetableDaysValue.text = days.toString()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val days = (sb?.progress ?: 0) + SessionManager.MIN_TIMETABLE_DAYS
                    loginViewModel.sessionManager.timetableDays = days
                    loginViewModel.refreshTimetable()
                }
            }
        )

        // ── Long names toggle ────────────────────────────────────────────────
        binding.switchLongNames.isChecked = loginViewModel.sessionManager.showLongNames
        binding.switchLongNames.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongNames = checked
            // Caches contain shortNames only in display strings — bust them so
            // the adapter re-binds with the new setting on next load.
            loginViewModel.clearAllCaches()
            loginViewModel.refreshTimetable()
        }

        // ── Cache TTL slider ──────────────────────────────────────────────────
        fun cacheTtlLabel(min: Int) = if (min == 0)
            getString(R.string.settings_cache_off)
        else
            getString(R.string.settings_cache_minutes, min)

        val currentTtl = loginViewModel.sessionManager.cacheTtlMinutes
        binding.seekerCacheTtl.max = SessionManager.MAX_CACHE_TTL      // 0..60
        binding.seekerCacheTtl.progress = currentTtl
        binding.textCacheTtlValue.text = cacheTtlLabel(currentTtl)

        binding.seekerCacheTtl.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.textCacheTtlValue.text = cacheTtlLabel(progress)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val ttl = sb?.progress ?: SessionManager.DEFAULT_CACHE_TTL
                    loginViewModel.sessionManager.cacheTtlMinutes = ttl
                    // Invalidate all caches so the new TTL takes effect immediately
                    loginViewModel.clearAllCaches()
                }
            }
        )

        // ── Primary account type display ──────────────────────────────────────
        val session = loginViewModel.sessionManager.session
        if (session != null) {
            binding.inputServer.setText(session.server)
            binding.inputSchoolname.setText(session.schoolname)
            binding.inputUsername.setText(loginViewModel.sessionManager.storedCredentials?.first ?: session.username)
        }

        // ── Second account ────────────────────────────────────────────────────
        // If an account is already stored, prime the StateFlow with Saved so the
        // collector below is the single source of truth and updates the UI correctly
        // when the fragment opens — regardless of whether we just saved it or it was
        // already there from a previous session.
        val second = loginViewModel.sessionManager.secondAccount
        if (second != null) {
            loginViewModel.primeSecondAccountState()
        }

        binding.btnSaveSecond.setOnClickListener {
            val label    = binding.inputSecondLabel.text.toString().trim()
            val username = binding.inputSecondUsername.text.toString().trim()
            val typed    = binding.inputSecondPassword.text.toString()
            val password = typed.ifBlank {
                loginViewModel.sessionManager.secondAccount?.password ?: ""
            }
            if (username.isBlank() || password.isBlank()) {
                binding.statusSecond.text = getString(R.string.settings_second_error_credentials)
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
                            binding.statusSecond.text = getString(R.string.settings_second_connecting)
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Saved -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.btnRemoveSecond.isVisible = true
                            binding.statusSecond.text = "✓ ${state.info}"
                            binding.statusSecond.isVisible = true
                            // Populate input fields from stored account so the user
                            // sees what is saved (covers the "primed on open" case)
                            val stored = loginViewModel.sessionManager.secondAccount
                            if (stored != null) {
                                if (binding.inputSecondLabel.text.isNullOrBlank())
                                    binding.inputSecondLabel.setText(stored.label)
                                if (binding.inputSecondUsername.text.isNullOrBlank())
                                    binding.inputSecondUsername.setText(stored.username)
                                binding.inputSecondPassword.hint =
                                    getString(R.string.settings_second_password_saved_hint)
                            }
                        }
                        is SecondAccountState.Removed -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.btnRemoveSecond.isVisible = false
                            binding.inputSecondLabel.text?.clear()
                            binding.inputSecondUsername.text?.clear()
                            binding.inputSecondPassword.text?.clear()
                            binding.statusSecond.text = getString(R.string.settings_second_removed)
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Error -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = getString(R.string.settings_second_error_prefix, state.message)
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
        // Clear only the primary session — second account is intentionally preserved
        loginViewModel.sessionManager.clearSession()
        loginViewModel.login(server, schoolname, username, password)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
