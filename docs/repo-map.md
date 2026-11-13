# repo-map

Keep under 5KB. No code. Update by 2 lines after every change.
المرجع الكامل: docs/PROJECT_CONTEXT.md (680 سطراً، اقرأ بالأقسام عبر Grep على العناوين، لا كاملاً).

## Identity
- برق (Barq): تطبيق SMS افتراضي لأندرويد، Kotlin + Compose، عربي RTL + values-en. APP_ID=dev.saged.barq. الإصدار 1.6.0 (VERSION_CODE=1 + رقم run). minSdk 26، target/compile 36.
- مكتوب من الصفر (لا كود Fossify). بدون إنترنت إلا فحص التحديثات عند الضغط. ممنوع إيموجي (استثناء: تفاعلات الرسائل).
- الإرسال دائماً SMS نصي مقسّم، لا تحويل إلى MMS. استقبال MMS اختياري. OTP يُنسخ ولا يُحذف.

## Files (حزمة dev/saged/barq)
- data/: ConversationRepository، SyncManager، ProviderWatcher، db/ (Room v4: Daos، Entities)، backup/ (BackupManager، SettingsSnapshot)، update/UpdateChecker، telephony/، contacts/، settings/، sim/
- sms/: SendEngine، SendQueue، SendDrainer، SendTracker، SendLimit، ScheduleManager، BulkSendService، gateway/ (بوابة HTTP للمطوّرين)
- mms/: MmsPdu، MmsReceivers. notifications/: Notifier، ActiveThread. widget/OtpWidget. work/Maintenance (DbCompactWorker)
- domain/: Classifier، OtpDetector، AutoReplyEngine، PhoneUtils، TextNorm، SheetImport
- ui/: inbox، thread، compose، contact، starred، batch، lock، onboarding، settings، whatsnew، BarqNavHost، MainViewModel
- بناء: .github/workflows/build.yml (+ unpack-zip.yml)، gradle/libs.versions.toml، app/schemas، app/src/main/baseline-prof.txt
- 1.8.0: ui/theme/Theme.kt (islandColor/islandShape)، ui/components/PillNav + SharedAvatar، ui/icons/BIcons، util/LocaleHelper + ui/settings/LanguageDialog، res/values-xx (es fr pt ru zh hi bn pt ur: 558 مفتاحاً، 290 مترجمة والباقي إنجليزي)، drawable/ic_flag_*، اختبار LocalizationTest. barq_rtl للاتجاه (ar وur)، barq_arabic لنص التفاعلات.
- docs/: PROJECT_CONTEXT.md، PHASE3_PLAN.md، PUBLISHING.md، releases/vX.Y.Z.md

## Versions
- 1.0 أساس · 1.1 جهات · 1.2 بحث وقوائم · 1.3 محرك إرسال · 1.4 بوابة ومجموعات · 1.5.0 قفل وExcel ومفضلة (DB 4) · 1.5.1 Noto · 1.5.2 حد 6 ساعات · 1.6.0 غير المقروءة (أخضر) · 1.6.1 فاشلة/سحب/اتصال · 1.7.0 تحديث داخلي (أخضر) · 1.8.0 One UI + شعار + 10 لغات (أخضر، لم يُجرَّب على الجوال)

## Decisions
- التحديث الداخلي (1.7.0): data/update/{UpdateChecker,UpdateManager,UpdateDownloadService,UpdateInstallReceiver,UpdateNotifications}، ui/update/UpdateUi.kt (UpdateLayer)؛ APK في files/updates ويُحذف في UpdateManager.restore() عند بدء البرنامج؛ CI ينشر version.json مع الإصدار.
- Room مصدر حقيقة الواجهة + مزامنة مع content://sms تحت providerLock (منع التكرار).
- Receivers عبر EntryPoint لا حقن حقول.
- AGP 8.13.2 وHilt 2.58 عمداً (AGP 9 يغيّر نموذج البناء).
- خدمة dataSync يجب أن تعالج onTimeout (أندرويد 15+).
- رفع zip يضيف ويستبدل ولا يحذف: الملف المحذوف يُستبدل بملف فارغ (سطر package فقط).
- نص واجهة جديد يُضاف في values و values-en بنفس الاسم. تغيير جدول Room = رفع version + Migration + اختبار.
- الرفع من الجوال فقط، الأسرار الأربعة موجودة، المفتاح خارج المستودع.

