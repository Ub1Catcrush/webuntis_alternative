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
import com.webuntis.dashboard.BuildConfig
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.UpdateManager
import com.webuntis.dashboard.databinding.FragmentSettingsBinding
import com.webuntis.dashboard.ui.login.LoginState
import com.webuntis.dashboard.ui.login.LoginViewModel
import com.webuntis.dashboard.ui.login.SecondAccountState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val loginViewModel: LoginViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    @Inject
    lateinit var updateManager: UpdateManager

    // ── Export / Import launchers ─────────────────────────────────────────────
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = loginViewModel.sessionManager.exportSettings()
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_export_ok), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@registerForActivityResult
            when (val result = loginViewModel.sessionManager.importSettings(json)) {
                is com.webuntis.dashboard.api.SessionManager.ImportResult.Success -> {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.settings_import_ok), android.widget.Toast.LENGTH_LONG).show()
                    val session = loginViewModel.sessionManager.session
                    val creds   = loginViewModel.sessionManager.storedCredentials
                    if (session != null && creds != null) {
                        loginViewModel.login(session.server, session.schoolname, creds.first, creds.second)
                    }
                    bindCurrentValues()
                    if (result.secondUpdated) loginViewModel.primeSecondAccountState()
                }
                is com.webuntis.dashboard.api.SessionManager.ImportResult.Error ->
                    android.widget.Toast.makeText(requireContext(), result.message, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_import_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSave.setOnClickListener { saveAndReLogin() }
        binding.btnLogout.setOnClickListener { loginViewModel.logout() }
        binding.btnExportSettings.setOnClickListener { exportLauncher.launch("webuntis_settings.json") }
        binding.btnImportSettings.setOnClickListener { importLauncher.launch("application/json") }

        // ── Update Section ────────────────────────────────────────────────────
        binding.textVersionInfo.text = getString(R.string.settings_update_version_info, BuildConfig.VERSION_NAME)
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }

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

        // ── Compact Week View toggle ──────────────────────────────────────────
        binding.switchCompactWeekView.isChecked = loginViewModel.sessionManager.useCompactWeekView
        binding.switchCompactWeekView.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.useCompactWeekView = checked
            // No need to clear cache, just refresh UI
            loginViewModel.refreshTimetable()
        }

        // ── Long names toggles (per type) ─────────────────────────────────
        binding.switchLongSubjects.isChecked = loginViewModel.sessionManager.showLongSubjects
        binding.switchLongSubjects.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongSubjects = checked
            loginViewModel.clearAllCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchLongTeachers.isChecked = loginViewModel.sessionManager.showLongTeachers
        binding.switchLongTeachers.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongTeachers = checked
            loginViewModel.clearAllCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchLongRooms.isChecked = loginViewModel.sessionManager.showLongRooms
        binding.switchLongRooms.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongRooms = checked
            loginViewModel.clearAllCaches(); loginViewModel.refreshTimetable()
        }

        // ── Cache TTL slider ──────────────────────────────────────────────────
        fun cacheTtlLabel(min: Int) = if (min == 0)
            getString(R.string.settings_cache_off)
        else
            getString(R.string.settings_cache_minutes, min)

        val currentTtl = loginViewModel.sessionManager.cacheTtlMinutes
        binding.seekerCacheTtl.max = SessionManager.MAX_CACHE_TTL
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
                    loginViewModel.clearAllCaches()
                }
            }
        )

        bindCurrentValues()

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
                            binding.statusSecond.text = getString(R.string.settings_second_saved_prefix, state.info)
                            binding.statusSecond.isVisible = true
                            val stored = loginViewModel.sessionManager.secondAccount
                            if (stored != null) {
                                if (binding.inputSecondLabel.text.isNullOrBlank())
                                    binding.inputSecondLabel.setText(stored.label)
                                if (binding.inputSecondUsername.text.isNullOrBlank())
                                    binding.inputSecondUsername.setText(stored.username)
                                binding.inputSecondPasswordLayout.hint =
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
                        findNavController().navigate(R.id.loginFragment)
                    }
                }
            }
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.settings_update_checking)

        viewLifecycleOwner.lifecycleScope.launch {
            updateManager.checkForUpdates().onSuccess { info ->
                if (info.hasUpdate && info.downloadUrl != null) {
                    binding.btnCheckUpdate.text = getString(R.string.settings_update_download_install)
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.setOnClickListener {
                        updateManager.downloadAndInstall(info.downloadUrl, "webuntis-dashboard-${info.latestVersion}.apk")
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_update_download_started), android.widget.Toast.LENGTH_SHORT).show()
                    }

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.settings_update_dialog_title))
                        .setMessage(getString(R.string.settings_update_dialog_message, info.latestVersion, info.releaseNotes ?: ""))
                        .setPositiveButton(getString(R.string.settings_update_dialog_install)) { _, _ ->
                             updateManager.downloadAndInstall(info.downloadUrl, "webuntis-dashboard-${info.latestVersion}.apk")
                        }
                        .setNegativeButton(getString(R.string.settings_update_dialog_later), null)
                        .show()
                } else {
                    binding.btnCheckUpdate.text = getString(R.string.settings_update_no_update)
                    binding.btnCheckUpdate.isEnabled = false
                }
            }.onFailure {
                binding.btnCheckUpdate.text = getString(R.string.settings_update_error)
                binding.btnCheckUpdate.isEnabled = true
                android.widget.Toast.makeText(requireContext(), getString(R.string.settings_update_check_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindCurrentValues() {
        val session = loginViewModel.sessionManager.session
        if (session != null) {
            binding.inputServer.setText(session.server)
            binding.inputSchoolname.setText(session.schoolname)
            binding.inputUsername.setText(
                loginViewModel.sessionManager.storedCredentials?.first ?: session.username)
        }
        binding.inputPassword.hint = if (loginViewModel.sessionManager.storedCredentials != null)
            getString(R.string.login_password_saved_hint)
        else getString(R.string.login_password_hint)
        val second = loginViewModel.sessionManager.secondAccount
        if (second != null) {
            loginViewModel.primeSecondAccountState()
            if (binding.inputSecondUsername.text.isNullOrBlank())
                binding.inputSecondUsername.setText(second.username)
            if (binding.inputSecondLabel.text.isNullOrBlank())
                binding.inputSecondLabel.setText(second.label)
            binding.inputSecondPasswordLayout.hint = getString(R.string.settings_second_password_saved_hint)
            binding.btnRemoveSecond.isVisible = true
        } else {
            binding.inputSecondPasswordLayout.hint = getString(R.string.settings_second_password_hint)
        }
    }

    private fun saveAndReLogin() {
        val server     = binding.inputServer.text.toString().trim()
        val schoolname = binding.inputSchoolname.text.toString().trim()
        val username   = binding.inputUsername.text.toString().trim()
        val typed      = binding.inputPassword.text.toString()
        // Use stored password when field is left blank (common case when only changing other fields)
        val password   = typed.ifBlank {
            loginViewModel.sessionManager.storedCredentials?.second ?: ""
        }

        if (server.isBlank() || schoolname.isBlank() || username.isBlank() || password.isBlank()) {
            binding.statusText.text = getString(R.string.error_fill_all_fields)
            binding.statusText.isVisible = true
            return
        }
        // Do NOT clearSession() here — it would wipe storedCredentials before login() saves them
        loginViewModel.login(server, schoolname, username, password)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
