# برق (Barq) — ملف تعريف المشروع الكامل

> هذا الملف مرجع شامل للمشروع. إذا فتحت محادثة جديدة، أرفق هذا الملف أو الصقه وقل: «هذا مشروعي برق، كمّل عليه».
> آخر تحديث: 29 أكتوبر 2026 (23:15) · الإصدار المثبّت 1.1.0 · **المرحلة الثالثة 1.2.0 مخططة وبانتظار التنفيذ (القسم 11)**
> المستودع: **github.com/Dev-saged/SMS-Barq** (الفرع `main`)

---

## 1. نظرة عامة

| البند | القيمة |
|---|---|
| الاسم | **برق** (Barq) |
| النوع | تطبيق رسائل SMS لأندرويد (تطبيق الرسائل الافتراضي) |
| المطوّر والمالك | **ساجد العبادلة (Sajed Al-Abadla)** |
| الترخيص | خاص، جميع الحقوق محفوظة (`LICENSE`) |
| معرّف التطبيق | `dev.saged.barq` (نسخة debug: `dev.saged.barq.debug`) |
| اللغة والواجهة | Kotlin + Jetpack Compose، عربي بالكامل ومن اليمين لليسار دائماً |
| الحد الأدنى / الهدف | Android 8.0 (API 26) / Android 15 (API 35) |
| فلسفة التصميم | قوي، بسيط، سلس، سريع، ويعمل محلياً بدون إنترنت |
| البناء | تلقائي على GitHub Actions، ولا يحتاج حاسوباً |

### الأصل والقرارات الأساسية
- بدأت الفكرة من دراسة تطبيق **Fossify Messages** (`FossifyOrg/Messages`، بترخيص GPL-3.0) ونسخة RCS منه (`IvanChanPing/fossify-messages-rcs`).
- **القرار: الكتابة من الصفر.** لا يحتوي المشروع أي كود من Fossify، حتى تبقى الحقوق كاملة للمالك.
- **RCS مستبعد**، لأنه يعتمد على API غير رسمي من Google وهش.
- **الرسائل الصادرة تبقى SMS نصية دائماً** مهما طال نصها، وتُقسَّم إلى أجزاء ولا تتحول إلى MMS أبداً.
- **استقبال MMS خيار** يشغّله ويطفيه المستخدم، ومتوقف افتراضياً.
- **رمز OTP لا يُحذف تلقائياً**، يُكتشف ويُنسخ فقط.
- **الرد التلقائي اختياري**، يفعّله المستخدم وقت الحاجة: رد ثابت أو ذكي.
- **حُذفت عمداً**: كلمة مرور التطبيق والحماية، خصوصية شاشة القفل، الأحرف المبسّطة، الإرسال بزر Enter، تحويل الرسائل إلى MMS، شاشات الشراء والتبرع.

---

## 2. المزايا

### مزايا أُبقيت من Fossify (أُعيدت كتابتها)
1. سلة المحذوفات (30 يوماً، ثم حذف تلقائي) والأرشيف
2. تثبيت المحادثات
3. حظر الأرقام وحظر الكلمات، مع احترام قائمة الحظر في نظام الهاتف (`BlockedNumberContract`)
4. تقارير التسليم (اختيارية)
5. عداد الأحرف والأجزاء (`SmsMessage.calculateLength`)
6. حجم الخط (0.9 / 1.0 / 1.15 / 1.3) وصيغة التاريخ ونظام 24 ساعة
7. تصدير واستيراد بصيغة JSON
8. دعم الشريحتين: شريحة لكل محادثة، وشريحة افتراضية
9. المسودات (تُحفظ تلقائياً بعد 500ms من التوقف عن الكتابة)

### مزايا جديدة خاصة ببرق
- **اكتشاف OTP**: عربي وإنجليزي، أرقام هندية (٠-٩)، رموز من 4 إلى 8 أرقام أو بصيغة «3+3». يتجاهل المبالغ والتواريخ والأوقات.
  - زر **نسخ** داخل الإشعار
  - بطاقة الرمز أعلى صندوق الوارد (تظهر 15 دقيقة)
  - شريط نسخ داخل فقاعة الرسالة
  - **ويدجت** آخر رمز (Glance)، الضغط عليه ينسخ الرمز
