# اصلاح Native Subscription و Smart Core در Trivox

این بسته سه ایراد معماری را اصلاح می‌کند:

1. فایل کامل Mihomo YAML دیگر خط‌به‌خط به parser لینک‌های Xray داده نمی‌شود. سندهای دارای `proxy-providers`، `proxy-groups`، `rules`، `dns` یا `tun` به‌صورت یک پروفایل native نگهداری و مستقیماً با Mihomo اجرا می‌شوند.
2. لینک رسمی remote profile سینگ‌باکس و لینک‌های installer مربوط به Mihomo/Clash قبل از اعتبارسنجی HTTPS باز می‌شوند.
3. Smart Core دیگر اولین هسته‌ای را که فقط config را قبول کند انتخاب نمی‌کند. هر candidate روی پورت موقت اجرا می‌شود، listener و عبور HTTPS بررسی می‌شود و بر اساس startup و latency امتیاز می‌گیرد.

## رفتار تبدیل

- تبدیل به همان هسته، سند native کامل را بدون تخریب provider/rule نگه می‌دارد.
- تبدیل Mihomo provider-based به هسته دیگر، ابتدا providerهای HTTPS را resolve می‌کند و سپس proxyهای قابل انتقال را جداگانه به config native مقصد تبدیل و validate می‌کند.
- خروجی تبدیل‌شده محلی و در گروه `نام ساب • نام هسته` ذخیره می‌شود تا آپدیت بعدی ساب اصلی آن را حذف نکند.
- تبدیل lossless بین همه قابلیت‌های اختصاصی هسته‌ها از نظر فنی ممکن نیست. مواردی که مقصد معادل معتبر ندارد، صریحاً fail می‌شوند و config جعلی ساخته نمی‌شود.

## لاگ‌های مورد انتظار Smart Core

```text
Smart core candidate Xray: valid=true, listener=true, proof=true, ...
Smart core candidate sing-box: valid=true, listener=true, proof=true, ...
Smart core candidate mihomo: valid=true, listener=true, proof=false, ...
Smart core winner: sing-box for VPN
```

## لاگ native runtime

```text
Smart core native affinity: mihomo
Starting real outbound core for VPN: mihomo
Starting Xray Android TUN bridge for mihomo
```
