DWR Gold Signals — مشروع Android كامل
========================================

طريقة الفتح والبناء:
1) افتح Android Studio.
2) File → Open → اختر مجلد المشروع (المجلد اللي بداخله ملف settings.gradle).
3) انتظر Gradle Sync يخلص تلقائياً (يحتاج إنترنت أول مرة عشان يحمّل Gradle wrapper والمكتبات).
4) قبل التشغيل: افتح
   app/src/main/java/com/dwr/goldsignals/MainActivity.java
   وبدّل السطر:
       String apiKey = "YOUR_API_KEY";
   بمفتاحك الحقيقي من https://twelvedata.com (يوجد خطة مجانية).
5) Run ▶ على جهاز حقيقي أو محاكي (Android 7.0 / API 24 فأعلى).
6) لبناء APK للتوزيع: Build → Build Bundle(s) / APK(s) → Build APK(s).

ملاحظات:
- أيقونة التطبيق (ic_launcher) هي أيقونة مبدئية (placeholder) دائرية حمراء/ذهبية،
  بإمكانك استبدالها لاحقاً من Android Studio عبر:
  File → New → Image Asset.
- التطبيق يجلب بيانات XAUUSD كل 30 ثانية من Twelve Data ويحسب إشارات CISD
  (منطقة تحول الهيكل) على إطار M5 كتجربة أولية — هذا ليس نصيحة استثمارية.
- من المهم مراجعة حدود الخطة المجانية لـ Twelve Data (عدد الطلبات بالدقيقة)
  حتى لا يتوقف التحديث بسبب تجاوز الحد.
