package com.planner.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class NotificationService extends Service {

    private static final String TAG = "PlannerNotif";

    public static final String CHANNEL_ID = "planner_tasks";
    public static final int NOTIFICATION_ID = 1001;

    public static final String PREFS = "planner_prefs";
    public static final String KEY_TASKS_JSON = "notif_tasks_json";
    public static final String KEY_ENABLED = "notif_enabled";
    public static final String KEY_MAX_TASKS = "notif_max_tasks";
    // i18n: подписи приходят из JS (index.html) — натив их только рисует.
    // Так перевод правится без пересборки APK. Если не пришли — русские по умолчанию.
    public static final String KEY_L_TITLE = "notif_l_title";
    public static final String KEY_L_EMPTY = "notif_l_empty";
    public static final String KEY_L_MORE  = "notif_l_more";

    // vC 11: полный список строк с окнами видимости + дата, на которую он собран.
    // Фильтрация по времени переехала сюда из JS — шторка обновляется и при
    // закрытом приложении (см. tick-ресивер и AlarmManager ниже).
    public static final String KEY_ROWS_JSON = "notif_rows_json";
    public static final String KEY_ROWS_DATE = "notif_rows_date";

    public static final String ACTION_START = "com.planner.app.NOTIF_START";
    public static final String ACTION_UPDATE = "com.planner.app.NOTIF_UPDATE";
    public static final String ACTION_STOP = "com.planner.app.NOTIF_STOP";
    public static final String ACTION_ALARM = "com.planner.app.NOTIF_ALARM";

    private static final int MAX_ROWS = 5;

    // id строк в layout — фиксированный список, RemoteViews не умеет динамику
    private static final int[] ROW_IDS = {
            R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4
    };
    private static final int[] ROW_TIME_IDS = {
            R.id.row_0_time, R.id.row_1_time, R.id.row_2_time,
            R.id.row_3_time, R.id.row_4_time
    };
    private static final int[] ROW_TITLE_IDS = {
            R.id.row_0_title, R.id.row_1_title, R.id.row_2_title,
            R.id.row_3_title, R.id.row_4_title
    };

    /** Минутный тик + включение экрана + сдвиг часов/даты. Регистрируется в
     *  onCreate, живёт пока жив сервис. ACTION_TIME_TICK нельзя объявить в
     *  манифесте — только runtime-регистрация. Приходит раз в минуту, пока
     *  экран включён, то есть ровно тогда, когда шторку могут открыть. */
    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            refresh(ctx);
        }
    };
    private boolean tickRegistered = false;

    /** Сохранить задачи из JS и обновить уведомление (если включено).
     *  Форматы, которые принимаем:
     *    vC 11+: {"tasks":[...],"tasksAll":[{"time","title","from","until"}],
     *             "date":"YYYY-MM-DD","labels":{...}}  — фильтрует натив
     *    vC  7+: {"tasks":[...],"labels":{...}}        — фильтрует JS
     *    старый: голый массив [...]                     — фильтрует JS, русские подписи
     *  Старые форматы оставлены: index.html на GitHub Pages обновляется мгновенно
     *  и у части пользователей остаётся APK предыдущей версии — и наоборот. */
    public static void updateTasks(Context ctx, String json) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tasksJson = json;
        String rowsJson = null, rowsDate = null;
        String lTitle = null, lEmpty = null, lMore = null;
        try {
            String trimmed = json == null ? "" : json.trim();
            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONArray arr = root.optJSONArray("tasks");
                tasksJson = arr != null ? arr.toString() : "[]";
                JSONArray all = root.optJSONArray("tasksAll");
                if (all != null) {
                    rowsJson = all.toString();
                    rowsDate = root.optString("date", "");
                }
                JSONObject lb = root.optJSONObject("labels");
                if (lb != null) {
                    lTitle = lb.optString("title", null);
                    lEmpty = lb.optString("empty", null);
                    lMore  = lb.optString("more",  null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateTasks parse failed: " + e.getMessage());
            tasksJson = json;
        }
        SharedPreferences.Editor ed = sp.edit();
        ed.putString(KEY_TASKS_JSON, tasksJson);
        if (rowsJson != null) {
            ed.putString(KEY_ROWS_JSON, rowsJson);
            ed.putString(KEY_ROWS_DATE, rowsDate == null ? "" : rowsDate);
        } else {
            // пришёл старый формат — снимаем нативный список, иначе он
            // будет висеть вечно и перебивать то, что прислал JS
            ed.remove(KEY_ROWS_JSON);
            ed.remove(KEY_ROWS_DATE);
        }
        if (lTitle != null && !lTitle.isEmpty()) ed.putString(KEY_L_TITLE, lTitle);
        if (lEmpty != null && !lEmpty.isEmpty()) ed.putString(KEY_L_EMPTY, lEmpty);
        if (lMore  != null && !lMore.isEmpty())  ed.putString(KEY_L_MORE,  lMore);
        ed.apply();

        lastSig = null;   // список пришёл извне — перерисовать безусловно

        if (!sp.getBoolean(KEY_ENABLED, false)) return;

        Intent i = new Intent(ctx, NotificationService.class);
        i.setAction(ACTION_UPDATE);
        startSvc(ctx, i);
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, NotificationService.class);
        i.setAction(ACTION_START);
        startSvc(ctx, i);
    }

    public static void stop(Context ctx) {
        cancelAlarm(ctx);
        Intent i = new Intent(ctx, NotificationService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception e) {
            Log.e(TAG, "stop failed: " + e.getMessage());
            // если сервис не запущен — просто снимаем уведомление
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        }
    }

    private static void startSvc(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "startSvc failed: " + e.getMessage());
        }
    }

    /** Перерисовать уведомление НЕ запуская сервис. Зовётся из tick-ресивера и
     *  из NotifAlarmReceiver. Обновление notify() по тому же id подхватывает
     *  уже висящее уведомление foreground-сервиса; запускать сервис из фона
     *  нельзя (Android 12+), поэтому здесь только notify + перевод будильника. */
    public static void refresh(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(KEY_ENABLED, false)) return;
            scheduleNext(ctx);
            // тик приходит раз в минуту — перерисовываем только когда список
            // реально изменился, иначе на MIUI уведомление дёргается впустую
            String sig = signature(sp);
            if (sig.equals(lastSig)) return;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFICATION_ID, buildNotification(ctx));
            lastSig = sig;
        } catch (Exception e) {
            Log.e(TAG, "refresh failed: " + e.getMessage());
        }
    }

    /** Слепок того, что сейчас видно в шторке — чтобы не перерисовывать зря. */
    private static String lastSig = null;

    private static String signature(SharedPreferences sp) {
        StringBuilder sb = new StringBuilder();
        sb.append(sp.getInt(KEY_MAX_TASKS, MAX_ROWS)).append('|')
          .append(sp.getString(KEY_L_TITLE, "")).append('|')
          .append(sp.getString(KEY_L_EMPTY, "")).append('|')
          .append(sp.getString(KEY_L_MORE, "")).append('|');
        for (JSONObject r : visibleRows(sp)) {
            sb.append(r.optString("time", "")).append('\u0001')
              .append(r.optString("title", "")).append('\u0002');
        }
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        registerTick();
    }

    @Override
    public void onDestroy() {
        if (tickRegistered) {
            try { unregisterReceiver(tickReceiver); } catch (Exception ignored) {}
            tickRegistered = false;
        }
        super.onDestroy();
    }

    private void registerTick() {
        if (tickRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);       // раз в минуту при включённом экране
            f.addAction(Intent.ACTION_SCREEN_ON);       // мгновенно при разблокировке
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            registerReceiver(tickReceiver, f);
            tickRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "registerTick failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            cancelAlarm(this);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        registerTick();

        // Android требует startForeground в течение 5 сек после старта — делаем всегда первым
        Notification n = buildNotification(this);
        try {
            startForeground(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE.equals(action) || ACTION_ALARM.equals(action)) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        }
        lastSig = signature(getSharedPreferences(PREFS, Context.MODE_PRIVATE));

        scheduleNext(this);
        return START_STICKY;
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "stopForeground failed: " + e.getMessage());
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Задачи на сегодня",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Постоянное уведомление со списком задач");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    // ───────── время ─────────

    private static String nowHHMM() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static String todayIso() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** "HH:MM" сегодняшнего дня в миллисекундах. */
    private static long millisToday(String hhmm) {
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long millisNextMidnight() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 5);        // +5 сек, чтобы дата уже точно сменилась
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ───────── будильник ─────────

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, NotifAlarmReceiver.class);
        i.setAction(ACTION_ALARM);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 2001, i, flags);
    }

    private static void cancelAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx));
        } catch (Exception e) {
            Log.e(TAG, "cancelAlarm failed: " + e.getMessage());
        }
    }

    /** Поставить будильник на ближайший момент, когда список меняется:
     *  наступление задачи, скрытие строки цели или полночь.
     *  Намеренно НЕточный (setAndAllowWhileIdle): точные будильники на API 31+
     *  требуют SCHEDULE_EXACT_ALARM и объяснения в Play. Для планировщика
     *  разброс в пару минут некритичен, а при включённом экране всё равно
     *  работает минутный ACTION_TIME_TICK. */
    private static void scheduleNext(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(KEY_ENABLED, false)) return;

            long now = System.currentTimeMillis();
            long when = millisNextMidnight();

            String rowsJson = sp.getString(KEY_ROWS_JSON, "");
            if (!rowsJson.isEmpty() && todayIso().equals(sp.getString(KEY_ROWS_DATE, ""))) {
                JSONArray rows = parseArray(rowsJson);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    for (String key : new String[]{"from", "until"}) {
                        String v = r.optString(key, "");
                        if (v.length() < 5) continue;
                        long ms = millisToday(v);
                        if (ms > now && ms < when) when = ms;
                    }
                }
            }

            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = alarmIntent(ctx);
            am.cancel(pi);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Exception e) {
            Log.e(TAG, "scheduleNext failed: " + e.getMessage());
        }
    }

    // ───────── отрисовка ─────────

    /** Строки к показу прямо сейчас.
     *  Новый формат (tasksAll): окно видимости [from, until) сравнивается с
     *  текущим временем — пустое поле значит «без границы». Список,
     *  собранный на другую дату, не показываем вообще: приложение закрыто
     *  со вчера, вчерашние задачи в шторке не нужны.
     *  Старый формат (tasks): рисуем как пришло, фильтр был на стороне JS. */
    private static List<JSONObject> visibleRows(SharedPreferences sp) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String rowsJson = sp.getString(KEY_ROWS_JSON, "");

        if (!rowsJson.isEmpty()) {
            String date = sp.getString(KEY_ROWS_DATE, "");
            if (!date.isEmpty() && !date.equals(todayIso())) return out;   // день сменился
            String now = nowHHMM();
            JSONArray rows = parseArray(rowsJson);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String from  = r.optString("from",  "");
                String until = r.optString("until", "");
                if (from.length() == 5 && now.compareTo(from) < 0) continue;    // ещё не наступило
                if (until.length() == 5 && now.compareTo(until) >= 0) continue; // уже перекрыто
                out.add(r);
            }
            return out;
        }

        JSONArray legacy = parseArray(sp.getString(KEY_TASKS_JSON, ""));
        for (int i = 0; i < legacy.length(); i++) {
            JSONObject r = legacy.optJSONObject(i);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static Notification buildNotification(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int maxTasks = sp.getInt(KEY_MAX_TASKS, MAX_ROWS);
        if (maxTasks < 1) maxTasks = 1;
        if (maxTasks > MAX_ROWS) maxTasks = MAX_ROWS;

        // i18n-подписи от JS (или русские по умолчанию)
        String lTitle = sp.getString(KEY_L_TITLE, "Задачи на сегодня");
        String lEmpty = sp.getString(KEY_L_EMPTY, "Задач нет");
        String lMore  = sp.getString(KEY_L_MORE,  "и ещё");

        List<JSONObject> tasks = visibleRows(sp);
        int total = tasks.size();
        int shown = Math.min(total, maxTasks);

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.notification_tasks);

        if (total == 0) {
            rv.setTextViewText(R.id.notif_title, lTitle);
            rv.setTextViewText(R.id.notif_subtitle, lEmpty);
            rv.setViewVisibility(R.id.notif_subtitle, View.VISIBLE);
        } else {
            rv.setTextViewText(R.id.notif_title, lTitle + " (" + total + ")");
            if (total > shown) {
                rv.setTextViewText(R.id.notif_subtitle, lMore + " " + (total - shown));
                rv.setViewVisibility(R.id.notif_subtitle, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.notif_subtitle, View.GONE);
            }
        }

        for (int i = 0; i < MAX_ROWS; i++) {
            if (i < shown) {
                JSONObject t = tasks.get(i);
                String time = t != null ? t.optString("time", "") : "";
                String title = t != null ? t.optString("title", "") : "";
                if (title.isEmpty()) title = "—";

                rv.setViewVisibility(ROW_IDS[i], View.VISIBLE);
                if (time.isEmpty()) {
                    rv.setViewVisibility(ROW_TIME_IDS[i], View.INVISIBLE);
                } else {
                    rv.setViewVisibility(ROW_TIME_IDS[i], View.VISIBLE);
                    rv.setTextViewText(ROW_TIME_IDS[i], time);
                }
                rv.setTextViewText(ROW_TITLE_IDS[i], title);
            } else {
                rv.setViewVisibility(ROW_IDS[i], View.GONE);
            }
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
            b.setPriority(Notification.PRIORITY_LOW);
        }

        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(lTitle)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setCustomContentView(rv);
            b.setCustomBigContentView(rv);
        } else {
            b.setContent(rv);
        }

        Notification n = b.build();
        n.bigContentView = rv;
        return n;
    }

    private static JSONArray parseArray(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "parseArray failed: " + e.getMessage());
            return new JSONArray();
        }
    }
}
