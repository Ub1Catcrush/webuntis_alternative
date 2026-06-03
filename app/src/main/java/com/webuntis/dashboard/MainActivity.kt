package com.webuntis.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.webuntis.dashboard.api.UpdateManager
import com.webuntis.dashboard.databinding.ActivityMainBinding
import com.webuntis.dashboard.ui.login.LoginViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NavItem(val destinationId: Int, val iconRes: Int, val labelRes: Int)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val loginViewModel: LoginViewModel by viewModels()

    @Inject
    lateinit var updateManager: UpdateManager

    private val navItems = listOf(
        NavItem(R.id.timetableFragment,  R.drawable.ic_calendar, R.string.nav_timetable),
        NavItem(R.id.homeworkFragment,   R.drawable.ic_homework,  R.string.nav_homework),
        NavItem(R.id.messagesFragment,   R.drawable.ic_message,   R.string.nav_messages),
        NavItem(R.id.absencesFragment,   R.drawable.ic_absence,   R.string.nav_absences),
        NavItem(R.id.eventsFragment,     R.drawable.ic_event,     R.string.nav_events),
        NavItem(R.id.classbookFragment,  R.drawable.ic_book,      R.string.nav_classbook),
        NavItem(R.id.settingsFragment,   R.drawable.ic_settings,  R.string.nav_settings),
    )

    private val tabViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            binding.bottomNavContainer.updatePadding(bottom = bars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        buildTabs()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isLoginScreen = destination.id == R.id.loginFragment
            binding.bottomNavContainer.visibility = if (isLoginScreen) View.GONE else View.VISIBLE
            if (!isLoginScreen) updateSelectedTab(destination.id)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.isLoggedIn.collect { loggedIn ->
                    val current = navController.currentDestination?.id
                    if (loggedIn && current == R.id.loginFragment) {
                        navController.navigate(R.id.action_login_to_timetable)
                    } else if (!loggedIn && current != R.id.loginFragment) {
                        navController.navigate(R.id.loginFragment)
                    }
                }
            }
        }

        // Automatic update check on app launch
        checkUpdatesSilently()
    }

    private fun checkUpdatesSilently() {
        lifecycleScope.launch {
            updateManager.checkForUpdates().onSuccess { info ->
                if (info.hasUpdate && info.downloadUrl != null) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Update verfügbar")
                        .setMessage("Eine neue Version (v${info.latestVersion}) ist verfügbar. Möchtest du sie jetzt installieren?\n\n${info.releaseNotes ?: ""}")
                        .setPositiveButton("Laden & Installieren") { _, _ ->
                            updateManager.downloadAndInstall(info.downloadUrl, "webuntis-dashboard-${info.latestVersion}.apk")
                        }
                        .setNegativeButton("Später", null)
                        .show()
                }
            }
        }
    }

    private fun buildTabs() {
        val container = binding.navTabs
        container.removeAllViews()
        tabViews.clear()

        val displayWidth = resources.displayMetrics.widthPixels
        val minTabWidth = (72 * resources.displayMetrics.density).toInt()
        val tabWidth = maxOf(displayWidth / navItems.size, minTabWidth)

        navItems.forEach { item ->
            val tab = LayoutInflater.from(this)
                .inflate(R.layout.item_nav_tab, container, false)
            tab.layoutParams = LinearLayout.LayoutParams(tabWidth, LinearLayout.LayoutParams.MATCH_PARENT)

            tab.findViewById<ImageView>(R.id.tab_icon).setImageResource(item.iconRes)
            tab.findViewById<TextView>(R.id.tab_label).text = getString(item.labelRes)

            tab.setOnClickListener {
                if (navController.currentDestination?.id != item.destinationId) {
                    navController.navigate(item.destinationId)
                }
            }
            container.addView(tab)
            tabViews.add(tab)
        }
    }

    private fun updateSelectedTab(destinationId: Int) {
        val activeColor   = getColorFromAttr(androidx.appcompat.R.attr.colorPrimary)
        val inactiveColor = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)

        navItems.forEachIndexed { i, item ->
            val tab     = tabViews[i]
            val icon    = tab.findViewById<ImageView>(R.id.tab_icon)
            val label   = tab.findViewById<TextView>(R.id.tab_label)
            val selected = item.destinationId == destinationId
            val color   = if (selected) activeColor else inactiveColor
            icon.setColorFilter(color)
            label.setTextColor(color)

            if (selected) {
                binding.bottomNav.post {
                    val scrollX = tab.left - (binding.bottomNav.width - tab.width) / 2
                    binding.bottomNav.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
                }
            }
        }
    }

    private fun getColorFromAttr(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
}
