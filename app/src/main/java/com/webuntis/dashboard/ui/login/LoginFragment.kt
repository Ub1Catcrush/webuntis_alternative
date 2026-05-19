package com.webuntis.dashboard.ui.login

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentLoginBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prevent auto-focus triggering IME on startup (crashes API 34 emulator)
        // Pre-fill from stored data so user doesn't have to re-enter everything
        val session = viewModel.sessionManager.session
        val creds   = viewModel.sessionManager.storedCredentials
        if (binding.inputServer.text.isNullOrBlank())
            binding.inputServer.setText(session?.server ?: "")
        if (binding.inputSchoolname.text.isNullOrBlank())
            binding.inputSchoolname.setText(session?.schoolname ?: "")
        if (binding.inputUsername.text.isNullOrBlank())
            binding.inputUsername.setText(creds?.first ?: session?.username ?: "")
        if (creds != null && binding.inputPassword.text.isNullOrBlank())
            binding.inputPassword.hint = getString(R.string.login_password_saved_hint)

        binding.root.requestFocus()

        binding.btnLogin.setOnClickListener {
            hideKeyboard()
            attemptLogin()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { state ->
                    when (state) {
                        is LoginState.Loading -> {
                            binding.progressBar.isVisible = true
                            binding.btnLogin.isEnabled = false
                            binding.errorText.isVisible = false
                        }
                        is LoginState.Success -> {
                            binding.progressBar.isVisible = false
                            findNavController().navigate(R.id.action_login_to_timetable)
                        }
                        is LoginState.Error -> {
                            binding.progressBar.isVisible = false
                            binding.btnLogin.isEnabled = true
                            binding.errorText.text = state.message
                            binding.errorText.isVisible = true
                        }
                        is LoginState.Idle -> {
                            binding.progressBar.isVisible = false
                            binding.btnLogin.isEnabled = true
                            binding.errorText.isVisible = false
                        }
                    }
                }
            }
        }
    }

    private fun attemptLogin() {
        val server = binding.inputServer.text.toString()
        val schoolname = binding.inputSchoolname.text.toString()
        val username = binding.inputUsername.text.toString()
        val password = binding.inputPassword.text.toString()

        if (server.isBlank() || schoolname.isBlank() || username.isBlank() || password.isBlank()) {
            binding.errorText.text = "Bitte alle Felder ausfüllen"
            binding.errorText.isVisible = true
            return
        }

        viewModel.login(server, schoolname, username, password)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val focusedView = requireActivity().currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
        focusedView.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
