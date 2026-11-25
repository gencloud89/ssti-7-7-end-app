package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoginDesign(context: Context) : Design<LoginDesign.Request>(context) {
    enum class Request { Login }

    data class Credentials(val email: String, val password: String)

    private val rootView: View = LayoutInflater.from(context)
        .inflate(R.layout.design_login, null, false)

    private val emailInput: TextInputEditText = rootView.findViewById(R.id.email_input)
    private val passwordInput: TextInputEditText = rootView.findViewById(R.id.password_input)
    private val loginButton: MaterialButton = rootView.findViewById(R.id.login_button)
    private val progressBar: LinearProgressIndicator = rootView.findViewById(R.id.progress_bar)
    private val errorText: TextView = rootView.findViewById(R.id.error_text)

    override val root: View get() = rootView

    private var pendingCredentials: Credentials? = null

    init {
        loginButton.setOnClickListener {
            val email = emailInput.text?.toString()?.trim() ?: ""
            val password = passwordInput.text?.toString() ?: ""
            if (email.isNotEmpty() && password.isNotEmpty()) {
                pendingCredentials = Credentials(email, password)
                requests.trySend(Request.Login)
            }
        }

        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                loginButton.performClick()
                true
            } else false
        }
    }

    fun getCredentials(): Credentials? = pendingCredentials

    suspend fun setLoading(loading: Boolean) = withContext(Dispatchers.Main) {
        loginButton.isEnabled = !loading
        progressBar.isVisible = loading
        emailInput.isEnabled = !loading
        passwordInput.isEnabled = !loading
    }

    suspend fun showError(message: String) = withContext(Dispatchers.Main) {
        errorText.text = message
        errorText.isVisible = true
    }

    suspend fun clearError() = withContext(Dispatchers.Main) {
        errorText.isVisible = false
    }

    fun hideKeyboard() {
        context.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
}
