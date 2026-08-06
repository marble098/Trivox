# Trivox Xray-only Release Notes

این نسخه ریپو را روی یک مسیر ساده و قابل نگهداری نگه می‌دارد:

- فقط `Xray core 26.7.28` از طریق `libXray.aar` استفاده می‌شود.
- build و دریافت core فقط روی GitHub Actions انجام می‌شود.
- نسخه اولیه برنامه `1.0.0` است و workflow از اجرای بعدی می‌تواند نسخه patch را خودکار افزایش دهد.
- خروجی release شامل APK universal و ABIهای موجود در AAR رسمی است.
- Workflowهای قبلی و اسکریپت‌های patch/debug قدیمی حذف می‌شوند تا CI دوباره چندمسیره و سنگین نشود.
