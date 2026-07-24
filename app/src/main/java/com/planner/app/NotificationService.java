package com.planner.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

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

    public static final String ACTION_START = "com.planner.app.NOTIF_START";
    public static final String ACTION_UPDATE = "com.planner.app.NOTIF_UPDATE";
    public static final String ACTION_STOP = "com.planner.app.NOTIF_STOP";

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

    /** Сохранить задачи из JS и обновить уведомление (если включено).
     *  Принимает либо новый формат {"tasks":[...],"labels":{"title":"…","empty":"…","more":"…"}},
     *  либо старый голый массив [...] (обратная совместимость со сборками до i18n). */
    public static void updateTasks(Context ctx, String json) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tasksJson = json;
        String lTitle = null, lEmpty = null, lMore = null;
        try {
            String trimmed = json == null ? "" : json.trim();
            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONArray arr = root.optJSONArray("tasks");
                tasksJson = arr != null ? arr.toString() : "[]";
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
        if (lTitle != null && !lTitle.isEmpty()) ed.putString(KEY_L_TITLE, lTitle);
        if (lEmpty != null && !lEmpty.isEmpty()) ed.putString(KEY_L_EMPTY, lEmpty);
        if (lMore  != null && !lMore.isEmpty())  ed.putString(KEY_L_MORE,  lMore);
        ed.apply();

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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android требует startForeground в течение 5 сек после старта — делаем всегда первым
        Notification n = buildNotification();
        try {
            startForeground(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE.equals(action)) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        }

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

    private Notification buildNotification() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_TASKS_JSON, "");
        int maxTasks = sp.getInt(KEY_MAX_TASKS, MAX_ROWS);
        if (maxTasks < 1) maxTasks = 1;
        if (maxTasks > MAX_ROWS) maxTasks = MAX_ROWS;

        // i18n-подписи от JS (или русские по умолчанию)
        String lTitle = sp.getString(KEY_L_TITLE, "Задачи на сегодня");
        String lEmpty = sp.getString(KEY_L_EMPTY, "Задач нет");
        String lMore  = sp.getString(KEY_L_MORE,  "и ещё");

        JSONArray tasks = parseTasks(json);
        int total = tasks.length();
        int shown = Math.min(total, maxTasks);

        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notification_tasks);

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
                JSONObject t = tasks.optJSONObject(i);
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

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
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

    private JSONArray parseTasks(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "parseTasks failed: " + e.getMessage());
            return new JSONArray();
        }
    }
}
