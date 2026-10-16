package com.planner.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.7: строки списка виджета «Сегодня». Читает снимок из SharedPreferences
 * (см. TodayWidget.snapshot). Кнопка ☐ есть только у строк с tap=true;
 * нажатие уходит в DoneReceiver через шаблон из TodayWidget + fill-in с id.
 */
public class TodayWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private final List<JSONObject> rows = new ArrayList<JSONObject>();

        Factory(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            rows.clear();
            SharedPreferences sp = ctx.getSharedPreferences(NotificationService.PREFS, Context.MODE_PRIVATE);
            JSONObject w = TodayWidget.snapshot(sp);
            if (w == null) return;
            JSONArray arr = w.optJSONArray("rows");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) rows.add(r);
            }
        }

        @Override
        public void onDestroy() {
            rows.clear();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= rows.size()) return null;
            JSONObject r = rows.get(position);
            String id = r.optString("id", "");
            String time = r.optString("time", "");
            String title = r.optString("title", "");
            String sub = r.optString("sub", "");
            boolean tap = r.optBoolean("tap", false) && !id.isEmpty();

            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_today_row);
            rv.setTextViewText(R.id.row_time, time);
            rv.setViewVisibility(R.id.row_time, time.isEmpty() ? View.GONE : View.VISIBLE);
            rv.setTextViewText(R.id.row_title, title.isEmpty() ? "—" : title);
            rv.setTextViewText(R.id.row_sub, sub);
            rv.setViewVisibility(R.id.row_sub, sub.isEmpty() ? View.GONE : View.VISIBLE);

            if (tap) {
                rv.setViewVisibility(R.id.row_check, View.VISIBLE);
                Intent fill = new Intent();
                fill.putExtra(DoneReceiver.EXTRA_ID, id);
                fill.setData(Uri.parse("planner://done/" + Uri.encode(id)));
                rv.setOnClickFillInIntent(R.id.row_check, fill);
            } else {
                rv.setViewVisibility(R.id.row_check, View.INVISIBLE);
            }
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
