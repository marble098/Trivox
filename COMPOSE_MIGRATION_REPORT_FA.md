# گزارش مهاجرت Trivox به Jetpack Compose Material 3

تاریخ: 2026-08-08
بسته مبنا: `Trivox-0.2.62`

## مرحله 1 — مهاجرت UI

- Compose در Gradle با Kotlin Compose plugin فعال شد.
- Material 3 و Compose BOM پایدار به وابستگی‌ها اضافه شد.
- پوشه `app/src/main/res/layout` به‌طور کامل حذف شد.
- صفحه اصلی با پنج تب پایین بازطراحی شد: خانه، کانفیگ‌ها، ساب‌ها، ابزارها، تنظیمات.
- App Routing به Compose مستقیم و state-based منتقل شد.
- برای Activityهای پیچیده‌ای که منطق اتصال/ویرایش آن‌ها شدیداً با Viewهای قبلی درهم‌تنیده بود، یک compatibility controller برنامه‌ای ساخته شد. این لایه XML inflate نمی‌کند و فقط قرارداد ID/رویدادهای منطق تست‌شده را نگه می‌دارد؛ UI قابل مشاهده Compose Material 3 است.
- چرخه عمر ComposeView با `DisposeOnViewTreeLifecycleDestroyed` بسته شده است.
- فارسی از RTL استاندارد Android و انگلیسی از LTR پیروی می‌کند.

## مرحله 2 — امکانات تکمیلی UI

- نمای سلامت کلی پروفایل‌ها در Home.
- فیلتر سریع فقط علاقه‌مندی‌ها.
- جست‌وجوی مستقیم کانفیگ‌ها.
- دسترسی متمرکز به انتخاب سریع‌ترین پروفایل، local proxy، backup، پاک‌سازی dead profileها، routing و diagnostics.
- امکان افزودن مستقیم Quick Settings Tile در Android 13+ و راهنمای دستی برای نسخه‌های قدیمی‌تر.
- وضعیت اتصال، زمان اتصال، اطلاعات Exit و تست‌های Live/Real در داشبورد اصلی.

## مرحله 3 — رفع تناقض و باگ

- ارجاع‌های app-level به `R.layout.*` حذف شدند؛ فقط layoutهای استاندارد `android.R.layout` برای Spinnerهای سازگاری باقی مانده‌اند.
- Workflow اصلی از guardهای وابسته به XML قدیمی پاک و verifier اختصاصی Compose اضافه شد.
- Workflowها و patch installerهای تاریخی که می‌توانستند XML قدیمی را دوباره وارد کنند حذف شدند.
- App Routing از `notifyDataSetChanged()` کامل و RecyclerView پنهان خارج شد.
- audit در حالت خارج از Git هم دیگر `__pycache__` تولیدشده توسط validatorها را اشتباهاً فایل tracked حساب نمی‌کند.
- قرارداد 172 شناسه مورد استفاده Kotlin با `ids.xml` بررسی می‌شود.

## مرحله 4 — بهینه‌سازی

- نرخ polling سازگاری UI کاهش یافت و App Routing polling کاملاً حذف شد.
- Compose composition هنگام نابودی Activity آزاد می‌شود.
- dependencyهای فقط tooling/preview از APK dependency graph حذف شدند.
- اسکریپت‌های patch منسوخ حذف شدند تا سورس سبک‌تر و CI پایدارتر شود.
- منطق Core/Network/Data عمدتاً ثابت نگه داشته شد تا ریسک regression اتصال کاهش یابد.

## اعتبارسنجی انجام‌شده

- Android resource validator: PASS
- Android API guards: PASS
- String format validator: PASS
- Compose migration verifier: PASS
- Compose ID contract: PASS — 172 ID
- Trivox technical audit: 0 ERROR
- Kotlin parser heuristic روی تمام فایل‌های Kotlin تغییرکرده: 0 syntax diagnostic
- Bash syntax برای uploader و verifier: PASS
- تست integration اسکریپت جایگزینی روی Git remote محلی: PASS (backup tag + حذف فایل قدیمی + commit + push)

دو warning باقی‌مانده در audit صرفاً maintainability هستند: `MainActivity.kt` و `NordVpnSubscriptionManager.kt` فایل‌های بزرگی‌اند. برای جلوگیری از regression شبکه در این مهاجرت UI، شکستن تهاجمی این دو فایل انجام نشده است.

## محدودیت محیط اعتبارسنجی

اجرای Gradle در محیط ساخت این بسته به دلیل عدم دسترسی DNS به `services.gradle.org` ممکن نبود؛ بنابراین ادعای build محلی ثبت نشده است. Workflow اصلی GitHub Actions همچنان `test` و `lint` را پس از verifierهای آفلاین اجرا می‌کند و build نهایی باید همان gate را طی کند.
