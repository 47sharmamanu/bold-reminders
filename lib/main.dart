import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const BoldRemindersApp());

class BoldRemindersApp extends StatelessWidget {
  const BoldRemindersApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Bold Reminders',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.system,
      theme: ThemeData(colorSchemeSeed: const Color(0xFFFFB300), useMaterial3: true),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFFFFB300),
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

// ---------------------------------------------------------------------------
// Bridge to the native Android layer (Kotlin)
// ---------------------------------------------------------------------------

class Bridge {
  static const _ch = MethodChannel('bold_reminders/alarm');

  static Future<Map<String, dynamic>> status() async =>
      Map<String, dynamic>.from(await _ch.invokeMethod('status') as Map);

  static Future<List<Map<String, dynamic>>> _list(String method) async {
    final raw = await _ch.invokeMethod(method) as List;
    return raw.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  static Future<List<Map<String, dynamic>>> alarms() => _list('alarms');
  static Future<List<Map<String, dynamic>>> log() => _list('log');
  static Future<List<Map<String, dynamic>>> calendars() => _list('calendars');

  static Future<Map<String, dynamic>> sync(String trigger) async =>
      Map<String, dynamic>.from(await _ch.invokeMethod('sync', {'trigger': trigger}) as Map);

  static Future<void> setCalendars(List<int> ids) => _ch.invokeMethod('setCalendars', {'ids': ids});

  static Future<bool> scheduleTest(int minutes) async =>
      (await _ch.invokeMethod('scheduleTest', {'minutes': minutes})) == true;

  static Future<void> cancelTest() => _ch.invokeMethod('cancelTest');
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
  static Future<void> call(String method) => _ch.invokeMethod(method);
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

String _two(int n) => n.toString().padLeft(2, '0');

String fmtTime(int? millis) {
  if (millis == null) return '—';
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  return '${_two(d.hour)}:${_two(d.minute)}';
}

String fmtDayTime(int? millis) {
  if (millis == null) return '';
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  return '${_two(d.day)}/${_two(d.month)} ${_two(d.hour)}:${_two(d.minute)}';
}

const _weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

String dayLabel(int millis) {
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(d.year, d.month, d.day);
  final diff = day.difference(today).inDays;
  final date = '${_weekdays[d.weekday - 1]} ${d.day} ${_months[d.month - 1]}';
  if (diff == 0) return 'Today · $date';
  if (diff == 1) return 'Tomorrow · $date';
  return date;
}

String ago(int? millis) {
  if (millis == null) return 'never';
  final mins = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(millis)).inMinutes;
  if (mins < 1) return 'just now';
  if (mins < 60) return '$mins min ago';
  return 'at ${fmtTime(millis)}';
}

// ---------------------------------------------------------------------------
// Home: the actual alarms
// ---------------------------------------------------------------------------

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  Map<String, dynamic> _status = {};
  List<Map<String, dynamic>> _alarms = [];
  bool _loaded = false;
  bool _syncing = false;
  String? _error;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sync('app open');
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => _load());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _sync('app open');
  }

  Future<void> _load() async {
    try {
      final s = await Bridge.status();
      final a = await Bridge.alarms();
      if (!mounted) return;
      setState(() {
        _status = s;
        _alarms = a;
        _loaded = true;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  Future<void> _sync(String trigger) async {
    if (_syncing) return;
    setState(() => _syncing = true);
    try {
      await Bridge.sync(trigger);
    } catch (_) {}
    await _load();
    if (mounted) setState(() => _syncing = false);
  }

  Future<void> _openSettings() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettingsPage()));
    _sync('settings closed');
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final calendarAllowed = _status['calendar'] == true;
    final selected = (_status['selectedCalendars'] as int?) ?? 0;
    final setupProblems = [
      _status['notifications'],
      _status['exactAlarm'],
      _status['fullScreen'],
    ].where((v) => v == false).length;

    // Group alarms by day.
    final groups = <String, List<Map<String, dynamic>>>{};
    for (final a in _alarms) {
      groups.putIfAbsent(dayLabel(a['at'] as int), () => []).add(a);
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Bold Reminders'),
        actions: [
          IconButton(
            onPressed: _syncing ? null : () => _sync('manual'),
            icon: _syncing
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.refresh),
            tooltip: 'Sync now',
          ),
          IconButton(onPressed: _openSettings, icon: const Icon(Icons.settings_outlined), tooltip: 'Settings'),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => _sync('manual'),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 0, 4, 12),
              child: Text(
                calendarAllowed
                    ? 'Synced ${ago(_status['lastSyncAt'] as int?)} · next 48 hours · $selected calendar${selected == 1 ? '' : 's'}'
                    : 'Not synced',
                style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ),
            if (_error != null) _banner(context, Icons.error_outline, 'Error: $_error', null, null),
            if (_status['ringing'] == true)
              _banner(context, Icons.alarm_on, 'An alarm is ringing now', null, null, strong: true),
            if (_loaded && !calendarAllowed)
              _banner(context, Icons.event_busy, 'Allow calendar access so your events can ring.', 'Allow',
                  () async {
                await Bridge.call('requestCalendar');
                await Future.delayed(const Duration(seconds: 2));
                _sync('first run');
              }),
            if (_loaded && calendarAllowed && selected == 0)
              _banner(context, Icons.calendar_month, 'No calendars chosen to ring.', 'Choose', _openSettings),
            if (_loaded && setupProblems > 0)
              _banner(context, Icons.warning_amber,
                  '$setupProblems setup item${setupProblems == 1 ? '' : 's'} need attention, or alarms may not ring properly.',
                  'Fix', _openSettings),
            if (_loaded && _alarms.isEmpty && calendarAllowed && selected > 0)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 48),
                child: Column(children: [
                  Icon(Icons.alarm_off, size: 48, color: scheme.onSurfaceVariant),
                  const SizedBox(height: 12),
                  Text('No alarms in the next 48 hours', style: text.titleMedium),
                  const SizedBox(height: 4),
                  Text('Timed events on your chosen calendars will appear here.',
                      textAlign: TextAlign.center, style: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
                ]),
              ),
            for (final entry in groups.entries) ...[
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
                child: Text(entry.key, style: text.titleSmall?.copyWith(color: scheme.primary)),
              ),
              ...entry.value.map((a) => _alarmCard(context, a)),
            ],
          ],
        ),
      ),
    );
  }

  Widget _alarmCard(BuildContext context, Map<String, dynamic> a) {
    final text = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    final titles = (a['title'] as String? ?? '').split(' + ');
    final isTest = a['test'] == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 76,
            child: Text(fmtTime(a['at'] as int?),
                style: text.headlineSmall?.copyWith(fontWeight: FontWeight.w600)),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              for (final t in titles) Text(t, style: text.titleMedium),
              const SizedBox(height: 2),
              Text(
                [if (titles.length > 1) '${titles.length} events, one alarm', a['detail'] as String? ?? '']
                    .where((s) => s.isNotEmpty)
                    .join(' · '),
                style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ]),
          ),
          if (isTest)
            IconButton(
              tooltip: 'Cancel test alarm',
              onPressed: () async {
                await Bridge.cancelTest();
                await _load();
              },
              icon: const Icon(Icons.close),
            )
          else
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Icon(Icons.alarm, color: scheme.primary),
            ),
        ]),
      ),
    );
  }

  Widget _banner(BuildContext context, IconData icon, String message, String? action, VoidCallback? onTap,
      {bool strong = false}) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: strong ? scheme.primaryContainer : scheme.secondaryContainer,
      child: ListTile(
        leading: Icon(icon),
        title: Text(message),
        trailing: action == null ? null : FilledButton.tonal(onPressed: onTap, child: Text(action)),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Settings: calendars, setup checklist, test alarm, log
// ---------------------------------------------------------------------------

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> with WidgetsBindingObserver {
  Map<String, dynamic> _status = {};
  List<Map<String, dynamic>> _calendars = [];
  List<Map<String, dynamic>> _log = [];
  Set<int> _selected = {};
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load(withCalendars: true);
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => _load());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load(withCalendars: true);
  }

  Future<void> _load({bool withCalendars = false}) async {
    try {
      final s = await Bridge.status();
      final l = await Bridge.log();
      List<Map<String, dynamic>>? cals;
      if (withCalendars) cals = await Bridge.calendars();
      if (!mounted) return;
      setState(() {
        _status = s;
        _log = l;
        if (cals != null) {
          _calendars = cals;
          _selected = cals.where((c) => c['selected'] == true).map((c) => (c['id'] as num).toInt()).toSet();
        }
      });
    } catch (_) {}
  }

  Future<void> _toggle(int id, bool on) async {
    setState(() => on ? _selected.add(id) : _selected.remove(id));
    await Bridge.setCalendars(_selected.toList());
    await Bridge.sync('calendars changed');
    await _load();
  }

  Future<void> _scheduleTest(int minutes) async {
    final ok = await Bridge.scheduleTest(minutes);
    await _load();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(ok
          ? 'Test alarm set for $minutes min from now. Lock the phone to see the full screen.'
          : 'Could not set the alarm: allow exact alarms above.'),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    final calendarAllowed = _status['calendar'] == true;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
        children: [
          _section(context, 'Calendars that ring', 'Timed events on these calendars ring at their start time.'),
          if (!calendarAllowed)
            Card(
              child: ListTile(
                leading: const Icon(Icons.event_busy),
                title: const Text('Calendar access needed'),
                trailing: FilledButton.tonal(
                  onPressed: () async {
                    await Bridge.call('requestCalendar');
                    await Future.delayed(const Duration(seconds: 2));
                    _load(withCalendars: true);
                  },
                  child: const Text('Allow'),
                ),
              ),
            )
          else if (_calendars.isEmpty)
            const Padding(
              padding: EdgeInsets.all(8),
              child: Text('No calendars found on this phone.'),
            )
          else
            Card(
              child: Column(children: [
                for (final c in _calendars)
                  CheckboxListTile(
                    value: _selected.contains((c['id'] as num).toInt()),
                    onChanged: (v) => _toggle((c['id'] as num).toInt(), v == true),
                    secondary: CircleAvatar(
                      radius: 7,
                      backgroundColor: Color((c['color'] as num?)?.toInt() ?? 0xFF888888).withAlpha(255),
                    ),
                    title: Text(c['name'] as String? ?? ''),
                    subtitle: Text(c['account'] as String? ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
                    dense: true,
                  ),
              ]),
            ),
          _section(context, 'Setup checklist', null),
          _check('Calendar access', _status['calendar'], 'requestCalendar', 'Needed to read your events.'),
          _check('Notifications allowed', _status['notifications'], 'requestNotifications',
              'Needed to show the alarm.'),
          _check('Exact alarms allowed', _status['exactAlarm'], 'openExactAlarmSettings',
              'Needed to ring at the exact minute.'),
          _check('Full-screen alarm allowed', _status['fullScreen'], 'openFullScreenSettings',
              'Needed to take over the lock screen.'),
          _check('Battery: not optimised', _status['battery'], 'openBatterySettings',
              'Recommended. On Samsung also add the app to "Never sleeping apps".'),
          _section(context, 'Test alarm', 'Rings once so you can check sound and full screen.'),
          Row(children: [
            Expanded(
              child: FilledButton.icon(
                onPressed: () => _scheduleTest(2),
                icon: const Icon(Icons.alarm_add),
                label: const Text('Test alarm in 2 min'),
              ),
            ),
            const SizedBox(width: 8),
            OutlinedButton(onPressed: () => _scheduleTest(1), child: const Text('1 min')),
          ]),
          Row(children: [
            Expanded(child: _section(context, 'Log', 'Syncs, alarms rung, dismissed, missed and skipped.')),
            if (_log.isNotEmpty)
              TextButton(
                onPressed: () async {
                  await Bridge.clearLog();
                  await _load();
                },
                child: const Text('Clear'),
              ),
          ]),
          if (_log.isEmpty) const Padding(padding: EdgeInsets.all(8), child: Text('No entries yet.')),
          ..._log.take(100).map((e) => ListTile(
                dense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                leading: Icon(_kindIcon(e['kind'] as String?), color: _kindColor(e['kind'] as String?, scheme)),
                title: Text(e['message'] as String? ?? ''),
                subtitle: Text(fmtDayTime(e['at'] as int?), style: text.bodySmall),
              )),
        ],
      ),
    );
  }

  Widget _section(BuildContext context, String title, String? sub) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 20, 4, 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: text.titleMedium),
        if (sub != null)
          Text(sub, style: text.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ]),
    );
  }

  Widget _check(String label, Object? ok, String fixMethod, String why) {
    final good = ok == true;
    return Card(
      child: ListTile(
        leading: Icon(good ? Icons.check_circle : Icons.error_outline, color: good ? Colors.green : Colors.orange),
        title: Text(label),
        subtitle: Text(why),
        trailing: good
            ? null
            : TextButton(
                onPressed: () async {
                  await Bridge.call(fixMethod);
                  await Future.delayed(const Duration(seconds: 2));
                  _load(withCalendars: true);
                },
                child: const Text('Fix'),
              ),
      ),
    );
  }

  IconData _kindIcon(String? kind) {
    switch (kind) {
      case 'fired':
        return Icons.alarm_on;
      case 'dismissed':
        return Icons.check;
      case 'missed':
        return Icons.alarm_off;
      case 'skipped':
        return Icons.skip_next;
      case 'error':
        return Icons.warning_amber;
      default:
        return Icons.sync;
    }
  }

  Color _kindColor(String? kind, ColorScheme s) {
    switch (kind) {
      case 'missed':
      case 'skipped':
        return Colors.orange;
      case 'error':
        return s.error;
      case 'dismissed':
        return Colors.green;
      default:
        return s.primary;
    }
  }
}