- **التصنيف**: `PERSONAL`, `OTP`, `BANK`, `OFFERS`, `SPAM`، وترتيب الأولوية: قاعدة المستخدم ← OTP ← سبام ← بنك ← عروض ← شخصي.
- **فلتر سبام محلي** بنظام نقاط: روابط، روابط مختصرة، عبارات احتيال. يُعتبر سبام عند 3 نقاط فأكثر، ولا يطبَّق على جهات الاتصال. السبام يُخزَّن بصمت ولا يصدر إشعاراً.
- **قواعد تصنيف** يضعها المستخدم لكل مرسل، وتتغلب على التصنيف التلقائي.
- **قوالب وردود سريعة** تظهر كأزرار فوق مربع الكتابة. القوالب الافتراضية: تمام، أنا بالطريق، بتواصل معك لاحقاً، شكراً لك، مشغول حالياً.
- **الرد التلقائي**:
  - `FIXED`: نص واحد للجميع
  - `SMART`: قواعد (كلمات مفصولة بـ `|`) ← رد مناسب، وإن لم تنطبق قاعدة يُستخدم النص الثابت
  - الشروط: جهات الاتصال فقط، نافذة زمنية (افتراضياً 22:00–07:00)، ومهلة بين الردود لكل مرسل (15 دقيقة إلى 24 ساعة، الافتراضي 60 دقيقة)
  - الحماية: لا يرد على المرسلين الأبجديين ولا على الأرقام القصيرة (أقل من 7 أرقام)، ولا على المحظورين أو السبام
- **كتم المحادثة** (بدون إشعار)
- **جدولة الإرسال**: بعد ساعة، الليلة 9 م، غداً 9 ص، أو تاريخ ووقت مخصص
- **إرسال جماعي** حتى مئات المستلمين، وكل مستلم يصله SMS منفصل في محادثة منفصلة. السرعة: 100 / 250 / 800ms بين كل رسالة
- **إيماءات السحب** قابلة للضبط: NONE, ARCHIVE, DELETE, READ, PIN, MUTE
- **نسخ احتياطي تلقائي** يومي أو أسبوعي، إلى مجلد يختاره المستخدم (SAF) أو مجلد التطبيق (آخر 5 نسخ)
- **Material You** وثيم فاتح وداكن وحسب النظام
- **البحث** في الاسم والرقم ونص الرسائل

---

## 3. التصميم (الواجهة)

اختار المالك **التصميم A «Material You»** من بين ثلاثة اتجاهات (A: Material You، B: داكن OLED، C: بطاقات تصنيف). لوحة التصاميم الثلاثة محفوظة كـ artifact باسم «SMS App UI Directions».

- **الخط:** Readex Pro (Regular / Medium / SemiBold / Bold) في `res/font/`
- **الألوان الثابتة (عند إيقاف Material You):**

| الدور | فاتح | داكن |
|---|---|---|
| primary | `#4355B9` | `#BAC3FF` |
| primaryContainer | `#DEE0FF` | `#293CA0` |
| secondaryContainer | `#E1E0F9` | `#434559` |
| background/surface | `#FBF8FF` | `#121318` |
| surfaceContainer (البحث) | `#EFEDF6` | `#1F1F25` |
| surfaceContainerHigh (فقاعة الوارد) | `#E9E7F0` | `#292A2F` |

- **ألوان التصنيفات:** OTP = primaryContainer، بنوك = أخضر `#CDEEDD`، عروض = عنبري `#FFDEA6`، سبام = errorContainer، شخصي = secondaryContainer
- **الفقاعات:** الواردة على جهة البداية (اليمين في RTL) بلون surfaceContainerHigh، والصادرة على جهة النهاية بلون primary. الزوايا 20dp، والذيل 6dp.
- **الأيقونة:** فقاعة رسالة بيضاء فيها برق، على تدرّج نيلي (`#4F63E6` ← `#2F3BA8` ← `#121747`) مع توهّج. المصدر: `docs/icon.svg`. داخل التطبيق: adaptive + monochrome + أيقونة إشعار.

### الشاشات
| الشاشة | الملف | المحتوى |
|---|---|---|
| الترحيب | `ui/onboarding/OnboardingScreen.kt` | طلب دور تطبيق الرسائل الافتراضي، ثم الأذونات |
| صندوق الوارد | `ui/inbox/InboxScreen.kt` | بحث، شرائح تصنيف، بطاقة OTP، قائمة بالسحب، وضع تحديد، شريط السبام |
| الأرشيف والسلة | `ui/inbox/FolderScreen.kt` | استعادة، حذف نهائي، إفراغ |
| المحادثة | `ui/thread/ThreadScreen.kt` | فقاعات، فواصل أيام، قائمة الرسالة، مربع كتابة بالقوالب والشريحة والجدولة |
| رسالة جديدة | `ui/compose/ComposeScreen.kt` | مستلمون متعددون، اقتراحات جهات الاتصال، لصق قائمة أرقام |
| الإعدادات | `ui/settings/SettingsScreen.kt` | المظهر، الرسائل، الحماية، الأدوات، السحب، النسخ، حول |
| الأدوات | `ui/settings/ToolsScreens.kt` | المحظورات، قواعد التصنيف، القوالب، الرد التلقائي |

