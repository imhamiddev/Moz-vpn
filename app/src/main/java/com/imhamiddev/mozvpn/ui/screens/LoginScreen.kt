package com.imhamiddev.mozvpn.ui.screens

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.imhamiddev.mozvpn.data.FxaAuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    // Secrets are deliberately not rememberSaveable: that would write them into the saved-state Bundle.
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var awaitingTwoFactor by rememberSaveable { mutableStateOf(authRepository.pendingSessionToken != null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = awaitingTwoFactor) {
        authRepository.clearPendingSession()
        awaitingTwoFactor = false
        errorMessage = null
        code = ""
    }

    fun doSubmitLogin() {
        val cleanEmail = FxaAuthRepository.normalizeEmail(email)
        if (cleanEmail.isBlank() || password.isBlank() || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.startLogin(cleanEmail, password)
            isLoading = false
            result.onSuccess { needsVerification ->
                if (needsVerification) awaitingTwoFactor = true else onSignedIn()
            }.onFailure { errorMessage = it.message ?: "Sign-in failed" }
        }
    }

    fun doSubmitTwoFactor() {
        val cleanCode = FxaAuthRepository.sanitizeCode(code)
        if (cleanCode.isBlank() || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.submitTwoFactorCode(cleanCode)
            isLoading = false
            result.onSuccess { onSignedIn() }
                .onFailure {
                    errorMessage = it.message ?: "Verification failed"
                    if (authRepository.pendingSessionToken == null) {
                        awaitingTwoFactor = false
                    }
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.padding(top = 20.dp))
            Text(
                "Welcome to Moz VPN",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                "Sign in with your Firefox account. Moz VPN uses the free 50 GB of monthly VPN traffic Mozilla includes with it. No subscription is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 24.dp))

            if (!awaitingTwoFactor) {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it.replace(Regex("[\\p{Z}\\u200B-\\u200D\\uFEFF\\u00A0\\s]+"), "").lowercase()
                    },
                    label = { Text("Firefox account email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            doSubmitLogin()
                        }
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 20.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        doSubmitLogin()
                    },
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Continue")
                    }
                }
            } else {
                Text("Enter the confirmation code sent to your email", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.replace(Regex("[\\p{Z}\\u200B-\\u200D\\uFEFF\\u00A0\\s]+"), "")
                    },
                    label = { Text("Confirmation code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            doSubmitTwoFactor()
                        }
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 20.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        doSubmitTwoFactor()
                    },
                    enabled = !isLoading && code.isNotBlank(),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Verify")
                    }
                }
            }

            errorMessage?.let {
                Spacer(Modifier.padding(top = 12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}
