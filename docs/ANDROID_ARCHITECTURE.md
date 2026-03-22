## Proje Mimarisi + İzin Akışı (Android)

Bu doküman “TimeMacro Scheduler” uygulamasının Android tarafı için:
- repository / package yapısını
- izin & sistem ayarları akışını
- sistem bileşenleri arasındaki orkestrasyonu
- yüksek seviye veri modellerini
tanımlar.

---

## 1) Repository / package yapısı (Android)

Hedefler:
- Jetpack Compose + MVVM (UI → ViewModel → UseCase/Domain → Repository/Data)
- Room (kalıcı veriler: macro/task/log)
- ForegroundService + AlarmManager (zamanlama güvenilirliği)
- AccessibilityService (macro playback / touch injection)
- MediaProjection (screen recording)

Önerilen ana paketler:
- `core/`: framework-agnostic ortak altyapı + Android platform helper’ları
- `data/`: Room/DataStore + repository implementasyonları
- `domain/`: iş kuralları, scheduler/macro motor sözleşmeleri (Android bağımlılığı minimum)
- `service/`: uzun yaşayan Android bileşenleri (accessibility, recorder, scheduler FGS)
- `receiver/`: sistem tetikleri (alarm, boot)
- `ui/` + `viewmodel/`: Compose ekranları ve state yönetimi
- `di/`: bağımlılık bağlama (ileride Hilt/Koin vb.)

Dosya/klasör iskeleti (kaynak kodu):
- `app/src/main/java/com/timemacro/scheduler/...`

---

## 2) İzin & System Settings akışı

Genel prensipler:
- İzinleri “özelliğe ilk ihtiyaç” anında iste
- Her adımda “neden” + “reddedilirse ne olur” açıklaması göster
- Deep link ile ilgili ayarlara tek tuşla yönlendir

### AccessibilityService (zorunlu)
- Ne zaman: Onboarding’de “Playback” adımı + ilk playback denemesinde kontrol
- Neden: Gesture/tap/swipe injection, UI event gözlemi
- Deny: Playback çalışmaz; Tasks ekranında “Enable Accessibility” CTA
- Deep link: `Settings.ACTION_ACCESSIBILITY_SETTINGS`

### MediaProjection (kayıt için zorunlu)
- Ne zaman: Kullanıcı “Record Macro” dediğinde
- Neden: Ekran yakalama Android’de sadece MediaProjection ile
- Deny: Kayıt iptal; kullanıcı tekrar deneyebilir
- Deep link: Yok (sistem intent popup)

### Foreground Service (mimari zorunluluk)
- Ne zaman: Zamanlama/oynatma sırasında servis başlatılır
- Neden: Arka planda güvenilir çalışma penceresi
- Deny: Runtime izni yok; ancak bildirim izni yoksa FGS riskli hale gelir

### Notification permission (Android 13+)
- Ne zaman: Onboarding’de “Arka plan bildirimleri” adımında
- Neden: FGS notification + run status
- Deny: Scheduling’i “limited” modda çalıştırma ya da engelle (önerilen: engelle + açıklama)
- Deep link: `Settings.ACTION_APP_NOTIFICATION_SETTINGS`

### Ignore battery optimizations (opsiyonel, önerilir)
- Ne zaman: İlk task aktif edilirken veya scheduling start edilirken
- Neden: Doze gecikmelerini azaltır
- Deny: Çalışır ama zamanlama kayabilir; UI’da reliability uyarısı
- Deep link: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`

### Overlay permission (opsiyonel)
- Ne zaman: Floating kontrol paneli açılmak istenince
- Neden: Kayıt/oynatmayı ekrandan yönetmek
- Deny: Widget yok; notification ile yönetim
- Deep link: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`

### Önerilen onboarding sırası
1) Hoş geldin + kısa özet
2) Bildirim izni (Android 13+)
3) Accessibility enable
4) (Ops) Battery optimization ignore
5) (Ops) Overlay
6) İlk macro kaydı (MediaProjection prompt burada)

---

## 3) Sistem servisleri orkestrasyonu

