# Bold Reminders

Calendar events that ring like a real alarm on Android: full screen over the lock screen, loud until you dismiss it.

## Download

Latest APK: **[bold-reminders.apk](https://github.com/47sharmamanu/bold-reminders/releases/latest/download/bold-reminders.apk)**

Open the link on your Android phone, download, and install (allow "Install unknown apps" for your browser when asked). New versions install over the old one.

## Status

**Calendar alarms.** Timed events on the calendars you choose ring at their start time:

- Reads the calendars already on the phone (no Google sign-in); default: calendars named "Meeting…" or "Deep work…"
- Next 48 hours, one alarm per start time; all-day, cancelled and declined events are skipped
- Same event on two calendars rings once; events at the same minute share one alarm
- Moved or deleted events update their alarm; events that arrive more than 5 minutes late are skipped and logged
- Syncs when calendar data changes, about every 15 minutes, on app open and after a restart
- Full-screen alarm over the lock screen; heads-up banner with sound when the phone is in use
- Rings on the alarm audio stream for up to 10 minutes, then leaves a "Missed alarm" notification
- Home screen lists upcoming alarms; Settings holds calendars, setup checklist, test alarm and log

## How it's built

- **Flutter (Dart)** — screens (`lib/main.dart`)
- **Kotlin** — the native alarm layer that works while the app is closed (`android/app/src/main/kotlin/...`): `AlarmScheduler` (AlarmManager.setAlarmClock), `AlarmService` (sound, vibration, 10-minute cap), `AlarmActivity` (full-screen screen), `BootReceiver` (restore after restart)
- **GitHub Actions** — builds the APK on every push to `main` and publishes it as a release. Standard Android build files are generated in CI by `flutter create`.

## First-time setup on the phone

1. Install the APK and open **Bold Reminders**.
2. Allow calendar access, then open Settings (gear icon) and tick the calendars that should ring.
3. Fix any orange items in the setup checklist (notifications, exact alarms, full-screen alarm, battery).
4. On Samsung: Settings → Battery → Background usage limits → add Bold Reminders to **Never sleeping apps**.
