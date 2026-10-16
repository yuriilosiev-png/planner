package com.planner.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 1.7.7: «Готово» из шторки и из виджета «Сегодня».
 *
 * Данные приложения живут в WebView (localStorage), натив их не видит. Поэтому
 * отметка идёт в два шага:
 *   1. здесь, сразу: задача убирается из шторки и виджета, а её id кладётся в
 *      очередь (SharedPreferences, ключ KEY_PENDING);
 *   2. страница забирает очередь через AndroidBridge.takePendingDone() и ставит
 *      задаче done — при открытии приложения, при возврате в него или сразу,
 *      если приложение открыто (MainActivity.onNativeDone()).
 * Так отметка не теряется, даже если приложение закрыто, и попадает в историю и
 * график дисциплины с настоящим временем нажатия.
 *
 * Отмечаются только простые задачи и шаги числовых целей (JS присылает tap=true).
 * Чек-листам и задачам со статусом нужен выбор пунктов/статуса — их кнопки нет.
 * exported=false: сюда шлём только мы сами через PendingIntent.
 */
public class DoneReceiver extends BroadcastReceiver {

    private static final String TAG = "PlannerDone";

    public static final String ACTION_DONE = "com.planner.app.TASK_DONE";
    public static final String EXTRA_ID = "task_id";
    /** Очередь отметок, ещё не забранных страницей: [{"id":"…","at":1739…}] */
    public static final String KEY_PENDING = "pending_done_json";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION_DONE.equals(intent.getAction())) return;
        String id = intent.getStringExtra(EXTRA_ID);
        if ((id == null || id.isEmpty()) && intent.getData() != null) {
            id = intent.getData().getLastPathSegment();
        }
        if (id == null || id.isEmpty()) return;
        markDone(ctx, id);
    }

    /** PendingIntent для кнопки в строке шторки. data уникальна на задачу —
     *  иначе Android склеит интенты с одинаковым requestCode и подменит extras. */
    public static PendingIntent pendingFor(Context ctx, String id, int requestCode) {
        Intent i = new Intent(ctx, DoneReceiver.class);
        i.setAction(ACTION_DONE);
        i.setData(Uri.parse("planner://done/" + Uri.encode(id)));
        i.putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, requestCode, i, flags);
    }

    /** Шаблон для строк списка виджета. Строка добавляет свой id через
     *  setOnClickFillInIntent — для этого шаблон обязан быть MUTABLE (Android 12+). */
    public static PendingIntent template(Context ctx) {
        Intent i = new Intent(ctx, DoneReceiver.class);
        i.setAction(ACTION_DONE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 3200, i, flags);
    }

    static synchronized void markDone(Context ctx, String id) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();

            // 1. очередь для страницы (без дублей)
            JSONArray q = parse(sp.getString(KEY_PENDING, "[]"));
            boolean queued = false;
            for (int i = 0; i < q.length(); i++) {
                JSONObject o = q.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) { queued = true; break; }
            }
            if (!queued) {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("at", System.currentTimeMillis());
                q.put(o);
                ed.putString(KEY_PENDING, q.toString());
            }

            // 2. убрать строку из шторки
            JSONArray rows = parse(sp.getString(NotificationService.KEY_ROWS_JSON, ""));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null && id.equals(r.optString("id"))) continue;
                if (r != null) keep.put(r);
            }
            if (keep.length() != rows.length()) {
                ed.putString(NotificationService.KEY_ROWS_JSON, keep.toString());
            }

            // 3. убрать строку из виджета и прибавить «выполнено»
            String wj = sp.getString(TodayWidget.KEY_WIDGET_JSON, "");
            if (!wj.isEmpty()) {
                JSONObject w = new JSONObject(wj);
                JSONArray wr = w.optJSONArray("rows");
                if (wr != null) {
                    JSONArray wk = new JSONArray();
                    boolean removed = false;
                    for (int i = 0; i < wr.length(); i++) {
                        JSONObject r = wr.optJSONObject(i);
                        if (r != null && id.equals(r.optString("id"))) { removed = true; continue; }
                        if (r != null) wk.put(r);
                    }
                    if (removed) {
                        w.put("rows", wk);
                        w.put("done", w.optInt("done", 0) + 1);
                        ed.putString(TodayWidget.KEY_WIDGET_JSON, w.toString());
                    }
                }
            }
            ed.apply();
        } catch (Exception e) {
            Log.e(TAG, "markDone failed: " + e.getMessage());
        }
        NotificationService.redraw(ctx);
        TodayWidget.updateAll(ctx);
        MainActivity.onNativeDone();
    }

    /** Забрать очередь (страница применяет отметки) и очистить её. */
    static synchronized String takePending(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_PENDING, "[]");
        sp.edit().remove(KEY_PENDING).apply();
        return s == null || s.isEmpty() ? "[]" : s;
    }

    static JSONArray parse(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
