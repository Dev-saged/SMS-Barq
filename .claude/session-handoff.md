# session-handoff

## State (2026-11-17)
- main = 2.2.2 (KSP 2.3.14 + Robolectric 4.18 فقط؛ بناء + Unit tests + Lint خضراء؛ v2.2.2 منشور). قبله 2.2.1 (hotfix: انهيار الوارد بسبب convCount قديم؛ بناء + Unit tests + Lint خضراء). 2.2.0: 2.2.0: بحث بالرقم بأي مقدمة، عرض ونسخ الأرقام خاماً 059/056، plurals عربية (16 مفتاحاً). 2.1.1: نسخ رقم المرسل وتنظيف lint.
- 2.1.0: نسخ بنقرة داخل الرسالة (CopyableValues → MessageBubble.linkify)، بحث بفلاتر (SearchFilters + زر Tune + MessageDao.recent)، تعلّم من تصحيح الشريحة (ThreadViewModel.insertSuggestion → ReplyEngine.onSuggestionEdited)، مسافات أوسع (ConversationRow 14dp، فقاعة 10dp، contentPadding للمحادثة).
- 2.0.x وما قبله كما في docs/repo-map.md (محرك ردود محلي، سلة رسائل Room v6، بحث الإعدادات، README SVG).

## Decisions
- التصنيف التلقائي (Classifier + شرائح OTP/بنوك/عروض/شخصي/مزعج) كان موجوداً، فلم يُعاد بناؤه؛ المضاف فقط فلاتر البحث.
- النسخ بنقرة: قيمة بعد «:» في آخر السطر (تحتاج حرفاً لاتينياً/رقماً/رمزاً)، بريد، رمز مختلط، أرقام ≥4 خانات؛ التواريخ والأوقات والروابط مستثناة.
- التصحيح: الشريحة المعدّلة تفقد use واحداً (وتُحذف إذا نفدت) والنص المرسل يكسب use إضافياً.

## Open issues
- لم يُجرَّب شيء من 1.7.0 إلى 2.1.0 على جوال حقيقي ولا لقطات شاشة؛ المسافات الجديدة لم تُرَ بصرياً.
- عتبات الاسترجاع غير معايَرة. استعادة MMS من السلة في برق فقط.
- plurals: المفاتيح متعددة الأرقام (unread_head، gateway_counts، import_skipped…) والمُلتقطة مسبقاً (sending_many…) ما زالت strings؛ اللغات غير ar/en لها صيغة other فقط.
- ContactScreen وComposeScreen بلا جزر. docs/code-index.md غير موجود. repo-map أكبر من 5KB.
- لا اختبار آلي لـReplyEngine.onSuggestionEdited (يحتاج Room؛ المنطق الصافي مغطى بـReplyIndexTest فقط).

## Next step
- هجرة واحدة لاحقة: AGP 9.1+ وcompileSdk 37 وKotlin 2.4 وHilt 2.60 ثم Compose BOM 2026.09 / Core 1.19 / Lifecycle 2.11 / Nav 2.10 / androidx.hilt 1.4 / serialization 1.13 / coroutines 1.11 (كلها مرتبطة ببعضها؛ جرّبنا فرادى وفشل البناء).
- انتظار ملاحظات ساجد بعد تجربة 2.1.0: دقة التقاط القيم القابلة للنسخ (إضافة/استثناء أنماط في CopyableValues)، وراحة المسافات.
- عند تغيير مخطط Room: Migration جديد ثم التزام app/schemas من artifact «Barq-schemas».