---

## 4. البنية التقنية

### المكتبات والإصدارات (`gradle/libs.versions.toml`)
| المكتبة | الإصدار |
|---|---|
| AGP | 8.7.3 |
| Kotlin / Compose compiler plugin | 2.1.0 |
| KSP | 2.1.0-1.0.29 |
| Gradle wrapper | 8.11.1 |
| Compose BOM | 2024.12.01 (Material3 1.3.x) |
| Hilt | 2.53.1 · androidx.hilt 1.2.0 |
| Room | 2.6.1 (مع room-paging) |
| Paging | 3.3.5 |
| WorkManager | 2.10.0 |
| DataStore Preferences | 1.1.1 |
| Glance | 1.1.1 |
| Navigation Compose | 2.8.5 |
| Lifecycle | 2.8.7 · Activity 1.9.3 · Core 1.15.0 |
| Coroutines | 1.9.0 · kotlinx.serialization 1.7.3 |
| JDK | 17 |

### النمط المعماري
- **MVVM + Repository**، والحقن بـ **Hilt**
- **Room هو مصدر الحقيقة للواجهة** (سريع ومجزأ إلى صفحات)، و**مزوّد SMS في النظام** (`content://sms`) يُحدَّث معه حتى تبقى الرسائل متاحة لباقي التطبيقات
- `providerLock` (Mutex) يمنع التكرار أثناء المزامنة مع المزوّد
- المستقبلات (receivers) تستخدم **EntryPoint** (`di/BarqEntryPoint.kt` ← `context.barq`) بدل حقن الحقول، لأنه أكثر موثوقية
- `util/goAsync {}` يشغّل coroutines من المستقبلات بمهلة 9 ثوانٍ

### هيكل الملفات
```
Barq/
├── .github/workflows/build.yml       # البناء التلقائي (وفك zip المرفوع من الهاتف)
├── gradle/libs.versions.toml          # الإصدارات
├── gradle.properties                  # APP_ID, VERSION_NAME, VERSION_CODE
├── app/build.gradle.kts               # التوقيع من الأسرار، R8، Compose
├── app/proguard-rules.pro
├── docs/  (PUBLISHING.md, PROJECT_CONTEXT.md, PHASE3_PLAN.md, cover.jpg, icon.svg/png, FONT_LICENSE_OFL.txt)
├── README.md · LICENSE · NOTICE.md
└── app/src/
    ├── main/AndroidManifest.xml
    ├── main/res/  (strings عربية، themes، drawables، font، xml)
    ├── test/java/dev/saged/barq/  (DomainTest.kt, MmsPduTest.kt — 26 اختباراً)
    └── main/java/dev/saged/barq/
        ├── BarqApp.kt                 # Hilt، قنوات الإشعارات، WorkManager، القيم الافتراضية
        ├── MainActivity.kt            # Compose host + نوايا sms:/smsto:/share/إشعار
        ├── di/  AppModule.kt (Room/DAOs/AppScope) · BarqEntryPoint.kt
        ├── data/
        │   ├── db/ Entities.kt · Daos.kt · BarqDatabase.kt
        │   ├── MessageRepository.kt   # القلب: وارد/صادر/حالات/حذف/أرشفة/قواعد
        │   ├── SyncManager.kt         # استيراد سجل الهاتف (دفعات 500) + مزامنة تزايدية
        │   ├── BlockList.kt
        │   ├── telephony/TelephonyProvider.kt   # قراءة وكتابة content://sms
        │   ├── contacts/ContactsRepository.kt    # أسماء مع ذاكرة مؤقتة + بحث
        │   ├── sim/SimRepository.kt
        │   ├── settings/SettingsRepository.kt    # DataStore → AppSettings
        │   └── backup/BackupManager.kt           # تصدير/استيراد/نسخ تلقائي
        ├── domain/ OtpDetector.kt · Classifier.kt · PhoneUtils.kt · AutoReplyEngine.kt
        ├── sms/
        │   ├── SmsDeliverReceiver.kt  # SMS_DELIVER
        │   ├── IncomingProcessor.kt   # تخزين ← إشعار ← ويدجت ← رد تلقائي
        │   ├── SendQueue.kt + SendQueueWorker.kt  # طابور الإرسال
        │   ├── SmsSender.kt           # sendTextMessage / sendMultipartTextMessage
        │   ├── SmsStatusReceiver.kt   # sent / delivered
        │   ├── ScheduleManager.kt     # AlarmManager للجدولة
        │   ├── SystemReceivers.kt     # ScheduledAlarmReceiver, BootReceiver
        │   └── HeadlessSmsSendService.kt  # الرد من شاشة المكالمة
        ├── mms/ MmsPdu.kt (محلّل مكتوب خصيصاً) · MmsReceivers.kt (WAP push، تنزيل، تأكيد)
        ├── notifications/ Notifier.kt · NotificationActionReceiver.kt
        ├── widget/ OtpWidget.kt (Glance + CopyOtpAction + Receiver)
        ├── work/ Maintenance.kt (كل 6 ساعات: نسخ تلقائي، تنظيف السلة والملفات المؤقتة)
        ├── util/ Receivers.kt · SmsManagers.kt
        └── ui/
            ├── BarqNavHost.kt (Routes) · MainViewModel.kt
            ├── theme/Theme.kt
            ├── components/ Common.kt · ConversationRow.kt · DefaultSmsRequest.kt
            ├── onboarding/ · inbox/ · thread/ · compose/ · settings/
```

