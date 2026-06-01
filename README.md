## TimeMacro Scheduler

Android çalışır MVP (Compose + Room + Navigation) ve mimari iskelet.

Bu repo artık **Macro record** (Accessibility event capture) + **Playback** (Accessibility gesture/node click) + **Scheduler/Tasks** akışını içerir.

Detaylar için `docs/ANDROID_ARCHITECTURE.md` dosyasına bakın.

### İndir & Kur (telefon)

Son sürüm debug APK: [`artifacts/timemacro-fix-tap-scheduler-debug.apk`](artifacts/timemacro-fix-tap-scheduler-debug.apk)

Kurulum:
1. Telefonda Settings → Security → "Install unknown apps" → tarayıcına izin ver.
2. APK'yı GitHub'dan indir (yukarıdaki link → "Download raw file") ve aç.
3. Açıldıktan sonra in-app onboarding'i takip et:
   - POST_NOTIFICATIONS izni (Android 13+) — runtime istenir.
   - Settings → Accessibility → "TimeMacro Scheduler" → enable.
   - (Önerilen) Ignore battery optimizations.

### Build / Run

Komut satırı:

```bash
./gradlew :app:assembleDebug
```

Çalıştırma:
- Android Studio ile projeyi açın → `app` konfigürasyonunu Run edin.

Notlar:
- `local.properties` içinde `sdk.dir` yolu bu makinede `/Users/macbookpro/Library/Android/sdk` olarak ayarlı. Sizde farklıysa güncelleyin.
- **PART 3 (MVP)**: Macro record + playback artık gerçek.

### Debug / Logcat (CRITICAL)

Telefon bağlı mı?

```bash
adb devices
```

Paket adını bul (gerekirse):

```bash
adb shell pm list packages | grep -i timemacro
```

Paket `com.timemacro.scheduler` ise:

- **PID filtreli logcat (önerilen)**:

```bash
adb logcat --pid=$(adb shell pidof -s com.timemacro.scheduler)
```

- **PID yoksa (app çalışmıyorsa) keyword filtreli**:

```bash
adb logcat | grep -i -E "TimeMacro/|CRASH|FATAL|AndroidRuntime|Accessibility|BIND_ACCESSIBILITY|timemacro"
```

- **Crash anını yakalamak için temiz log ile başla**:

```bash
adb logcat -c
adb logcat | grep -i -E "TimeMacro/|CRASH|FATAL|AndroidRuntime|Accessibility|timemacro"
```

- **Accessibility/gesture OS logları**:

```bash
adb logcat | grep -i -E "AccessibilityManager|AccessibilityService|WindowManager|gesture|dispatchGesture|keyEvent"
```

- **App tag filtreli (hızlı)**:

```bash
adb logcat -s "TimeMacro/Accessibility:V" "TimeMacro/Recording:V" "TimeMacro/Playback:V" "TimeMacro/Task:V" "TimeMacro/DB:V"
```

- **Tek komut (kolay)**:

```bash
adb logcat | grep -i "TimeMacro/"
```

- **(Opsiyonel) MIUI/ColorOS servis öldürme logları**:

```bash
adb logcat | grep -i -E "miui|powerkeeper|battery|background|kill|appops|restrict"
```

- **(Opsiyonel) Bugreport**:

```bash
adb bugreport bugreport.zip
```

### Test etme (PART 3)
- **1) Accessibility’i açın**
  - Uygulama → Settings tab → “Accessibility” butonu → **TimeMacro Scheduler** servisini enable edin.
- **2) Macro kaydedin**
  - Macros tab → “New Macro (Record)”
  - “Start Recording” → MediaProjection iznini verin
  - İlk kullanımda **Overlay izni** istenir (STOP balonu için). İzni verin.
  - Ekranda birkaç tıklama / scroll yapın (MVP swipe yerine scroll kaydedebilir)
  - (Opsiyonel) Kayıt sırasında **VOL+ / VOL-** basarak kaydı durdurabilirsiniz (Settings’te açık olmalı)
  - **STOP balonu** ekranda görünür → dokununca kayıt durur
  - Alternatif: “STOP (Overlay is also available)” (veya notification’dan STOP)

