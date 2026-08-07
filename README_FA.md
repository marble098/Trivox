# Trivox — نسخه Compose Material 3

Trivox یک کلاینت سبک اندرویدی مبتنی بر Xray/libXray است. رابط کاربری این نسخه از layoutهای XML به **Jetpack Compose + Material 3** مهاجرت کرده و صفحه اصلی به پنج تب پایین تقسیم شده است: خانه، کانفیگ‌ها، ساب‌ها، ابزارها و تنظیمات.

## نکات اصلی

- تمام قابلیت‌های اتصال، VPN/Proxy، import/export، سابسکریپشن، تست TCP و Real Delay، DNS، WireGuard/NordLynx، OpenSSH، app routing و diagnostics حفظ شده‌اند.
- `app/src/main/res/layout` دیگر وجود ندارد و UI قابل مشاهده با Compose ساخته می‌شود.
- برای کاهش ریسک رگرسیون، منطق شبکه/Core/Data دست‌کاری بنیادی نشده است و کنترلرهای تست‌شدهٔ قبلی در نقاط پیچیده از یک compatibility bridge برنامه‌ای (بدون XML) استفاده می‌کنند.
- App Routing به Compose مستقیم منتقل شده و دیگر RecyclerView قدیمی ندارد.
- صفحه اصلی نمای وضعیت اتصال، سلامت پروفایل‌ها، فیلتر علاقه‌مندی‌ها و دسترسی متمرکز به ابزارهای پرتکرار دارد.
- زبان فارسی RTL و انگلیسی LTR از locale استاندارد اندروید پیروی می‌کنند.

## ساخت

```bash
./tools/trivox-wizard.sh --core-version v26.7.28 --abis arm64-v8a --prepare
./build.sh all
```

یا برای بررسی مهاجرت Compose قبل از build:

```bash
bash tools/verify-compose-migration.sh
python3 tools/audit-trivox.py --ci
```

## GitHub Actions

Workflow اصلی `.github/workflows/main.yml` ابتدا منابع، API guardها، فرمت رشته‌ها و قرارداد Compose را بررسی می‌کند و سپس `test` و `lint` را با Gradle اجرا می‌کند. Workflowهای تاریخی patch که XMLهای قدیمی را بازمی‌گرداندند از این بسته حذف شده‌اند.

## جایگزینی کامل ریپو

فایل `tools/replace-github-repo.sh` از احراز هویت موجود `gh` استفاده می‌کند و هیچ PAT جداگانه‌ای درخواست نمی‌کند. برای کم‌کردن مصرف اینترنت، فقط Git metadata و treeهای آخرین commit را با partial shallow fetch دریافت می‌کند و blobهای قدیمی را checkout نمی‌کند. سپس کل tree ریپو را با محتوای ZIP جایگزین می‌کند، backup tag می‌سازد و با همان حساب لاگین‌شده در `gh` push می‌کند.

```bash
bash /sdcard/Download/replace-trivox-repo-gh-direct-v7.sh \
  /sdcard/Download/Trivox-Compose-Material3-2026-08-08-v6.zip \
  OWNER/REPO main
```

برای PAT کلاسیک، scopeهای `repo` و `workflow` پیشنهاد می‌شوند. توکن را به‌عنوان آرگومان خط فرمان وارد نکنید؛ خود اسکریپت آن را با ورودی مخفی دریافت می‌کند.

## هسته

نسخه پیش‌فرض Xray/libXray برابر `v26.7.28` است و AAR رسمی توسط wizard دریافت و اعتبارسنجی می‌شود. فایل core عمداً داخل سورس commit نشده است.
