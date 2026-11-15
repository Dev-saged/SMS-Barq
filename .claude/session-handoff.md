# session-handoff

## State (2026-11-15)
- main = 2.1.0 بعد الدمج (الفرع feature/smart-inbox-2-1-0 أخضر: بناء + Unit tests + Lint).
- 2.1.0: نسخ بنقرة داخل الرسالة (CopyableValues → MessageBubble.linkify)، بحث بفلاتر (SearchFilters + زر Tune + MessageDao.recent)، تعلّم من تصحيح الشريحة (ThreadViewModel.insertSuggestion → ReplyEngine.onSuggestionEdited)، مسافات أوسع (ConversationRow 14dp، فقاعة 10dp، contentPadding للمحادثة).
- 2.0.x وما قبله كما في docs/repo-map.md (محرك ردود محلي، سلة رسائل Room v6، بحث الإعدادات، README SVG).

## Decisions
- التصنيف التلقائي (Classifier + شرائح OTP/بنوك/عروض/شخصي/مزعج) كان موجوداً، فلم يُعاد بناؤه؛ المضاف فقط فلاتر البحث.
- النسخ بنقرة: قيمة بعد «:» في آخر السطر (تحتاج حرفاً لاتينياً/رقماً/رمزاً)، بريد، رمز مختلط، أرقام ≥4 خانات؛ التواريخ والأوقات والروابط مستثناة.
- التصحيح: الشريحة المعدّلة تفقد use واحداً (وتُحذف إذا نفدت) والنص المرسل يكسب use إضافياً.

## Open issues
- لم يُجرَّب شيء من 1.7.0 إلى 2.1.0 على جوال حقيقي ولا لقطات شاشة؛ المسافات الجديدة لم تُرَ بصرياً.
- عتبات الاسترجاع غير معايَرة. استعادة MMS من السلة في برق فقط.
- ContactScreen وComposeScreen بلا جزر. docs/code-index.md غير موجود. repo-map أكبر من 5KB.
- لا اختبار آلي لـReplyEngine.onSuggestionEdited (يحتاج Room؛ المنطق الصافي مغطى بـReplyIndexTest فقط).

## Next step
- انتظار ملاحظات ساجد بعد تجربة 2.1.0: دقة التقاط القيم القابلة للنسخ (إضافة/استثناء أنماط في CopyableValues)، وراحة المسافات.
- عند تغيير مخطط Room: Migration جديد ثم التزام app/schemas من artifact «Barq-schemas».