#### Overlay STOP bubble test
1) Settings → Overlay iznini ver
2) MacroRecord → Start Recording → overlay bottom STOP + timer görünmeli
3) Overlay STOP bas → kayıt durmalı + overlay kaybolmalı + **TimeMacro app öne gelmeli**
4) Overlay iznini kaldır → kayıt yine çalışmalı (in-app STOP ile)
- **3) Playback test**
  - Macro detail ekranında **“TEST NOW”**
  - App **minimize olur** ve playback hemen başlar (gesture’lar arka plandaki hedef uygulamada çalışır)
  - Playback sırasında ekranda küçük **STOP** (cancel) overlay görünür
  - Sonucu Logs tab’da görebilirsiniz (`SUCCESS` / `FAILED` / `CANCELLED` / `SKIPPED` / `PERMISSION_ERROR`)

### Test etme (PART 4 - Scheduler)
- **0) (Android 12+) Exact alarms izni**
  - Settings tab → “Exact alarms” butonu → izin verin
- **1) Task oluşturun**
  - Tasks tab → “Create Task”
  - `Start time`’ı birkaç dakika ileri ayarlayın
  - `Daily hours to run` örn: 20
  - `Interval mode`:
    - FIXED_INTERVAL → `fixedIntervalMinutes` örn: 5
    - MACRO_BASED → `extraDelayMinutes` örn: 1 (macro duration + delay)
  - Kaydedince task aktif olur ve scheduler alarm kurar
- **2) Alarm tetiklenmesini gözleyin**
  - Zaman gelince foreground “Running macro task…” bildirimi görünebilir
  - Logs tab → `source=ALARM` satırlarıyla sonucu görün
- **3) Reboot testi**
  - Cihazı yeniden başlatın → BootReceiver + WorkManager aktif task’ları yeniden planlar

### Polish Pack testleri

#### (1) Deterministic overdue detection
- Task aktifken DB’de `nextScheduledAt` tutulur.
- Doğrulama:
  - Task’ı aktif et → HomeTasks’ta Next Run gör
  - Uygulamayı force-stop yapıp 15dk beklemeden bile (HealthCheckWorker) alarm missing/overdue ise yeniden schedule eder
  - Logs tab’da `source=SCHEDULER` / `source=ALARM` kayıtlarını gözleyin

#### (2) Real time picker
- TaskEditor’da `Start time` alanı artık **Pick time** ile TimePickerDialog açar.
- Kaydedince zaman `HH:mm` formatında saklanır (leading zero dahil).

#### (3) Playback progress + cancel
- Macro detail ekranında playback sırasında:
  - “Playback: RUNNING • step X/Y” görünür
  - **Cancel Playback** ile koşu iptal edilir
- Scheduled run sırasında:
  - Foreground notification text’i mümkünse “Step X/Y” olarak güncellenir
  - İptal edilirse log status `CANCELLED` olur

### Quick test checklist (Macro fixes)
1) Macro kaydet (isim: **TronTest**) → Macros list’te **TronTest** görünmeli
2) Listeden çöp ikonuyla macro sil → macro listeden kaybolmalı
3) Macro detail aç → **Delete Macro** → silinince Macros list’e geri dönmeli

### Production bugfix test (Tap accuracy + STOP return + Test Now)
1) **Record tap accuracy**
  - Macros → New Macro (Record)
  - 5 tap kaydet: 4 köşe + ekran ortası
  - STOP (overlay) → app MacroRecord’a dönmeli
  - Save → Macro detail açılır → **TEST NOW**
  - Playback sırasında tap’ler aynı noktalara basmalı (Settings’te “Show tap dot during playback” açıkken kırmızı nokta görünür)
2) **STOP bubble return**
  - Kayıt sırasında overlay STOP’a bas → uygulama 1s içinde MacroRecord ekranına gelmeli
3) **Save → TEST NOW**
  - Save sonrası tek tıkla TEST NOW çalışmalı ve Logs’a `source=MANUAL_TEST` satırı düşmeli

### Record UX: auto minimize
- Start Recording → MediaProjection iznini ver → kayıt gerçekten başlayınca uygulama otomatik arka plana gider (minimize).

### Macro auto-naming
- İsim boş bırakılırsa kayıtlar otomatik adlandırılır: **Macro 1, Macro 2, ...** (kalıcı sayaç).

