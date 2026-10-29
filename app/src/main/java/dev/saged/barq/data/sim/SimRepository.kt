package dev.saged.barq.data.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SimInfo(val subId: Int, val slot: Int, val label: String, val color: Int)

@Singleton
class SimRepository @Inject constructor(@ApplicationContext private val context: Context) {

    @SuppressLint("MissingPermission")
    fun activeSims(): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().map {
                SimInfo(
                    subId = it.subscriptionId,
                    slot = it.simSlotIndex,
                    label = it.displayName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "SIM ${it.simSlotIndex + 1}",
                    color = it.iconTint,
                )
            }.sortedBy { it.slot }
        }.getOrDefault(emptyList())
    }

    fun defaultSmsSubId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()
}