### قاعدة البيانات (Room، الإصدار 1، الملف `barq.db`، وضع WAL)
| الجدول | الحقول المهمة |
|---|---|
| `conversations` | threadId (PK)، addresses، displayName، snippet، date، unreadCount، category، categoryLocked، pinned، archived، muted، deleted، deletedAt، draft، subId، lastFromMe، lastBox |
| `messages` | id، providerId (unique)، threadId، address، body، date، dateSent، box، read، deliveryStatus، subId، otp، category، isMms، attachments (JSON)، subject، scheduledAt، errorCode، autoReply |
| `blocked_numbers` / `blocked_keywords` | number / keyword |
| `category_rules` | sender (unique) ← category |
| `templates` | text، position |
| `auto_reply_rules` | keywords، reply، enabled |
| `auto_reply_log` | address ← lastAt (مهلة الرد) |

- **قيم `box`** (مطابقة لـ Telephony): `INBOX=1`, `SENT=2`, `OUTBOX=4`, `FAILED=5`, `QUEUED=6`
- **قيم `deliveryStatus`:** `NONE=-1`, `PENDING=32`, `DELIVERED=0`, `FAILED=64`
- **أي تغيير في الجداول** يحتاج رفع `version` وكتابة Migration في `BarqDatabase.kt`.

### دورة حياة الرسالة
- **الوارد:** `SmsDeliverReceiver` ← `IncomingProcessor.onSms` ← `MessageRepository.onIncomingSms`، وفيها: فحص الحظر ← كشف OTP ← التصنيف ← الكتابة في المزوّد وفي Room ← `refreshConversation`. بعدها: إشعار (إلا للسبام والمكتوم) ← تحديث الويدجت ← الرد التلقائي.
- **الصادر:** الواجهة ← `queueOutgoing` (صف `QUEUED`؛ والجماعي داخل معاملة واحدة) ← `SendQueue.kick()` (WorkManager بسياسة `APPEND_OR_REPLACE`) ← `SendQueueWorker` يأخذ دفعات من 50، ولكل رسالة: `markOutbox` ← `SmsSender.send` ← تأخير حسب السرعة المختارة. يعيد تشغيل نفسه بعد 8 دقائق إذا بقي في الطابور شيء. النتيجة تصل إلى `SmsStatusReceiver`، والجزء الأخير يحدد النجاح وأي جزء فاشل يُفشل الرسالة.
- **المجدول:** صف `QUEUED` مع `scheduledAt` ← `ScheduleManager.schedule` (منبّه دقيق إن سُمح به) ← `ScheduledAlarmReceiver` ← `kick()`. `BootReceiver` يعيد ضبط المنبّهات، ويحوّل الرسائل العالقة في `OUTBOX` إلى `FAILED`.
- **MMS (إن كان مفعّلاً):** `MmsWapPushReceiver` يحلل m-notification.ind ← `downloadMultimediaMessage` إلى ملف عبر FileProvider ← `MmsDownloadedReceiver` يحلل m-retrieve.conf ويحفظ المرفقات في `files/attachments` ← `onMms` ← يرسل m-notifyresp.ind للتأكيد. رسائل MMS **لا تُكتب** في `content://mms`. إن كان معطّلاً يظهر إشعار تنبيه فقط.

### الإشعارات
- **القنوات:** `messages` و`otp` (أهمية عالية)، و`status` (فشل الإرسال وتنبيه MMS)
- إشعار واحد لكل محادثة بنمط MessagingStyle، وأزراره: **نسخ الرمز**، **رد** (RemoteInput)، **تحديد كمقروء**

### الإعدادات (`AppSettings` في DataStore)
themeMode, dynamicColor, fontScale, datePattern, use24h, deliveryReports, showCounter, mmsEnabled, spamFilter, recycleBin, swipeStart, swipeEnd, defaultSubId, sendDelayMs, autoReplyEnabled, autoReplyMode, autoReplyText, autoReplyContactsOnly, autoReplyCooldownMin, autoReplyWindow, autoReplyFromMin, autoReplyToMin, backupFrequency, backupTreeUri, lastBackupAt, onboardingDone

