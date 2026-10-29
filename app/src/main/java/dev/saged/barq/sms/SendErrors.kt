package dev.saged.barq.sms

import androidx.annotation.StringRes
import dev.saged.barq.R
import dev.saged.barq.data.db.SendError

/**
 * Plain Arabic reasons for a failed message, from the radio's result code.
 * Numbers follow android.telephony.SmsManager.RESULT_* (stable across Android versions).
 */
object SendErrors {
    @StringRes
    fun reason(code: Int): Int = when (code) {
        SendError.CANCELLED -> R.string.err_cancelled
        SendError.REJECTED -> R.string.err_rejected
        2 -> R.string.err_radio_off            // RESULT_ERROR_RADIO_OFF
        4, 9 -> R.string.err_no_service        // NO_SERVICE, RADIO_NOT_AVAILABLE
        3, 14, 18 -> R.string.err_format       // NULL_PDU, INVALID_SMS_FORMAT, ENCODING_ERROR
        5 -> R.string.err_limit                // LIMIT_EXCEEDED
        6 -> R.string.err_fdn                  // FDN_CHECK_FAILURE
        7, 8 -> R.string.err_short_code        // SHORT_CODE_NOT_ALLOWED / NEVER_ALLOWED
        10, 17 -> R.string.err_network         // NETWORK_REJECT, NETWORK_ERROR
        19 -> R.string.err_smsc                // INVALID_SMSC_ADDRESS
        1 -> R.string.err_generic              // GENERIC_FAILURE (often no credit / rejected)
        else -> R.string.err_unknown
    }
}
