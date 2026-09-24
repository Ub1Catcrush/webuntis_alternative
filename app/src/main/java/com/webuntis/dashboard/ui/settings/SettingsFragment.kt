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
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loginViewModel.sessionManager.notificationsEnabled = true
            com.webuntis.dashboard.api.NotificationScheduler.start(requireContext())
            updateBatteryOptimizationUi()
        } else {
            // Permission denied — leave the feature off and reflect that in the switch so
            // it doesn't silently claim to be enabled while no notification can ever show.
            binding.switchNotificationsEnabled.isChecked = false
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.settings_notifications_permission_denied), android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

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
                    if (result.secondUpdated) renderAdditionalAccountsList()
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

        // ── Background change-check notifications ───────────────────────────────
        binding.switchNotificationsEnabled.isChecked = loginViewModel.sessionManager.notificationsEnabled
        binding.switchNotificationsEnabled.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                loginViewModel.sessionManager.notificationsEnabled = false
                com.webuntis.dashboard.api.NotificationScheduler.stop(requireContext())
                return@setOnCheckedChangeListener
            }
            // Android 13+ requires the runtime POST_NOTIFICATIONS permission before any
            // notification (including the ones PlanChangeCheckWorker posts) can show at all.
            val needsPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                // Launcher's callback flips this back on success/off on denial — don't
                // flip sessionManager's flag until we actually know the outcome.
            } else {
                loginViewModel.sessionManager.notificationsEnabled = true
                com.webuntis.dashboard.api.NotificationScheduler.start(requireContext())
                updateBatteryOptimizationUi()
            }
        }
        updateBatteryOptimizationUi()
        binding.btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnCheckNow.setOnClickListener { runCheckNow() }

        // ── Week view: what the tile's second line shows ───────────────────────
        when (loginViewModel.sessionManager.weekViewSecondLine) {
            SessionManager.WeekViewSecondLine.SUBJECT_LONG_NAME -> binding.radioWeekSecondLineSubject.isChecked = true
            SessionManager.WeekViewSecondLine.TEACHER_LONG_NAME -> binding.radioWeekSecondLineTeacher.isChecked = true
            SessionManager.WeekViewSecondLine.NONE              -> binding.radioWeekSecondLineNone.isChecked = true
        }
        binding.radioGroupWeekViewSecondLine.setOnCheckedChangeListener { _, checkedId ->
            loginViewModel.sessionManager.weekViewSecondLine = when (checkedId) {
                R.id.radio_week_second_line_teacher -> SessionManager.WeekViewSecondLine.TEACHER_LONG_NAME
                R.id.radio_week_second_line_none    -> SessionManager.WeekViewSecondLine.NONE
                else                                 -> SessionManager.WeekViewSecondLine.SUBJECT_LONG_NAME
            }
            loginViewModel.refreshTimetable()
        }

        // ── Unterrichtsinhalte: default day-window ──────────────────────────────
        when (loginViewModel.sessionManager.lessonContentDefaultDays) {
            7  -> binding.radioLessonContentDays7.isChecked = true
            30 -> binding.radioLessonContentDays30.isChecked = true
            60 -> binding.radioLessonContentDays60.isChecked = true
            else -> binding.radioLessonContentDays14.isChecked = true
        }
        binding.radioGroupLessonContentDays.setOnCheckedChangeListener { _, checkedId ->
            loginViewModel.sessionManager.lessonContentDefaultDays = when (checkedId) {
                R.id.radio_lesson_content_days_7  -> 7
                R.id.radio_lesson_content_days_30 -> 30
                R.id.radio_lesson_content_days_60 -> 60
                else                               -> 14
            }
        }

        // ── Long names toggles (per type) ─────────────────────────────────
        binding.switchLongSubjects.isChecked = loginViewModel.sessionManager.showLongSubjects
        binding.switchLongSubjects.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongSubjects = checked
            binding.switchShortSubjectsInParens.isEnabled = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchLongTeachers.isChecked = loginViewModel.sessionManager.showLongTeachers
        binding.switchLongTeachers.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongTeachers = checked
            binding.switchShortTeachersInParens.isEnabled = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchLongRooms.isChecked = loginViewModel.sessionManager.showLongRooms
        binding.switchLongRooms.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showLongRooms = checked
            binding.switchShortRoomsInParens.isEnabled = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
        }

        // ── "Show abbreviation in parentheses" — only meaningful while the matching
        //    long-name switch above is on, so each starts disabled unless its parent is checked.
        binding.switchShortSubjectsInParens.isChecked = loginViewModel.sessionManager.showShortSubjectInParens
        binding.switchShortSubjectsInParens.isEnabled = loginViewModel.sessionManager.showLongSubjects
        binding.switchShortSubjectsInParens.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showShortSubjectInParens = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchShortTeachersInParens.isChecked = loginViewModel.sessionManager.showShortTeacherInParens
        binding.switchShortTeachersInParens.isEnabled = loginViewModel.sessionManager.showLongTeachers
        binding.switchShortTeachersInParens.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showShortTeacherInParens = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
        }
        binding.switchShortRoomsInParens.isChecked = loginViewModel.sessionManager.showShortRoomInParens
        binding.switchShortRoomsInParens.isEnabled = loginViewModel.sessionManager.showLongRooms
        binding.switchShortRoomsInParens.setOnCheckedChangeListener { _, checked ->
            loginViewModel.sessionManager.showShortRoomInParens = checked
            loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
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
                    loginViewModel.clearDataCaches()
                }
            }
        )

        bindCurrentValues()

        binding.btnSaveSecond.setOnClickListener {
            val label    = binding.inputSecondLabel.text.toString().trim()
            val username = binding.inputSecondUsername.text.toString().trim()
            val password = binding.inputSecondPassword.text.toString()
            if (username.isBlank() || password.isBlank()) {
                binding.statusSecond.text = getString(R.string.settings_second_error_credentials)
                binding.statusSecond.isVisible = true
                return@setOnClickListener
            }
            loginViewModel.saveSecondAccount(username, password, label)
        }

        renderAdditionalAccountsList()

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
                            binding.statusSecond.text = getString(R.string.settings_second_saved_prefix, state.info)
                            binding.statusSecond.isVisible = true
                            // Clear the form so it's ready for the next child, rather than
                            // looking like it's still showing/editing the one just added.
                            binding.inputSecondLabel.text?.clear()
                            binding.inputSecondUsername.text?.clear()
                            binding.inputSecondPassword.text?.clear()
                            renderAdditionalAccountsList()
                        }
                        is SecondAccountState.Removed -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = getString(R.string.settings_second_removed)
                            binding.statusSecond.isVisible = true
                            renderAdditionalAccountsList()
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
        renderAdditionalAccountsList()
    }

    /**
     * Renders one row per configured additional (child) account into layout_additional_accounts,
     * each with its own "Entfernen" button — replaces the single-account show/hide-remove-button
     * approach now that there can be several. Built programmatically rather than via a
     * RecyclerView + adapter: this list is short (a handful of children at most) and lives on an
     * already-scrolling settings screen, so the extra machinery isn't worth it here.
     */
    private fun renderAdditionalAccountsList() {
        val container = binding.layoutAdditionalAccounts
        container.removeAllViews()
        val accounts = loginViewModel.additionalAccounts
        if (accounts.isEmpty()) {
            val empty = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.settings_second_no_accounts)
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                textSize = 13f
                setPadding(0, 0, 0, 12)
            }
            container.addView(empty)
            return
        }
        accounts.forEach { account ->
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 12)
            }
            val info = buildString {
                if (account.personName.isNotBlank()) append(account.personName) else append(account.username)
                if (account.label.isNotBlank() && account.label != account.personName) {
                    append(" (").append(account.label).append(")")
                }
            }
            val label = android.widget.TextView(requireContext()).apply {
                text = info
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = com.google.android.material.button.MaterialButton(
                requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = getString(R.string.settings_second_remove_button)
                setOnClickListener { loginViewModel.removeAdditionalAccount(account.key) }
            }
            row.addView(label)
            row.addView(removeBtn)
            container.addView(row)
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

    /**
     * Shows/hides the "disable battery optimization" hint depending on both whether the
     * feature is even on and whether the OS already exempts the app. Without this exemption,
     * OEM battery managers (Samsung, Xiaomi, etc. — especially on Android 14/15, which
     * tightened background execution further) routinely kill the periodic WorkManager job
     * before it ever runs, so change-check notifications can silently never appear even
     * though the feature is correctly enabled and everything else about it works.
     */
    /**
     * Runs PlanChangeCheckWorker immediately via NotificationScheduler.checkNow() and reports
     * back once it finishes, instead of the user having to wait up to 15 minutes for the next
     * periodic slot and then having no way to tell "nothing changed" apart from "the pipeline
     * is broken" if no notification shows up.
     */
    private fun runCheckNow() {
        if (!loginViewModel.sessionManager.notificationsEnabled) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_disabled), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val requestId = com.webuntis.dashboard.api.NotificationScheduler.checkNow(requireContext())
        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_running), android.widget.Toast.LENGTH_SHORT).show()
        androidx.work.WorkManager.getInstance(requireContext())
            .getWorkInfoByIdLiveData(requestId)
            .observe(viewLifecycleOwner) { info ->
                if (info == null) return@observe
                when (info.state) {
                    androidx.work.WorkInfo.State.SUCCEEDED ->
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_done), android.widget.Toast.LENGTH_LONG).show()
                    androidx.work.WorkInfo.State.FAILED ->
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_failed), android.widget.Toast.LENGTH_LONG).show()
                    else -> {}
                }
            }
    }

    private fun updateBatteryOptimizationUi() {
        val enabled = loginViewModel.sessionManager.notificationsEnabled
        val exempted = isIgnoringBatteryOptimizations()
        val showHint = enabled && !exempted
        binding.btnBatteryOptimization.isVisible = showHint
        binding.textBatteryOptimizationHint.isVisible = showHint
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM ROMs (or a Play-distributed build's policy restrictions) can refuse this
            // intent — fall back to the general battery-settings screen so the user can still
            // find the per-app battery option manually.
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                android.widget.Toast.makeText(requireContext(), getString(R.string.settings_battery_optimization_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after coming back from the system battery settings (or from granting the
        // notification permission via the system dialog on some ROMs) so the hint disappears
        // as soon as it's no longer needed, without requiring the user to leave and re-open
        // this screen.
        if (_binding != null) updateBatteryOptimizationUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