### صيغة النسخة الاحتياطية (JSON)
```json
{ "format":"barq-backup", "version":1, "createdAt":0,
  "blockedNumbers":[], "blockedKeywords":[], "rules":[{"sender":"","category":""}],
  "templates":[], "autoReplyRules":[{"keywords":"","reply":"","enabled":true}],
  "conversations":[{"addresses":"","pinned":false,"archived":false,"muted":false,"category":"","categoryLocked":false}],
  "messages":[{"address":"","body":"","date":0,"dateSent":0,"box":1,"read":true,"subId":-1}] }
```
الاستيراد يتجاهل الرسائل المكررة (نفس الرقم والتاريخ والنص).

### الأذونات
READ/SEND/RECEIVE_SMS، RECEIVE_MMS، RECEIVE_WAP_PUSH، READ_CONTACTS، READ_PHONE_STATE، POST_NOTIFICATIONS، SCHEDULE_EXACT_ALARM، RECEIVE_BOOT_COMPLETED، INTERNET (لتنزيل MMS فقط عبر النظام).

**متطلبات دور الرسائل الافتراضي (كلها موجودة في المانيفست):** SMS_DELIVER، WAP_PUSH_DELIVER، نشاط SENDTO لـ sms/smsto/mms/mmsto، وخدمة RESPOND_VIA_MESSAGE.

---

## 5. البناء والنشر

- **الملف:** `.github/workflows/build.yml`. يعمل عند كل push، وعند الوسوم `v*`، ويدوياً.
- **خطواته:**
  1. إن وُجد ملف `.zip` في جذر المستودع، يفكه (ما عدا `.github`) ويرفع الملفات المستخرجة ويحذف الـ zip. هذا مخصص للرفع من الهاتف.
  2. إن لم يوجد `settings.gradle.kts` يتوقف بملاحظة بدل الفشل.
  3. يبني بـ `./gradlew assembleRelease` على JDK 17.
  4. يرفع الـ APK كـ artifact، ويحدّث إصداراً تجريبياً باسم `latest-build` فيه `Barq-latest.apk`.
  5. عند الوسم `vX.Y.Z` ينشر إصداراً رسمياً ومعه الـ APK.
  6. يشغّل الاختبارات، وفشلها لا يوقف الـ APK.
- **التوقيع (GitHub Secrets):** `KEYSTORE_BASE64`، `SIGNING_STORE_PASSWORD`، `SIGNING_KEY_ALIAS` (= barq)، `SIGNING_KEY_PASSWORD`. بدونها يُوقَّع الـ APK بمفتاح مؤقت، فلا تُثبَّت التحديثات فوق بعضها.
- **مفتاح التوقيع** في `Barq-signing-keys.zip` المنفصل (barq-release.jks، RSA 4096، صالح 100 سنة). **لا يُرفع للمستودع أبداً.**
- **رقم الإصدار:** `versionCode = VERSION_CODE + GITHUB_RUN_NUMBER`، فكل بناء جديد يُثبَّت فوق السابق.
- **الشرح الكامل خطوة بخطوة:** `docs/PUBLISHING.md`

---

## 6. حالة المشروع والتحقق

- ✅ الكود كامل، حوالي 7,500 سطر Kotlin في 45 ملفاً.
- ✅ 26 اختبار وحدة تنجح محلياً: OTP، التصنيف، مطابقة الأرقام وتوحيد الصيغ، استبدال الأرقام بالأسماء، محلّل MMS.
- ✅ كل الملفات خالية من أخطاء الصياغة، وخطوة فك الـ zip في البناء مجرّبة.
- ✅ المشروع مرفوع على `Dev-saged/SMS-Barq`، وخطوة فك الـ zip في GitHub Actions نجحت، وأسرار التوقيع الأربعة مضافة وتعمل.
- ✅ **البناء الكامل نجح** (الإصدار 1.0.0) والتطبيق يعمل على جهاز المالك. بيئة التطوير لا تصل إلى مستودعات Google، فالبناء يجري دائماً على GitHub Actions، وكل خطأ يُصلَّح من سجل البناء (log).

