# اصلاح فوری Trivox v8.1 برای GitHub Actions

مبنای این اصلاح: commit `1b25e651` و بسته `Trivox-v8-runtime-ui-wireguard.zip`.

## علت شکست

تست قدیمی `XrayConfigBuilderWireGuardV6Test` انتظار دارد مقدار معتبر
`domainStrategy = AsIs` بدون تغییر حفظ شود. در اصلاح v8، مقدار `AsIs` سهواً
از مجموعه strategyهای معتبر WireGuard حذف شده بود و Builder آن را به
`ForceIPv4` تبدیل می‌کرد؛ بنابراین تست خط 48 شکست می‌خورد.

## تغییر انجام‌شده

- `AsIs` دوباره به strategyهای معتبر WireGuard اضافه شد.
- هشدار Kotlin مربوط به `when` روی `Any?` در پردازش DNS نیز با تبدیل آن به
  یک `when` شرطی رفع شد.
- هیچ قابلیت، UI، منطق اتصال، DNS یا WireGuard حذف یا ضعیف نشده است.

## نصب دستی

محتویات ZIP را در ریشه ریپو Extract و فایل موجود را Replace کنید، سپس:

```bash
./gradlew --no-daemon --stacktrace test lint
```

فایل اصلاحی:

```text
app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt
```
