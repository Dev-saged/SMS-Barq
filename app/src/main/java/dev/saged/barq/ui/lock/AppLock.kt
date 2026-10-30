package dev.saged.barq.ui.lock

import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.saged.barq.R
import dev.saged.barq.data.settings.LockMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Barq's app lock. It uses the phone's own fingerprint, face and screen lock (the ones saved in
 * the phone's settings) through Android's system prompt — Barq never sees or stores them.
 *
 * Locked when Barq starts, and again when it comes back after being away longer than the chosen
 * time. Opening a picker or a link from Barq (choosing a file, sharing…) gives a short grace so
 * the owner is not asked again on the way back.
 */
object AppLock {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    private var awaySince = 0L
    private var leftForPicker = false
    private var prompting = false

    /** Barq opened another screen itself (file picker, share sheet, link…). */
    fun leavingForPicker() {
        leftForPicker = true
    }

    fun onBackground() {
        if (!_locked.value) awaySince = SystemClock.elapsedRealtime()
    }

    /** Called when Barq is visible again: locks if it was away too long. */
    fun onForeground(enabled: Boolean, afterSec: Int) {
        if (!enabled) {
            _locked.value = false
            leftForPicker = false
            return
        }
        if (_locked.value) return
        val away = SystemClock.elapsedRealtime() - awaySince
        val allowed = maxOf(afterSec * 1000L, if (leftForPicker) PICKER_GRACE_MS else 0L, ROTATE_GRACE_MS)
        leftForPicker = false
        if (awaySince > 0 && away > allowed) _locked.value = true
    }

    /** The lock was just turned on in settings: it counts as unlocked right now. */
    fun unlockNow() {
        _locked.value = false
        awaySince = SystemClock.elapsedRealtime()
    }

    fun authenticators(method: LockMethod): Int = when (method) {
        LockMethod.BIOMETRIC -> BIOMETRIC_WEAK
        // The PIN alone is only possible from Android 11; before that the prompt offers both.
        LockMethod.DEVICE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        LockMethod.ANY -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

    /** True when the phone can unlock this way (a fingerprint / face saved, or a screen lock set). */
    fun available(activity: FragmentActivity, method: LockMethod): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators(method)) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the system prompt. [onDone] gets true when the owner proved it is them. */
    fun prompt(activity: FragmentActivity, method: LockMethod, onDone: (Boolean) -> Unit) {
        if (prompting) return
        prompting = true
        val auth = authenticators(method)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_prompt_sub))
            .setAllowedAuthenticators(auth)
            .apply { if (auth and DEVICE_CREDENTIAL == 0) setNegativeButtonText(activity.getString(R.string.cancel)) }
            .setConfirmationRequired(false)
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onDone(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    onDone(false)
                }
            },
        )
        runCatching { prompt.authenticate(info) }.onFailure {
            prompting = false
            onDone(false)
        }
    }

    /** Proved it is the owner: Barq opens. */
    fun open() {
        _locked.value = false
        awaySince = SystemClock.elapsedRealtime()
    }

    private const val PICKER_GRACE_MS = 2 * 60 * 1000L
    private const val ROTATE_GRACE_MS = 1_500L
}
