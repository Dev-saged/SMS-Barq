package dev.saged.barq.data

import android.content.Context
import android.provider.BlockedNumberContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.db.BlockDao
import dev.saged.barq.data.db.BlockedKeywordEntity
import dev.saged.barq.data.db.BlockedNumberEntity
import dev.saged.barq.domain.PhoneUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockList @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: BlockDao,
) {
    val numbers = dao.numbers()
    val keywords = dao.keywords()

    suspend fun isBlockedNumber(address: String): Boolean {
        if (dao.numbersOnce().any { PhoneUtils.same(it.number, address) }) return true
        // Also respect the phone's system-wide block list (shared with the dialer).
        return runCatching { BlockedNumberContract.isBlocked(context, address) }.getOrDefault(false)
    }

    suspend fun hasBlockedKeyword(body: String): Boolean {
        val text = PhoneUtils.normalizeDigits(body)
        return dao.keywordsOnce().any { it.keyword.isNotBlank() && text.contains(it.keyword, ignoreCase = true) }
    }

    suspend fun blockNumber(number: String) {
        val n = number.trim()
        if (n.isNotEmpty()) dao.addNumber(BlockedNumberEntity(n))
    }

    suspend fun unblockNumber(entity: BlockedNumberEntity) = dao.removeNumber(entity)

    suspend fun blockKeyword(keyword: String) {
        val k = keyword.trim()
        if (k.isNotEmpty()) dao.addKeyword(BlockedKeywordEntity(k))
    }

    suspend fun unblockKeyword(entity: BlockedKeywordEntity) = dao.removeKeyword(entity)
}
