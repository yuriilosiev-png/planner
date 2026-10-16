package com.planner.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 1.7.7: виджет «Сегодня» на главном экране.
 *
 * Шапка: «Сегодня» + «3 из 5» (тап — открыть приложение). Ниже список
 * невыполненных задач дня по времени; у простых задач кнопка ☐ — отметить
 * (DoneReceiver), у чек-листов и задач со статусом кнопки нет.
 *
 * Данные — снимок, который страница присылает вместе со списком для шторки
 * (поле widget в updateNotificationTasks, сохраняет NotificationService.updateTasks).
 * Задачи на новый день создаёт страница, поэтому если снимок вчерашний, виджет
 * пишет «Откройте приложение, чтобы обновить список».
 *
 * Обновление: при каждом новом снимке от страницы, после отметки, раз в 30 мин
 * (updatePeriodMillis) и при смене даты (checkDay из минутного тика шторки).
 */
public class TodayWidget extends AppWidgetProvider {

    private static final String TAG = "PlannerWidget";

    /** {"date":"YYYY-MM-DD","done":2,"total":5,"rows":[{"id","time","title","tap","sub"}]} */
    public static final String KEY_WIDGET_JSON = "widget_json";
    public static final String KEY_L_WTITLE = "widget_l_title";   // «Сегодня»
    public static final String KEY_L_WCOUNT = "widget_l_count";   // «{d} из {t}»
    public static final String KEY_L_WDONE  = "widget_l_done";    // «Всё на сегодня сделано 🎉»
    public static final String KEY_L_WSTALE = "widget_l_stale";   // «Откройте приложение…»
    private static final String KEY_DRAWN_DATE = "widget_drawn_date";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        draw(ctx, mgr, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, android.os.Bundle opts) {
        draw(ctx, mgr, new int[]{id});
    }

    /** Перерисовать все виджеты «Сегодня» (новые данные, отметка, смена даты). */
    public static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TodayWidget.class));
            if (ids == null || ids.length == 0) return;
            draw(ctx, mgr, ids);
        } catch (Exception e) {
            Log.e(TAG, "updateAll failed: " + e.getMessage());
        }
    }

    /** Дёшево: перерисовать, только если сменилась дата (зовётся раз в минуту). */
    public static void checkDay(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
        if (!todayIso().equals(sp.getString(KEY_DRAWN_DATE, ""))) updateAll(ctx);
    }

    private static void draw(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(ctx, id));
            } catch (Exception e) {
                Log.e(TAG, "draw failed: " + e.getMessage());
            }
        }
        try {
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        } catch (Exception e) {
            Log.e(TAG, "notify list failed: " + e.getMessage());
        }
        ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DRAWN_DATE, todayIso()).apply();
    }

    private static RemoteViews build(Context ctx, int widgetId) {
        SharedPreferences sp = ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
        JSONObject w = snapshot(sp);
        boolean fresh = w != null;

        String title = sp.getString(KEY_L_WTITLE, "Сегодня");
        String countTpl = sp.getString(KEY_L_WCOUNT, "{d} из {t}");
        String allDone = sp.getString(KEY_L_WDONE, "Всё на сегодня сделано 🎉");
        String stale = sp.getString(KEY_L_WSTALE, "Откройте приложение, чтобы обновить список");
        String noTasks = sp.getString(NotificationService.KEY_L_EMPTY, "Задач нет");

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_today);
        rv.setTextViewText(R.id.widget_title, title);

        String count = "";
        String empty = stale;
        if (fresh) {
            int done = w.optInt("done", 0);
            int total = w.optInt("total", 0);
            if (total > 0) {
                count = countTpl.replace("{d}", String.valueOf(done)).replace("{t}", String.valueOf(total));
                empty = allDone;
            } else {
                empty = noTasks;
            }
        }
        rv.setTextViewText(R.id.widget_count, count);
        rv.setTextViewText(R.id.widget_empty, empty);

        // список строк — через RemoteViewsService; data уникальна на виджет,
        // иначе система отдаст всем виджетам одну фабрику
        Intent svc = new Intent(ctx, TodayWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.widget_list, svc);
        rv.setEmptyView(R.id.widget_list, R.id.widget_empty);
        rv.setPendingIntentTemplate(R.id.widget_list, DoneReceiver.template(ctx));

        PendingIntent open = openApp(ctx);
        rv.setOnClickPendingIntent(R.id.widget_header, open);
        rv.setOnClickPendingIntent(R.id.widget_empty, open);
        return rv;
    }

    /** Снимок на сегодня или null (нет данных / снимок вчерашний). */
    static JSONObject snapshot(SharedPreferences sp) {
        String wj = sp.getString(KEY_WIDGET_JSON, "");
        if (wj == null || wj.isEmpty()) return null;
        try {
            JSONObject w = new JSONObject(wj);
            return todayIso().equals(w.optString("date", "")) ? w : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 3300, i, flags);
    }

    static String todayIso() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
