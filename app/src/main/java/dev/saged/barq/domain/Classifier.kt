package dev.saged.barq.domain

import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity

/**
 * Local, offline message classification. Nothing leaves the phone.
 *
 * Priority: user rule → a saved contact is always personal → OTP → spam → bank / offers → personal.
 * Banks and shops send from names ("MyBank") or short codes, never from a person's mobile number,
 * so an unknown mobile number is only "offers" when it looks like a marketing blast (a link or an
 * unsubscribe line), and never "bank". A code from a friend keeps its copy button on the bubble;
 * only the conversation's category stays personal.
 */
object Classifier {

    private val bank = Regex(
        "(?i)(رصيد|حساب|حسابك|بنك|البنك|مصرف|المصرف|خصم مبلغ|تم خصم|تم إيداع|ايداع|إيداع|سحب|حوالة|تحويل|بطاقة|بطاقتك|" +
            "صراف|قسط|فاتورة|debited|credited|account|a/c|balance|bank|card ending|transaction|iban|atm|transfer|withdrawal|deposit|wallet|محفظة)"
    )

    private val offers = Regex(
        "(?i)(عرض|عروض|تخفيض|تخفيضات|خصم \\d+ ?%|\\d+ ?% ?خصم|اشترك|مجان|مجاناً|هدية|كوبون|كود الخصم|تسوق|احصل على|لفترة محدودة|" +
            "promo|offer|sale|discount|% off|coupon|subscribe|unsubscribe|free|deal|shop now|limited time|stop to)"
    )

    private val spamPhrases = Regex(
        "(?i)(ربحت|فزت|لقد فزت|مبروك.{0,20}(جائزة|ربح|فوز)|جائزة|اضغط على الرابط|انقر هنا|سحب على|حسابك موقوف|تم تعليق|" +
            "congratulations|you have won|you won|winner|claim (your|now)|prize|click (here|the link|below)|account (suspended|locked)|" +
            "verify your account|bitcoin|crypto|investment|loan approved|قرض فوري|ربح يومي|استثمار مضمون)"
    )

    /** Lines only marketing messages carry. */
    private val optOut = Regex("(?i)(لإلغاء الاشتراك|لالغاء الاشتراك|إلغاء الاشتراك|ارسل stop|أرسل stop|send stop|reply stop|text stop|unsubscribe|opt out)")

    private val url = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|xyz|top|info|click|link|ly|me|io)\\b)")

    private val shortener = Regex("(?i)(bit\\.ly|tinyurl|t\\.co/|goo\\.gl|cutt\\.ly|rb\\.gy|is\\.gd|shorturl|t\\.ly|ow\\.ly|s\\.id)")

    fun matchRule(address: String, rules: List<CategoryRuleEntity>): CategoryRuleEntity? =
        rules.firstOrNull { rule ->
            PhoneUtils.same(rule.sender, address) ||
                (!PhoneUtils.isPhoneNumber(rule.sender) && address.contains(rule.sender, ignoreCase = true))
        }

    fun classify(
        address: String,
        body: String,
        otp: String?,
        isContact: Boolean,
        spamFilter: Boolean,
        rules: List<CategoryRuleEntity>,
    ): Category {
        matchRule(address, rules)?.let { return it.category }
        if (isContact) return Category.PERSONAL
        if (otp != null) return Category.OTP
        if (spamFilter && spamScore(address, body) >= 3) return Category.SPAM
        val person = PhoneUtils.isReplyable(address)
        if (!person) {
            if (bank.containsMatchIn(body)) return Category.BANK
            if (offers.containsMatchIn(body)) return Category.OFFERS
        } else if (offers.containsMatchIn(body) && (url.containsMatchIn(body) || optOut.containsMatchIn(body))) {
            return Category.OFFERS
        }
        return Category.PERSONAL
    }

    fun spamScore(address: String, body: String): Int {
        var score = 0
        if (url.containsMatchIn(body)) score += 1
        if (shortener.containsMatchIn(body)) score += 2
        if (spamPhrases.containsMatchIn(body)) score += 2
        // A personal-looking number sending links/promos is a common scam pattern.
        if (PhoneUtils.isReplyable(address) && url.containsMatchIn(body)) score += 1
        return score
    }
}