### سجل محاولات البناء والإصلاحات
| # | المرحلة | الخطأ | الإصلاح | الحالة |
|---|---|---|---|---|
| 1 | Configure `:app` | `app/build.gradle.kts:11` → `Unresolved reference: util` و`load` (داخل سكربت Gradle، كلمة `java` تُفهم كإضافة Java وليس كحزمة) | إضافة `import java.util.Properties` في أول الملف واستخدام `Properties()` | ✅ أُصلح في هذا الإصدار |
| 1ب | مراجعة استباقية | متغيرات Paging باسم `items` تتعارض مع دالة `items()` داخل LazyColumn، واستيراد `decodeFromString` ناقص | إعادة التسمية إلى `pagingItems` + إضافة الاستيراد | ✅ أُصلح |
| 2 | البناء الكامل (1.0.0) | لا أخطاء | — | ✅ **نجح البناء وثُبّت التطبيق على Galaxy S21+ (SM-G996B/DS) وعمل** |
| 3 | المرحلة الثانية (1.1.0) | لا أخطاء | تحسينات جهات الاتصال والقائمة (القسم 8) | ✅ **نجح البناء #3** (7m 16s)، والـ APK في إصدار `latest-build` |

> **الخطوة التالية:** تنفيذ المرحلة الثالثة 1.2.0 (القسم 11 والملف `docs/PHASE3_PLAN.md`) عند كلمة «ابدأ» من المالك. إن فشل أي بناء، نزّل السجل (Actions ← العملية ← ⋯ ← Download log archive)، وأرسله للذكاء الاصطناعي مع هذا الملف.
>
> **مكان التنزيل:** `github.com/Dev-saged/SMS-Barq/releases` ← إصدار **latest-build** (عليه شارة Pre-release) ← Assets ← `Barq-latest.apk`. أو من صفحة البناء ← Artifacts.
- ⚠️ لم تُختبر بعد على الجهاز: استقبال MMS (يختلف باختلاف المشغّل) والشريحتين.

### قيود معروفة
- رسائل MMS القديمة في الهاتف لا تُستورد (الاستيراد يشمل SMS فقط).
- رسائل MMS المستلمة تُحفظ داخل برق فقط، ولا تُكتب في مزوّد النظام.
- أندرويد يطلب تأكيداً مرة واحدة عند إرسال عدد كبير من الرسائل (حماية من النظام)، والحل «السماح دائماً».
- الرسالة العربية: 70 حرفاً للجزء الواحد (67 في الرسائل متعددة الأجزاء). الإنجليزية: 160 / 153.
- الجدولة الدقيقة على Android 12+ تحتاج إذن «المواعيد الدقيقة»، ويوجد له زر في الإعدادات.

---

## 7. تعليمات لأي مساعد ذكاء اصطناعي يكمل المشروع
- اقرأ هذا الملف كاملاً قبل أي تعديل، ولا تخالف «القرارات الأساسية» في القسم 1 إلا بطلب صريح من المالك.
- المالك يعمل **من الجوال فقط، بدون حاسوب**. البناء والتجربة كلها عبر GitHub Actions. سلّم دائماً ملفات كاملة جاهزة للصق، أو zip كاملاً، مع خطوات واضحة من متصفح الجوال.
- لغة الحوار: **العربية (لهجة شامية بسيطة)**. نصوص الواجهة عربية في `strings.xml`.
- لا تغيّر إصدارات المكتبات إلا لسبب واضح (القسم 4)، ولا تضف مكتبات ثقيلة. الهدف تطبيق سريع وخفيف.
- عند إصلاح خطأ بناء: اقرأ السجل، صلّح **كل** الأخطاء الظاهرة دفعة واحدة، وحدّث جدول «سجل محاولات البناء» أعلاه.
- أي تغيير في جداول Room يحتاج رفع `version` وكتابة Migration.
- حافظ على: الحقوق باسم ساجد العبادلة، الإرسال SMS دائماً، والعمل بدون إنترنت (الاستثناء الوحيد المعتمد: فحص التحديثات عند ضغط المستخدم فقط، القسم 11).
- **ممنوع استخدام الإيموجي نهائياً** في التطبيق والواجهة. كل الأيقونات SVG/Vector فخمة ومرسومة يدوياً.
- طريقة العمل المفضّلة للمالك: يجمع المشاكل واحدة واحدة مع صور، تُسجَّل في قائمة، ثم يقول «ابدأ» فيُنفَّذ كل شي دفعة واحدة ويُسلَّم zip كامل + تحديث هذا الملف وREADME.
- `Barq-signing-keys.zip` لا يُرفع أبداً، والأسرار موجودة مسبقاً في المستودع.

## 8. المرحلة الثانية (1.1.0): تحسينات طلبها المالك بعد التجربة على الجوال

