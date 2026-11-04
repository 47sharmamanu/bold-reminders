# Bold Reminders

Calendar events that ring like a real alarm on Android: full screen over the lock screen, loud until you dismiss it.

## Download

Latest APK: **[bold-reminders.apk](https://github.com/47sharmamanu/bold-reminders/releases/latest/download/bold-reminders.apk)**

Open the link on your Android phone, download, and install (allow "Install unknown apps" for your browser when asked). New versions install over the old one.

## Status

**Stage A — one test alarm.** The app can set a test alarm 1 or 2 minutes ahead and ring it properly:

- Full-screen alarm over the lock screen; heads-up banner with sound when the phone is in use
- Plays on the alarm audio stream and vibrates
- Rings for up to 10 minutes, then stops and leaves a "Missed alarm" notification
- Survives app close, reboot and time changes (alarms are restored)
- Setup checklist and an on-screen log of scheduled, rung, dismissed and missed alarms

Next stages: read Google Calendar from the phone, ring selected calendars at event start, keep alarms in sync.

## How it's built

- **Flutter (Dart)** — screens (`lib/main.dart`)
- **Kotlin** — the native alarm layer that works while the app is closed (`android/app/src/main/kotlin/...`): `AlarmScheduler` (AlarmManager.setAlarmClock), `AlarmService` (sound, vibration, 10-minute cap), `AlarmActivity` (full-screen screen), `BootReceiver` (restore after restart)
- **GitHub Actions** — builds the APK on every push to `main` and publishes it as a release. Standard Android build files are generated in CI by `flutter create`.

## First-time setup on the phone

1. Install the APK and open **Bold Reminders**.
2. Fix any orange items in the setup checklist (notifications, exact alarms, full-screen alarm, battery).
3. On Samsung: Settings → Battery → Background usage limits → add Bold Reminders to **Never sleeping apps**.
4. Tap **Test alarm in 2 min**, lock the phone, and wait.
