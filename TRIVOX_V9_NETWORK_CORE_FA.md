# بسته اصلاحی Trivox v9 — شبکه، اتصال و WireGuard

مبنای بررسی: شاخه `main` در commit زیر:

`05a32f27d58276adb33b06ddba07a3402a912e5f`

فایل‌های داخل این ZIP از ریشه ریپو شروع می‌شوند. محتویات ZIP را در ریشه پروژه Extract کنید و فایل‌های هم‌نام را جایگزین کنید.

## تغییرات اصلی

### اتصال و Latency

- بررسی سلامت اتصال همچنان فقط با ترافیک HTTPS واقعی از مسیر فعال انجام می‌شود؛ TCP Ping و Real Delay صرفاً معیار رتبه‌بندی باقی می‌مانند.
- بررسی شروع اتصال پس از رسیدن به حداقل نمونه‌های معتبر زودتر خاتمه پیدا می‌کند و در حالتی که موفقیت دیگر ممکن نیست، سریع‌تر Fail می‌شود.
- زمان آغاز بررسی برای Proxy، VPN و WireGuard جدا شده و حالت تطبیقی برای اتصال سریع‌تر اضافه شده است.
- بودجه هندشیک WireGuard از تنظیمات قابل کنترل است و لغو اتصال همچنان cooperative باقی می‌ماند.

### هندشیک و Sockopt

- گزینه‌های مستند Xray شامل TCP Fast Open، TCP Keep-Alive، TCP User Timeout و Happy Eyeballs به صورت محافظه‌کارانه اضافه می‌شوند. Happy Eyeballs فقط در مسیرهایی فعال می‌شود که با targetStrategy یا dialerProxy تعارض نداشته باشند؛ برای کانفیگ تک‌مرحله‌ای، bootstrap محدود DNS از ایجاد حلقه جلوگیری می‌کند.
- هیچ مقدار صریح موجود در کانفیگ Provider بازنویسی نمی‌شود.
- گزینه‌های TCP روی transportهای UDP-first مانند mKCP و Hysteria تحمیل نمی‌شوند.

### سازگاری کانفیگ

- یک لایه سازگاری غیرمخرب برای JSON کامل Xray اضافه شده است.
- aliasهای هم‌معنی پروتکل و transport نرمال می‌شوند؛ برای مثال `ss`، `wg`، `hy2`، `raw`، `ws`، `kcp` و فیلد جدید `method`.
- فیلدهای ناشناخته حذف نمی‌شوند و تصمیم نهایی اعتبارسنجی با Xray 26.7.28 است.
- Hysteria2 در JSON کامل به ساختار canonical هسته تبدیل می‌شود و aliasهای متداول server/host، server_port، password/auth/token، SNI و insecure نیز نرمال می‌شوند.
- تبدیل اجباری transportهای حذف‌شده‌ای مانند HTTP/2 قدیمی یا QUIC قدیمی انجام نمی‌شود، چون چنین تبدیلی می‌تواند سازگاری سمت سرور را خراب کند.

### WireGuard مبتنی بر Xray

- این بسته از outbound داخلی WireGuard در Xray 26.7.28 استفاده می‌کند؛ هسته بومی جداگانه WireGuard-Go به پروژه تحمیل نشده است.
- MTU سقفی، workers، keep-alive، زمان بررسی هندشیک و domainStrategy در Settings اضافه شده‌اند. کانفیگ WireGuard ساخته‌شده از بخش Manual دیگر مقدار ForceIP را هاردکد نمی‌کند تا همین تنظیمات واقعاً اعمال شوند.
- MTU پایین‌تر واردشده توسط Provider هرگز افزایش داده نمی‌شود.
- `keepAlive=0` صریح حفظ می‌شود.
- `AllowedIPs`، aliasهای کلیدها، endpoint، reserved و ساختار تک-peer/چند-peer نرمال می‌شوند.
- `streamSettings` برای WireGuard حذف و `noKernelTun=true` اعمال می‌شود.
- فیلدهای اختصاصی AmneziaWG همچنان با خطای روشن رد می‌شوند، چون Xray WireGuard نمی‌تواند آن obfuscation را شبیه‌سازی کند.

### Memory و Buffer

- listenerهای سراسری وضعیت اتصال با WeakReference نگهداری می‌شوند تا Activity فراموش‌شده در حافظه قفل نشود.
- یک Buffer Pool کوچک، محدود و قابل پاک‌سازی برای درخواست‌های شبکه بخش Kotlin اضافه شده است.
- بافرها پیش از بازگشت به Pool پاک می‌شوند و در `onTrimMemory` و `onLowMemory` آزاد می‌شوند.
- ترافیک اصلی VPN/Proxy داخل libXray باقی می‌ماند؛ مقدار Buffer در Settings مربوط به خواندن‌های app-side است و وانمود نمی‌کند که اندازه بافر داخلی Go/Xray را تغییر می‌دهد.

## فایل‌های تست جدید

- `AppSettingsNetworkTuningV9Test.kt`
- `XrayConfigBuilderNetworkTuningV9Test.kt`
- `ManualConfigFactoryWireGuardV9Test.kt`
- `NetworkBufferPoolV9Test.kt`

## اعتبارسنجی انجام‌شده

- XMLهای بسته با parser استاندارد XML بررسی شدند.
- فایل‌های خالص Kotlin مربوط به Model، CoreManager، XrayConfigBuilder، compatibility، verifier، buffer pool و ConnectionInfoManager با stubهای محدود کامپایل شدند.
- تطابق تمام IDهای استفاده‌شده در SettingsActivity با activity_settings.xml بررسی شد.
- کنترل SHA-256 برای تمام فایل‌ها و سلامت ZIP انجام می‌شود و نتیجه در `SHA256SUMS.txt` قرار دارد.

## محدودیت تست

در این محیط APK واقعی ساخته نشد و تست روی دستگاه واقعی یا libXray AAR انجام نشد. پس از جایگزینی فایل‌ها، workflow فعلی پروژه که `test` و `lint` را اجرا می‌کند باید مرجع نهایی باشد. تنظیمات پیش‌فرض محافظه‌کارانه‌اند و TCP Fast Open به‌صورت پیش‌فرض خاموش است.

«پشتیبانی کامل» در این بسته یعنی تمام پروتکل‌ها و transportهایی که خود Xray 26.7.28 می‌پذیرد، همراه با aliasهای امن و JSON کامل. transportهای حذف‌شده از خود هسته، مانند HTTP/2 قدیمی و QUIC قدیمی، بدون دانستن تنظیمات سمت سرور قابل تبدیل تضمینی نیستند و عمداً به کانفیگ دیگری جعل نمی‌شوند.