| المشكلة | الحل المنفّذ | الملفات |
|---|---|---|
| أرقام مسجّلة تظهر كأرقام لا كأسماء. السبب: الرسائل تصل بصيغة `+972…` والجهات محفوظة بـ `0…` أو `+970…`، وسامسونج تقارن الأرقام الدولية حرفياً | فهرس جهات اتصال خاص ببرق، يطابق **آخر 9 خانات** (`PhoneUtils.matchKey`)، فيعمل مع كل الصيغ وكل الأجهزة. لا يخزّن نتيجة «غير موجود» قبل منح الإذن، ويتحدّث تلقائياً عند تعديل الجهات (`ContactsWatcher`) | `data/contacts/ContactsRepository.kt`، `ContactsWatcher.kt`، `domain/PhoneUtils.kt` |
| نفس الرقم بصيغ مختلفة (`059…`/`+972…`/`+970…`) يظهر بأكثر من محادثة | **محادثة واحدة لكل رقم حقيقي**: `MessageRepository.threadResolver()` للوارد والصادر والاستيراد، و`mergeDuplicateConversations()` يدمج المحادثات القديمة تلقائياً عند كل مزامنة. كل رقم مختلف لنفس الشخص يبقى بمحادثة مستقلة | `data/MessageRepository.kt`، `data/SyncManager.kt`، `data/backup/BackupManager.kt` |
| الأرقام داخل نص الرسالة | تُعرض باسم الجهة المحفوظ (عرض فقط، والنص الأصلي يبقى محفوظاً) عبر `NumberText.replaceWithNames` | `domain/PhoneUtils.kt`، `ui/components/Common.kt` (`rememberWithNames`)، الفقاعات وسطر آخر رسالة |
| صور جهات الاتصال | تُجلب تلقائياً من الهاتف (`PHOTO_THUMBNAIL_URI`) مع ذاكرة مؤقتة، وتتحدّث إذا تغيّرت (مفتاح الذاكرة يشمل `contacts.version` لأن أندرويد يبقي نفس الرابط عند تغيير الصورة). لا يوجد خيار لوضع صورة من داخل برق (قرار المالك) | `ui/components/Common.kt` (`Avatar`، `rememberContactPhoto`، `LocalContacts`) |
| صفحة الشخص | شاشة جديدة `contact/{threadId}`: صورة كبيرة بإطار متدرّج، الاسم، الرقم تحته (**لمسة على الرقم تنسخه** بدون زر)، **زر اتصال مميز** (حبة خضراء متدرّجة بظل)، زر «الرسائل»، وزر «جهة الاتصال» أو «إضافة لجهات الاتصال». تُفتح بالضغط على الصورة في القائمة أو على الاسم في أعلى المحادثة | `ui/contact/ContactScreen.kt`، `ui/BarqNavHost.kt` |
| الوقت في القائمة غير مرتب، والعناصر متداخلة | تخطيط بأعمدة ثابتة: عمود الوقت والعداد بعرض ثابت (56dp) ومحاذاة واحدة، الاسم والرسالة يُقصّان بـ «…»، و**خط فاصل رفيع** يبدأ بعد الصورة (اختيار المالك: الشكل «أ») | `ui/components/ConversationRow.kt`، `ui/inbox/InboxScreen.kt` |

- الاختبارات: 26 اختباراً تنجح محلياً (أُضيفت اختبارات توحيد الصيغ واستبدال الأرقام بالأسماء).
- زر الاتصال يفتح شاشة الاتصال برقم جاهز (`ACTION_DIAL`) دون الحاجة لإذن إضافي.

## 9. أفكار للمراحل القادمة (غير منفّذة)
> انتقل منها إلى المرحلة 1.2.0: البحث والانتقال لنتيجة البحث، قوائم الإرسال المحفوظة، الإحصائيات، نقل الإعدادات مع النسخة الاحتياطية. والباقي ما زال فكرة.
- البحث داخل محادثة واحدة، والانتقال إلى نتيجة البحث
- إرسال الصور (MMS صادر)، بشرط أن يبقى اختيارياً حتى لا يخالف قرار «SMS دائماً»
- قوائم إرسال جماعي محفوظة (مجموعات أرقام)
- إحصائيات (رسائل اليوم، أجزاء SMS المستهلكة)
- لغة إنجليزية إضافية (`values-en`)
- نقل الإعدادات مع النسخة الاحتياطية
- اختبارات واجهة (Compose UI tests)

---

## 10. أوامر سريعة
```bash
./gradlew assembleDebug          # نسخة تطوير
./gradlew assembleRelease        # نسخة إصدار (تحتاج أسرار التوقيع)
./gradlew testDebugUnitTest      # الاختبارات
```
- **تغيير الاسم:** `res/values/strings.xml` ← `app_name`
- **تغيير المعرّف أو الإصدار:** `gradle.properties` ← `APP_ID`، `VERSION_NAME`، `VERSION_CODE`
- **إضافة نص للواجهة:** كل النصوص في `res/values/strings.xml` (عربي)

## 11. المرحلة الثالثة (1.2.0): مخططة وبانتظار «ابدأ»

> الخطة التفصيلية الكاملة (الأسباب والحلول وملاحظات التنفيذ لكل نقطة): **`docs/PHASE3_PLAN.md`**. لم يُنفَّذ منها شيء في الكود بعد.

