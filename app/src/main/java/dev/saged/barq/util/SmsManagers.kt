package dev.saged.barq.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** The SmsManager for a SIM (dual-SIM aware), or the default one when [subId] is unknown. */
@Suppress("DEPRECATION")
fun smsManagerFor(context: Context, subId: Int): SmsManager {
    val valid = subId >= 0 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = context.getSystemService(SmsManager::class.java)
        if (valid) base.createForSubscriptionId(subId) else base
    } else {
        if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }
}
