# repo-map

Keep under 5KB. No code. Update by 2 lines after every change.
المرجع الكامل: docs/PROJECT_CONTEXT.md (649 سطراً، اقرأ بالأقسام عبر Grep على العناوين، لا كاملاً).

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
- docs/: PROJECT_CONTEXT.md، PHASE3_PLAN.md، PUBLISHING.md، releases/vX.Y.Z.md

## Versions
- 1.0 أساس · 1.1 جهات · 1.2 بحث وقوائم · 1.3 محرك إرسال · 1.4 بوابة ومجموعات · 1.5.0 قفل وExcel ومفضلة (DB 4) · 1.5.1 Noto · 1.5.2 حد 6 ساعات · 1.6.0 غير المقروءة (أخضر) · 1.6.1 فاشلة/سحب/اتصال (قيد البناء)

## Decisions
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
- سجل المكالمات يفتح المحادثة المفتوحة: launchSingleTop على thread/{id}؛ احذف المفتوحة قبل navigate (BarqNavHost).
- السحب ينفَّذ باللمسة: سرعة السحبة؛ فحص الإزاحة 45% داخل confirmValueChange (SwipeableRow).
- القائمة تقفز لأعلى بعد الرجوع: Paging فارغ لحظة الاستعادة؛ scrollIndex في InboxViewModel.
- بناء #4: ملف محذوف بقي في المستودع (MessageRepository.kt) وكسر البناء؛ الحل ملف فارغ.
- بناء #8: latestIncoming معرّفة مرتين في MessageDao (Conflicting overloads)؛ احذف المكرر.
- متغير Paging باسم items يتعارض مع items(): استعمل pagingItems.
- زر «رسالة» من الجهات كان يفتح compose بدل المحادثة (حُلّ في 1.2.0 عبر threadResolver).

## Open issues
- لا يوجد docs/code-index.md ولا version.json (مطلوبان في بروتوكول المالك؛ يُنشآن عند أول عمل).
- MMS والشريحتان لم تُختبرا. استعادة نسخ أندرويد قد لا تعمل خارج Play.
- أفكار غير منفّذة فعلاً: MMS صادر اختياري، اختبارات Compose UI (البحث داخل المحادثة والإحصائيات منفّذان).
- ملفات يحذفها المالك يدوياً (اختياري): res/drawable/ic_star.xml.