### ملخص النقاط المعتمدة
| # | النقطة | النوع |
|---|---|---|
| 1 | الرد من الإشعار وشاشة المكالمة يرسل من الشريحة الصحيحة | إصلاح |
| 2 | لا إشعار ولا صوت والمحادثة مفتوحة أمامك | إصلاح |
| 3 | التطبيق خفيف عند كل فتح (بدون إعادة بناء الجهات ووميض الصور) | أداء |
| 4 | الإرسال من الخلفية بدون تأخير (Expedited) | إصلاح |
| 5 | بطاقة الرمز والويدجت تعرض اسم الجهة | واجهة |
| 6 | إلغاء التثبيت والكتم جماعياً | واجهة |
| 7 | الرسالة المجدولة لا تغيّر ترتيب المحادثة ووقتها | واجهة |
| 8 | صورة الجهة في الإشعار | واجهة |
| 9 | عدم تعليم المقروء مع كل تحميل صفحة | أداء |
| 10 | الشرائح تظهر بعد منح الإذن بدون إعادة فتح | واجهة |
| 16 | زر «رسالة» من جهات الاتصال وسجل المكالمات يفتح المحادثة مباشرة | إصلاح |
| 17 | كل جهات الاتصال (1000+) مع بحث فوري، فواصل أحرف ثابتة، وشريط أحرف تفاعلي (فقاعة، تكبير موجي، اهتزاز) | إصلاح + ميزة |
| 18 | لا يبقى شريط ملوّن عالق بعد السحب ثم «تراجع» | إصلاح |
| 19 | المسودة ترفع المحادثة لأعلى القائمة بوقتها، ومسحها يرجعها | إصلاح |
| 20 | بطاقة المطوّر الجديدة (تصميم وتطوير، أزرار تواصل، صُنع في غزة، الإصدار) | تصميم |
| 21 | إرسال جماعي فوري بالواجهة، بأقصى سرعة للمودم، مع تقدّم وإيقاف وإلغاء | أداء + ميزة |
| 22 | الرسالة الفاشلة: أيقونة خطأ، سبب الفشل بالعربي، وزر إعادة إرسال ظاهر | واجهة |
| 23 | تقسيم `MessageRepository` + محرك إرسال واحد `SendEngine` | هيكلية |
| 24 | بحث فوري FTS مع الانتقال لمكان الرسالة | ميزة |
| 25 | تخفيف إعادة الرسم في Compose | أداء |
| 26 | سجل أعطال داخل التطبيق (نسخ ومشاركة) | أدوات |
| 27 | مجموعات إرسال محفوظة | ميزة |
| 28 | الإعدادات والمجموعات مع النسخة الاحتياطية (صيغة version 2) | ميزة |
| 29 | إشعارات أذكى (اختفاء إشعار الرمز بعد النسخ، تجميع، نغمة لكل محادثة) | ميزة |
| 30 | إحصائيات بسيطة | ميزة |
| 31 | فحص التحديثات عند الضغط فقط (استثناء معتمد من مبدأ بدون إنترنت) | ميزة |

### قرارات المرحلة
- **قاعدة البيانات 1 ← 2 بـ Migration واحد** يجمع: `conversations.draftAt`، `messages.batchId`، جدول `messages_fts`، جدولا المجموعات. ممنوع `fallbackToDestructiveMigration`.
- **لا مكتبات جديدة.** Compose وAndroid فقط.
- **غير مختار:** «بناء أذكى» و«اختبارات جديدة». الاختبارات الحالية يجب أن تبقى ناجحة.
- **حقيقة تقنية:** إرسال 1000 SMS عبر الشبكة في ثانية مستحيل على أي هاتف. الهدف: تسجيل 1000 رسالة بالطابور بأقل من ثانية بدون تعليق، والإرسال الفعلي بأقصى سرعة يسمح بها المودم.
- **`build.yml`** سيحتاج تعديلاً صغيراً للنقطة 31، ويُلصق يدوياً لأن ملف الـ workflow لا يتحدث من الـ zip.

### حسابات المالك (لبطاقة المطوّر وREADME)
| المنصة | الحساب | الرابط |
|---|---|---|
| واتساب | +972597857250 | `https://wa.me/972597857250` |
| إنستغرام | sajedd.0 | `https://instagram.com/sajedd.0` |
| فيسبوك | saged.t | `https://facebook.com/saged.t` |
| إكس | saged_al | `https://x.com/saged_al` |
| غيت هب | Dev-saged | `https://github.com/Dev-saged` |

---
© 2026 ساجد العبادلة — جميع الحقوق محفوظة. صُنع بكل حب من فلسطين — غزة.
