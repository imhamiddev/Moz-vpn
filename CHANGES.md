# تغییرات رفع باگ FoxyVPN (بر اساس گزارش text.md)

بیلد Gradle در محیط تولید این تغییرات اجرا نشده؛ فقط بازبینی استاتیک انجام شده. قبل از انتشار حتماً بیلد و روی گوشی تست کنید.

## مراحل انجام‌شده
| مرحله | مورد گزارش | خلاصه |
|---|---|---|
| 1 | 1 | تغییر لوکیشن در حالت متصل → reconnect خودکار (`ACTION_RECONNECT`) + اصلاح پاک شدن `connectJob` |
| 2 | 2 | سرویس از singletonهای `FoxyVpnApp` استفاده می‌کند (Token/ProxyState/Settings) |
| 3 | 3 | `TokenStore` lazy + بازیابی از خرابی Keystore؛ `loadAuth` اول روی IO |
| 4 | 7 | ۴۲۹ دیگر fatal نیست (`RateLimitedError`)، reuse پاس معتبر، رعایت `Retry-After` |
| 5 | 16 | `UpstreamTrust` گواهی‌های `user:` را نادیده می‌گیرد |
| 6 | 15 | پراکسی‌ها: TUN فقط loopback + احراز هویت تصادفی SOCKS5؛ proxy-only با login اختیاری (SOCKS5/HTTP) |
| 7 | 22 | بدون تغییر کد (گزارش اشتباه بود: `setMetered(false)` یعنی ارث‌بری از شبکه)؛ فقط کامنت |
| 8 | 8, 12 | ۴۰۱/۴۰۳ در `activateGuardian` → `TokenInvalidError`؛ `AtomicInteger` برای generation و `@Volatile` |
| 9 | 17, 19, 20 | ریسیور ویجت non-exported؛ پسورد/کد 2FA بدون `rememberSaveable`؛ sign-out با revoke توکن‌ها + پاک‌سازی cookie |
| 10 | 5, 6 | بستن سشن مرده هنگام تغییر شبکه؛ reconnect با `disableNewStreamsAndCloseWhenIdle` |
| 11 | 13, 14 | آزادسازی منابع `onDestroy` خارج از main thread؛ `start()` با try/catch |
| 12 | 4 | `ClientSlotLimiter` (صف‌بندی به جای رد فوری، سقف وابسته به heap) |
| 13 | 9, 10 | retry با املای ایمیل سرور (errno 120)؛ backoff کوتاه/تصاعدی و round-robin برای DoH |
| 14 | 11 | پنجرهٔ هر استریم 2MB، پنجرهٔ کانکشن 16MB |
| 15 | 18 | `AppLogger.d` فقط debug + حذف با R8؛ حذف مقصد از لاگ قابل export |
| 16 | 23 | WifiLock: `FULL_LOW_LATENCY` از اندروید ۱۰؛ `WAKE_LOCK` لازم است و نگه داشته شد |
| 17 | 24 | `MIN_MTU = 1280` (حداقل IPv6) و clamp هنگام خواندن |
| 18 | 32 | `Call.await()` لغوپذیر به جای `execute()` |
| 19 | 33, 35 | «Recommended» = کم‌پینگ‌ترین شهر؛ نمایش نام شهر |
| 20 | 36, 37 | کش ۱۰ دقیقه‌ای `fetchUserInfo`؛ ولیدیشن پورت (≥1024، تکراری نبودن) |
| 21 | 34, 39 | نشان واقعی (Protected / Proxy only / Reconnecting)؛ نوتیفیکیشن هشدار قطع fatal |
| 22 | 31, 28 | حذف تأخیر Splash، کاهش settle، انتظار slot رویدادمحور، sampling کم‌تر با صفحهٔ خاموش |
| 23 | 30 | پراکسی HTTP: `Connection: close` برای درخواست‌های forward + ورودی بافردار |
| 24 | 27, 26 | کش پاسخ DoH؛ `excludeRoute` برای LAN در API 33+ |
| 25 | 25 | بدون تغییر: تونل فقط TCP حمل می‌کند؛ رفع نیازمند MASQUE/CONNECT-UDP است |

## انجام‌نشده / اختیاری
مورد 29 (کپی فریم‌ها)، 38 (ترجمه/RTL)، 21 (تصمیم‌های محصولی UA/کوکی)، و موارد بیلد/نگهداری (آپدیت وابستگی‌ها، pin کردن commit، تست واحد).

## چک‌لیست تست روی گوشی
1. وصل شدن در حالت TUN و باز شدن سایت‌ها (hev با login تصادفی SOCKS5 وصل می‌شود).
2. نمایش IP/کشور خروجی در Home (مسیر `Authenticator` برای SOCKS).
3. عوض کردن لوکیشن وقتی وصل هستید؛ همچنین وسط Connecting.
4. خاموش کردن Wi-Fi وسط دانلود و برگشت روی موبایل (باید در چند ثانیه reconnect شود).
5. proxy-only با و بدون login: `curl --socks5 user:pass@IP:port` و `curl -x http://user:pass@IP:port`.
6. دسترسی به روتر/پرینتر (192.168.x.x) روی اندروید ۱۳+ هنگام اتصال.
7. سرعت دانلود تکی بزرگ و هم‌زمان چند تب (پنجرهٔ 2MB).
8. ساین‌اوت و ساین‌این دوباره؛ لاگین با ایمیل حروف بزرگ/کوچک.
9. ویجت صفحهٔ اصلی بعد از آپدیت اپ (ممکن است تا اولین آپدیت ویجت کلیک اثر نکند).
10. بیلد release (R8): مطمئن شوید قانون `-assumenosideeffects` مشکلی نمی‌سازد.
