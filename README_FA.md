# Trivox — راهنمای فارسی

Trivox یک کلاینت سبک اندروید برای Xray-core است. رابط آن با XML Viewهای معمولی ساخته شده و فاقد تبلیغات، آنالیتیکس، ردیاب، دسترسی روت و دانلود مخفی هسته در خود اپ است.

## شروع سریع

در لینوکس، macOS، ترموکس یا GitHub Actions:

```bash
./tools/trivox-wizard.sh --core-version v26.7.28 --abis arm64-v8a --prepare
./build.sh all
```

در ویندوز:

```powershell
.\tools\trivox-wizard.ps1 -CoreVersion v26.7.28 -Abis arm64-v8a -Prepare
.\build.ps1 all
```

خروجی‌ها در پوشه `dist/` قرار می‌گیرند و فایل `SHA256SUMS.txt` نیز ساخته می‌شود.

## فایل هسته مناسب

مسیر صحیح، فایل رسمی `libxray-android.zip` از ریلیز متناظر [XTLS/libXray](https://github.com/XTLS/libXray/releases) است. فایل‌های معمولی مانند `Xray-android-arm64-v8a.zip` غالباً شامل فایل اجرایی مستقل هستند و AAR اندرویدی نیستند. ویزارد ساختار ZIP را بررسی می‌کند و این فایل ناسازگار را صرفاً تغییرنام نمی‌دهد.

برای فایل دانلودشده دستی:

```bash
./tools/trivox-wizard.sh --local-core core-input/libxray-android.zip --abis arm64-v8a,armeabi-v7a
```

## حالت‌ها

- پروکسی محلی: Xray بدون رابط VPN اجرا می‌شود و SOCKS/HTTP فقط روی `127.0.0.1` گوش می‌دهند.
- VPN کامل: `VpnService` رابط TUN واقعی می‌سازد و FD آن مستقیماً به TUN inbound رسمی Xray داده می‌شود. helper جدا یا هسته دوم لازم نیست.

## GitHub Actions

پروژه را در GitHub قرار دهید، وارد تب Actions شوید و workflow با نام `Build Trivox APKs` را اجرا کنید. نسخه هسته، ABIها، نوع بیلد و ساخت اختیاری Release قابل انتخاب هستند. در نبود اطلاعات امضای اختصاصی، APK قابل‌نصب با کلید debug ساخته می‌شود و workflow شکست نمی‌خورد.

محدودیت‌ها و جزئیات فنی در [README.md](README.md)، [BUILDING.md](BUILDING.md) و [CORE_INTEGRATION.md](CORE_INTEGRATION.md) آمده‌اند.