Roller:
- AlarmManager: “tam zaman” tetikleyicisi
- AlarmReceiver: alarm event’ini yakalar, SchedulerForegroundService’i ayağa kaldırır
- SchedulerForegroundService: koşulları doğrular, MacroRunner’ı başlatır, log yazar
- AccessibilityService: MacroRunner aracılığıyla gesture injection ile playback’i icra eder
- WorkManager: retry/backoff, plan recompute, log cleanup gibi ertelemeye toleranslı işler
- BootReceiver: reboot sonrası aktif task’ları DB’den okuyup alarmları yeniden kurar

Net cevaplar:
- Playback’i kim tetikler? AlarmManager → AlarmReceiver → SchedulerForegroundService → AccessibilityService (icra)
- Arka plan/screen-off altında kim ayakta kalır? ForegroundService + AccessibilityService en dayanıklısı
- Reboot recovery kimde? BootReceiver

---

## 4) Yüksek seviye veri modelleri

Bu modeller “domain model” olarak düşünülür; Room entity’leri ayrı tutulabilir.

---

## PART 3 (MVP): Recording + Playback gerçek akış

### Recording akışı (MediaProjection)
- UI (`MacroRecordScreen`)
  - “Start Recording” → `MediaProjection` permission intent (ActivityResult API)
  - Permission OK → `ScreenRecorderService` (FGS) `ACTION_START` ile başlar
- `ScreenRecorderService`
  - `MediaProjection` + `MediaRecorder` + `VirtualDisplay` ile MP4 üretir
  - Çıktı: app external files `Movies/TimeMacro/` altında timestamp’li `.mp4`
  - Bildirim: “Recording…” + **STOP** action
  - STOP (UI veya bildirim) → recorder/projection cleanup → `MacroSessionManager.onStopped()`

### Action capture (Accessibility)
- `MacroSessionManager` recording aktifken:
  - `TYPE_VIEW_CLICKED` → TAP (x,y)
  - `TYPE_VIEW_SCROLLED` (API 28+) → SCROLL(direction, amount)
  - Aksiyonlar arası zaman farkı \(>150ms\) ise otomatik WAIT eklenir

**Limitasyon (MVP):**
- Accessibility event’lerinden gerçek “swipe path” çıkarmak güvenilir değil.
- Bu yüzden MVP’de “swipe” yerine ağırlıklı olarak **SCROLL** kaydedilir; playback’te bu, sabit mesafeli swipe gesture’a çevrilir.

### Playback (dispatchGesture)
- UI (`MacroDetailScreen`) “Test Playback” → `MacroRunner.runMacro(macroId)`
- `MacroRunner` request’i `MacroPlaybackBus` üzerinden `MacroAccessibilityService`’e iletir
- `MacroAccessibilityService` JSON’u parse eder ve sırayla:
  - WAIT → delay
  - TAP → dispatchGesture(tap)
  - SWIPE/SCROLL → dispatchGesture(swipe)
- Sonuç `LogEntry` olarak DB’ye yazılır (`SUCCESS` / `FAILED`).

---

## PART 4: Scheduler + TaskRunner (AlarmManager + FGS + WorkManager)

### Temel akış
- **Task aktif edilince** (UI toggle / save):
  - `SchedulerEngine.schedule(task)` → `AlarmManager.setExactAndAllowWhileIdle(...)`
- **Alarm tetiklenince**
  - `AlarmReceiver` → `SchedulerForegroundService` (FGS) başlatır
  - Service içinde `TaskRunner` çalışır:
    - prereq check (Accessibility / macro var mı / expiry)
    - `MacroRunner.runMacro(...)` (AccessibilityService üzerinden)
    - timeout (10 dk)
    - `LogEntry` yazar (`SUCCESS` / `FAILED` / `SKIPPED` / `PERMISSION_ERROR`)
  - Ardından `SchedulerEngine.schedule(task)` ile **bir sonraki run** planlanır

### Doze / güvenilirlik
- Exact alarm: `setExactAndAllowWhileIdle` Doze altında bile en doğru tetikleyicidir.
- Safety net: **WorkManager** (15dk) `HealthCheckWorker`
  - aktif task’larda alarm missing/overdue durumunu toparlar

### Reboot recovery
- `BootReceiver` → `BootRescheduleWorker` → `rescheduleAllActiveTasks()`

### Limitasyonlar (MVP)
- Exact alarm izni (Android 12+) kapalıysa scheduler alarm kuramaz; Settings ekranından kullanıcıya yönlendirme yapılır.
- Swipe capture hâlâ “scroll heuristics” tabanlıdır (PART 3 limitasyonu).

