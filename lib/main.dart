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

/// Talks to the native Android alarm layer (Kotlin).
class AlarmBridge {
  static const _ch = MethodChannel('bold_reminders/alarm');

  static Future<Map<String, dynamic>> status() async =>
      Map<String, dynamic>.from(await _ch.invokeMethod('status') as Map);

  static Future<List<Map<String, dynamic>>> log() async {
    final raw = await _ch.invokeMethod('log') as List;
    return raw.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  static Future<bool> scheduleTest(int minutes) async =>
      (await _ch.invokeMethod('scheduleTest', {'minutes': minutes})) == true;

  static Future<void> cancelTest() => _ch.invokeMethod('cancelTest');
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
  static Future<void> call(String method) => _ch.invokeMethod(method);
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  Map<String, dynamic> _status = {};
  List<Map<String, dynamic>> _log = [];
  String? _error;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final s = await AlarmBridge.status();
      final l = await AlarmBridge.log();
      if (!mounted) return;
      setState(() {
        _status = s;
        _log = l;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  Future<void> _scheduleTest(int minutes) async {
    final ok = await AlarmBridge.scheduleTest(minutes);
    await _refresh();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(ok
          ? 'Test alarm set for ${_fmt(_status['nextAlarmAt'] as int?)}. Lock the phone to see the full screen.'
          : 'Could not set the alarm: allow exact alarms below.'),
    ));
  }

  static String _two(int n) => n.toString().padLeft(2, '0');

  static String _fmt(int? millis) {
    if (millis == null) return '—';
    final d = DateTime.fromMillisecondsSinceEpoch(millis);
    return '${_two(d.hour)}:${_two(d.minute)}';
  }

  static String _fmtFull(int? millis) {
    if (millis == null) return '';
    final d = DateTime.fromMillisecondsSinceEpoch(millis);
    return '${_two(d.day)}/${_two(d.month)} ${_two(d.hour)}:${_two(d.minute)}';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final nextAt = _status['nextAlarmAt'] as int?;
    final nextTitle = _status['nextAlarmTitle'] as String?;
    final ringing = _status['ringing'] == true;

    return Scaffold(
      appBar: AppBar(title: const Text('Bold Reminders'), actions: [
        IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh), tooltip: 'Refresh'),
      ]),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (_error != null)
              Card(
                color: scheme.errorContainer,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text('Error talking to the alarm layer: $_error'),
                ),
              ),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(ringing ? 'Ringing now' : 'Next alarm',
                      style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(
                    nextAt == null ? 'Nothing scheduled' : '${_fmt(nextAt)} · ${nextTitle ?? ''}',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 16),
                  Row(children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: () => _scheduleTest(2),
                        icon: const Icon(Icons.alarm_add),
                        label: const Text('Test alarm in 2 min'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton(
                      onPressed: () => _scheduleTest(1),
                      child: const Text('1 min'),
                    ),
                  ]),
                  if (nextAt != null)
                    TextButton(
                      onPressed: () async {
                        await AlarmBridge.cancelTest();
                        await _refresh();
                      },
                      child: const Text('Cancel test alarm'),
                    ),
                ]),
              ),
            ),
            const SizedBox(height: 8),
            _sectionTitle(context, 'Setup checklist'),
            _check('Notifications allowed', _status['notifications'], 'requestNotifications',
                'Needed to show the alarm.'),
            _check('Exact alarms allowed', _status['exactAlarm'], 'openExactAlarmSettings',
                'Needed to ring at the exact minute.'),
            _check('Full-screen alarm allowed', _status['fullScreen'], 'openFullScreenSettings',
                'Needed to take over the lock screen.'),
            _check('Battery: not optimised', _status['battery'], 'openBatterySettings',
                'Recommended. On Samsung also add the app to "Never sleeping apps".'),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: _sectionTitle(context, 'Log')),
              if (_log.isNotEmpty)
                TextButton(
                  onPressed: () async {
                    await AlarmBridge.clearLog();
                    await _refresh();
                  },
                  child: const Text('Clear'),
                ),
            ]),
            if (_log.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: Text('No entries yet. Set a test alarm to begin.'),
              ),
            ..._log.map((e) => ListTile(
                  dense: true,
                  leading: Icon(_kindIcon(e['kind'] as String?), color: _kindColor(e['kind'] as String?, scheme)),
                  title: Text(e['message'] as String? ?? ''),
                  subtitle: Text(_fmtFull(e['at'] as int?)),
                )),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String text) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 8, 4, 4),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );

  Widget _check(String label, Object? ok, String fixMethod, String why) {
    final good = ok == true;
    return Card(
      child: ListTile(
        leading: Icon(good ? Icons.check_circle : Icons.error_outline,
            color: good ? Colors.green : Colors.orange),
        title: Text(label),
        subtitle: Text(why),
        trailing: good
            ? null
            : TextButton(
                onPressed: () async {
                  await AlarmBridge.call(fixMethod);
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
      case 'error':
        return Icons.warning_amber;
      default:
        return Icons.sync;
    }
  }

  Color _kindColor(String? kind, ColorScheme s) {
    switch (kind) {
      case 'missed':
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
