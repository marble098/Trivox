# بسته اصلاحی Trivox v12 — Real Delay، NordLynx، OpenSSH و Local Proxy

این بسته برای وضعیت ریپو در commit زیر آماده شده است:

`e7e0d26edce5eb4e03f8d16aec36152de3592a3b`

## روش اعمال

1. از ریپو نسخه پشتیبان بگیرید.
2. محتویات ZIP را در **ریشه ریپو** استخراج کنید و اجازه جایگزینی فایل‌ها را بدهید.
3. از ریشه ریپو اجرا کنید:

```bash
python3 tools/apply_trivox_v12.py
```

4. بررسی ساختاری:

```bash
python3 tools/verify_trivox_v12.py
```

5. تست و بیلد:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## فعال‌کردن باینری واقعی OpenSSH

ریپو فعلی فقط اسکلت OpenSSH دارد و `manifest.json` آن روی `BUILD_REQUIRED` است. فایل workflow در این بسته طوری اصلاح شده که خروجی آماده با مسیرهای صحیح بسازد:

1. در GitHub به **Actions → Build OpenSSH Android assets** بروید.
2. `Run workflow` را اجرا کنید.
3. artifact با نام `trivox-openssh-overlay` را دانلود کنید.
4. آن artifact را در ریشه ریپو استخراج کنید.
5. دوباره دستورهای verify و Gradle را اجرا کنید.

بدون این مرحله، برنامه عمداً خطای واضح «OpenSSH assets have not been built» می‌دهد؛ هیچ باینری جعلی یا ناسازگار داخل APK قرار نمی‌گیرد.

## فرمت افزودن OpenSSH

از گزینه Import/Add existing configuration استفاده کنید:

```text
ssh://USERNAME:PASSWORD@HOST:22?timeout=10#NAME
```

یا:

```text
openssh://USERNAME:PASSWORD@HOST:22?timeout=10#NAME
```

نام کاربری، رمز و نام باید URL-encode شوند. رمز هنگام import مستقیماً به `AndroidKeyStore` منتقل و پیش از ذخیره پروفایل از metadata حذف می‌شود. لینک خام ذخیره‌شده شامل رمز نیست.

## پروفایل‌های Real Delay

- **Turbo:** گروه ۱۲، چهار worker، یک Cloudflare Trace معتبر؛ سریع‌ترین حالت.
- **Balanced:** گروه ۸، سه worker، دو اثبات HTTPS؛ پیش‌فرض پیشنهادی.
- **Accurate:** گروه ۶، دو worker، دو اثبات از سه مقصد؛ فشار کمتر و بررسی عمیق‌تر.
- **Custom:** گروه، worker، timeout، مکث شروع، تعداد مقصد و تعداد اثبات قابل تنظیم است.

هر حالت محدودیت هم‌زمانی دارد و برای لیست‌های بسیار بزرگ، گروه‌بندی انجام می‌شود تا گوشی با صدها process یا thread هم‌زمان تحت فشار قرار نگیرد.

## اصلاح Local Proxy

- listener محلی پیش از تست HTTPS بررسی می‌شود.
- تست DNS-free از مسیر SOCKS قبل از HTTP CONNECT انجام می‌شود.
- در حالت Proxy و DNS پیش‌فرض، bootstrap DNS از مسیر direct انجام می‌شود تا حلقه DNS-via-proxy ایجاد نشود.
- انتخاب صریح **DNS through proxy** همچنان حفظ شده است.
- `sniffing.routeOnly=true` مانع بازنویسی ناخواسته مقصد در mixed inbound می‌شود.

## NordLynx و NordWhisper

NordLynx subscription از قبل در ریپو وجود داشت و پروفایل‌های واقعی WireGuard می‌ساخت. این بسته مسیر DNS و local proxy را اصلاح می‌کند تا همان پروفایل‌ها در Proxy mode قابل استفاده باشند؛ هسته تکراری اضافه نشده است.

NordWhisper یک پروتکل اختصاصی NordVPN است و runtime قابل‌باندل برای کلاینت ثالث منتشر نشده است. بسته، scheme آن را تشخیص می‌دهد و خطای دقیق می‌دهد؛ آن را به WireGuard تبدیل نمی‌کند و اتصال جعلی گزارش نمی‌کند.

## فایل‌های مستقیم و فایل‌های patch‌شونده

فایل‌های کوچک/جدید و جایگزین کامل داخل مسیر واقعی ریپو قرار دارند. برای فایل‌های بزرگ موجود (`Models.kt`، `ConfigParser.kt`، `SettingsActivity.kt`، سرویس‌ها و builder)، اسکریپت patcher با anchorهای دقیق و idempotent تغییر را اعمال می‌کند تا امکانات موجود حذف نشوند.
