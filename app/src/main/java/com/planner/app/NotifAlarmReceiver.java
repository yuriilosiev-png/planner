package com.planner.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Будильник шторки (vC 11).
 *
 * Зачем ресивер, а не запуск сервиса напрямую: с Android 12 приложению нельзя
 * стартовать foreground-сервис из фона, а неточный будильник в список исключений
 * не входит. Ресивер же будится свободно и просто перерисовывает уже висящее
 * уведомление через NotificationManager.notify() по тому же id.
 *
 * Срабатывает на ближайшую границу: время задачи, момент скрытия строки цели
 * или полночь. Следующий будильник ставит сам refresh().
 */
public class NotifAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "PlannerNotif";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            NotificationService.refresh(ctx);
        } catch (Exception e) {
            Log.e(TAG, "alarm refresh failed: " + e.getMessage());
        }
    }
}