## Details (PROJECT_CONTEXT 4/11/13)
- Room: v4 (Migration1To2، 2To3، 3To4؛ ممنوع fallbackToDestructiveMigration). جداول: conversations، messages (+FTS4 messages_fts على searchText مطبّع عربياً)، send_groups(+members)، blocked_*، category_rules، templates، auto_reply_*.
- box: INBOX=1 SENT=2 OUTBOX=4 FAILED=5 QUEUED=6.
- مسار الإرسال: SendEngine ← OutgoingRepository.enqueue ← SendQueue/BulkSendService ← SendDrainer ← SmsSender ← SmsStatusReceiver.
- البوابة (1.4.0): POST /send، مفتاح X-Api-Key، منفذ 8080، شبكة محلية فقط، لوحة ويب assets/gateway.html، متوافقة مع SMS Sendr (بوت المالك).
- وضع المطوّر: 3 ضغطات على سطر الإصدار. خط Readex Pro.
- المالك: يعمل من الهاتف فقط، يجمع المشاكل ثم يقول «ابدأ»، لهجة شامية.
- الأرقام الفلسطينية ← 05x (PhoneUtils.localPs)؛ matchKey آخر 9 خانات.

## Solved bugs (never reintroduce)
- بناء 1.7.0: serialization 1.13 وcoroutines 1.11 مبنيتان بـKotlin 2.4 فتفشل مع المترجم 2.2.21 (Incompatible classes). ثبّت 1.10.0 و1.10.2 حتى الانتقال إلى AGP 9/Kotlin 2.4.
- «لا يظهر تحديث»: المستودع private فيرجع 404 لأي طلب بلا دخول (api وgithub.com)؛ يلزم مستودع عام. الفحص الآن 3 طبقات (version.json ← تحويل /releases/latest ← API) ويعرض السبب؛ المقارنة versionName ثم versionCode.
- سجل المكالمات يفتح المحادثة المفتوحة: launchSingleTop على thread/{id}؛ احذف المفتوحة قبل navigate (BarqNavHost).
- السحب ينفَّذ باللمسة: سرعة السحبة؛ فحص الإزاحة 45% داخل confirmValueChange (SwipeableRow).
- القائمة تقفز لأعلى بعد الرجوع: Paging فارغ لحظة الاستعادة؛ scrollIndex في InboxViewModel.
- بناء #4: ملف محذوف بقي في المستودع (MessageRepository.kt) وكسر البناء؛ الحل ملف فارغ.
- بناء #8: latestIncoming معرّفة مرتين في MessageDao (Conflicting overloads)؛ احذف المكرر.
- متغير Paging باسم items يتعارض مع items(): استعمل pagingItems.
- زر «رسالة» من الجهات كان يفتح compose بدل المحادثة (حُلّ في 1.2.0 عبر threadResolver).

## Open issues
- لا يوجد docs/code-index.md (مطلوب في بروتوكول المالك).
- ترحيل AGP 9 + compileSdk 37 مؤجل (يفتح Hilt 2.60 وCompose 1.12 وKotlin 2.4).
- MMS والشريحتان لم تُختبرا. استعادة نسخ أندرويد قد لا تعمل خارج Play.
- أفكار غير منفّذة فعلاً: MMS صادر اختياري، اختبارات Compose UI (البحث داخل المحادثة والإحصائيات منفّذان).
- ملفات يحذفها المالك يدوياً (اختياري): res/drawable/ic_star.xml.
