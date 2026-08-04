# بسته اصلاحی Trivox v11

مبنای بررسی و اصلاح: ریپوی `marble098/Trivox`، شاخه `main`، commit:

```text
ca58af07f4a45408dde350534d2baf909cdf8289
```

این بسته قابلیت‌های موجود را حذف نمی‌کند. فایل‌های جدید در مسیر واقعی پروژه قرار گرفته‌اند و اصلاح فایل‌های بزرگ موجود با patcher محافظت‌شده انجام می‌شود تا فایل ناقص یا نسخه‌ای ناسازگار روی ریپو جایگزین نشود.

## تغییرات

### 1. سریع‌تر شدن Real Delay All

فایل جدید:

```text
app/src/main/java/com/trivox/client/network/BatchRealDelayRunner.kt
```

- تا ۶ پروفایل را در یک اجرای موقت Xray قرار می‌دهد؛ برای هر پروفایل inbound SOCKS و outbound مستقل می‌سازد.
- فقط دو probe هم‌زمان اجرا می‌شود تا CPU، RAM، باتری و تعداد socketها کنترل شود.
- هر پروفایل همچنان دو اثبات HTTPS مستقل دریافت می‌کند: Cloudflare Trace معتبر و پاسخ دقیق 204.
- فقط زمانی نتیجه موفق ثبت می‌شود که هر دو probe موفق باشند؛ بنابراین سرعت با حذف اعتبارسنجی به دست نیامده است.
- پروفایل‌های chain یا گروه‌هایی که validation/start آن‌ها شکست بخورد، خودکار به روش قبلی و تک‌پروفایلی برمی‌گردند.
- DNSهای تولیدشده برای اعضای گروه ادغام می‌شوند تا bootstrapهای دامنه‌ای حذف نشوند.

### 2. زیرساخت کامل باینری OpenSSH برای Android

فایل‌ها:

```text
app/src/main/java/com/trivox/client/ssh/OpenSshBinaryManager.kt
app/src/main/java/com/trivox/client/ssh/OpenSshCommand.kt
app/src/main/assets/openssh/manifest.json
.github/workflows/openssh-binaries.yml
tools/openssh/build-openssh-android.sh
tools/openssh/prepare-termux-properties.py
tools/openssh/write-manifest.py
```

- suite کلاینت شامل `ssh`، `scp`، `sftp`، `ssh-add`، `ssh-agent`، `ssh-keygen` و `ssh-keyscan` است.
- سه ABI هدف: `arm64-v8a`، `armeabi-v7a` و `x86_64`.
- OpenSSH و dependencyهای runtime با prefix خود Trivox ساخته می‌شوند:

```text
/data/data/com.trivox.client/files/usr
```

- فایل‌های اجرایی، libraryها، helperها و configهای runtime با SHA-256 در manifest ثبت می‌شوند.
- نصب runtime اتمیک است، checksum بررسی می‌شود، path traversal رد می‌شود و permission مناسب اعمال می‌شود.
- wrapper آماده برای Dynamic SOCKS Forward با key authentication، known_hosts، keepalive و ExitOnForwardFailure وجود دارد.

**نکته مهم:** باینری‌های نهایی داخل این ZIP قرار نگرفته‌اند، چون باید برای package/prefix واقعی برنامه در محیط Android/Termux build بازسازی شوند. کپی‌کردن باینری آماده Termux با prefix `com.termux` قابل اعتماد نیست. پس از قرار دادن این بسته در ریپو، Workflow با نام `Build OpenSSH Android assets` را اجرا کنید، artifact را دانلود کنید و پوشه خروجی `openssh` را جایگزین این مسیر کنید:

```text
app/src/main/assets/openssh
```

تا قبل از این مرحله، manifest عمداً مقدار `BUILD_REQUIRED` دارد و manager به‌جای اجرای فایل ناسازگار، خطای واضح می‌دهد.

### 3. مرتب‌سازی ترکیبی TCP + Real

- پروفایل دارای هر دو نتیجه سالم در اولویت اول است.
- امتیاز ترکیبی: ۴۰٪ TCP و ۶۰٪ Real Delay.
- پروفایل دارای فقط یکی از دو نتیجه، با penalty پنج‌ثانیه‌ای پایین‌تر قرار می‌گیرد.
- حالت‌های `SMART`، `LOWEST_LATENCY` و گزینه انتخاب سریع‌ترین همگی از همین منطق استفاده می‌کنند.
- Favorite همچنان در SMART اولویت قبلی خود را حفظ می‌کند.

### 4. کوتاه شدن Configuration

متن‌های قابل‌نمایش انگلیسی و فارسی به شکل زیر کوتاه می‌شوند:

```text
Configuration  -> Config
Configurations -> Configs
پیکربندی       -> کانفیگ
```

نام resourceها، preference keyها و identifierهای داخلی تغییر نمی‌کنند تا سازگاری نسخه‌های قبلی از بین نرود.

### 5. خروجی لینک‌های یک ساب با نگه‌داشتن انگشت

در منوی long-press نام ساب در صفحه اصلی، گزینه زیر اضافه می‌شود:

```text
Export config links / خروجی لینک‌های کانفیگ
```

فقط URIهای قابل اشتراک و غیرخالی، بدون تکرار، با `ACTION_SEND` صادر می‌شوند. JSON داخلی یا داده‌ای که share-link معتبر نیست وارد خروجی نمی‌شود.

## نصب دستی

1. محتویات ZIP را در ریشه ریپو Extract کنید؛ مسیرها باید مستقیماً با `app/`، `.github/` و `tools/` شروع شوند.
2. ابتدا فقط سازگاری نسخه را بررسی کنید:

```bash
python3 tools/apply_trivox_v11.py . --check
```

3. سپس اصلاحات را اعمال کنید:

```bash
python3 tools/apply_trivox_v11.py .
```

4. تست و lint پروژه:

```bash
./gradlew --no-daemon --stacktrace test lint
```

5. برای تولید OpenSSH، فایل‌ها را commit/push کنید و Workflow زیر را دستی اجرا کنید:

```text
Build OpenSSH Android assets
```

6. artifact تولیدی را در `app/src/main/assets/openssh` جایگزین و سپس APK را build کنید.

## رفتار ایمن patcher

- اگر anchorهای commit مبنا پیدا نشوند، عملیات متوقف می‌شود و فایل‌ها نصفه اصلاح نمی‌شوند.
- اجرای دوباره idempotent است.
- ابتدا `--check` را اجرا کنید؛ اگر ریپو بعد از commit مبنا تغییر کرده باشد، قبل از Replace شدن فایل‌ها متوجه می‌شوید.

## اعتبارسنجی انجام‌شده روی بسته

- Python syntax: موفق
- Shell syntax (`bash -n`): موفق
- Kotlin syntax/type contract فایل‌های جدید با API stub: موفق
- patch anchor validation: موفق
- idempotency اجرای دوباره patcher: موفق
- حفظ resource identifier و preference-like keyها هنگام کوتاه‌سازی متن: موفق
- Gradle/Android full build: در این محیط قابل اجرا نبود، چون checkout کامل ریپو، Android SDK و libXray AAR در محیط ساخت بسته در دسترس نبود.
- OpenSSH cross-build واقعی: باید در GitHub Actions اجرا شود؛ خروجی باینری در این محیط تولید نشده است.
