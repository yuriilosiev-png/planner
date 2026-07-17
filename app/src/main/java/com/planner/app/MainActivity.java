package com.planner.app;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/**
 * Planner — нативная обёртка (WebView) для веб-планировщика.
 *
 * EDGE-TO-EDGE: WebView под статусбаром, статусбар прозрачный (фон приложения
 * заходит под часы). В index.html зона закрыта через env(safe-area-inset-top).
 *
 * ГИБРИД: сначала GitHub Pages (PAGES_URL); при ошибке — офлайн-копия из assets.
 *
 * МОСТ ДЛЯ БЭКАПА (работает на любом Android, minSdk 24):
 *   - shareBackup(json, filename)  — системный «Поделиться» (Telegram, Drive, почта)
 *   - saveToDownloads(json, filename) — сохранить в папку Загрузки
 *   - pickBackupFile()             — системный выбор .json для импорта
 * Web Share API НЕ используем — он нестабилен в WebView на части устройств.
 */
public class MainActivity extends Activity {

    private static final String TAG = "PlannerMain";
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int REQ_PICK_BACKUP = 2001;
    private static final String CHANNEL_ID = "planner_tasks";

    private WebView webView;

    private static final String PAGES_URL =
        "https://yuriilosiev-png.github.io/planner/index.html";
    private static final String ASSET_URL = "file:///android_asset/index.html";

    private volatile boolean loadedOfflineFallback = false;

    private static WeakReference<MainActivity> sInstance;

    public static MainActivity getInstance() {
        return sInstance != null ? sInstance.get() : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new WeakReference<>(this);

        // ---- EDGE-TO-EDGE ----
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        createNotificationChannel();
        requestNotificationPermission();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0f1115"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // КЭШ: если сеть есть — всегда берём свежую версию с Pages (LOAD_NO_CACHE),
        // если сети нет — разрешаем кэш (LOAD_CACHE_ELSE_NETWORK), а при полном
        // провале загрузки onReceivedError уведёт на офлайн-копию из assets.
        // Это убирает баг «белый экран / старая версия до перезапуска».
        settings.setCacheMode(isOnline()
            ? WebSettings.LOAD_NO_CACHE
            : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(
            settings.getUserAgentString() + " PlannerApp/1.2"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && !loadedOfflineFallback) {
                    loadedOfflineFallback = true;
                    Log.w(TAG, "Pages load failed, falling back to offline asset. code="
                        + (error != null ? error.getErrorCode() : "?"));
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.loadUrl(ASSET_URL);
                        }
                    });
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Если есть сеть — чистим кэш WebView перед загрузкой.
        // Это добивает старый залипший service-worker-кэш (из прежних сборок),
        // из-за которого приложение показывало устаревшую версию/белый экран
        // до ручного перезапуска. JS со своей стороны тоже снимает регистрацию SW.
        if (isOnline()) {
            try {
                webView.clearCache(true);
                webView.clearHistory();
            } catch (Exception e) {
                Log.w(TAG, "clearCache failed", e);
            }
        }

        webView.loadUrl(PAGES_URL);
    }

    // ─────────────────────────────────────────────────────────────────
    // Вызвать JS-функцию в WebView (натив → веб)
    // ─────────────────────────────────────────────────────────────────
    private void callJs(final String js) {
        if (webView == null) return;
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * Есть ли интернет. От этого зависит стратегия кэша:
     * онлайн → всегда свежая версия с Pages; офлайн → кэш/assets.
     */
    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            Log.w(TAG, "isOnline check failed", e);
            return false;
        }
    }

    // экранирование строки для безопасной вставки в JS
    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ─────────────────────────────────────────────────────────────────
    // AndroidBridge — JS-интерфейс.
    // В JS вызывать ТОЛЬКО с проверкой: if (typeof AndroidBridge !== 'undefined')
    // ─────────────────────────────────────────────────────────────────
    public static class AndroidBridge {
        private final WeakReference<MainActivity> activityRef;

        public AndroidBridge(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @JavascriptInterface
        public String getVersion() {
            return "1.0";
        }

        /** Есть ли нативный мост (JS проверяет, чтобы решить какие кнопки показать). */
        @JavascriptInterface
        public boolean hasNativeBackup() {
            return true;
        }

        /**
         * Системный «Поделиться»: сохраняет JSON во внутренний кэш и открывает
         * системный диалог (Telegram, Google Drive, почта, Files...).
         * Работает на любом Android через FileProvider.
         */
        @JavascriptInterface
        public void shareBackup(final String json, final String filename) {
            final MainActivity a = activityRef.get();
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    a.doShareBackup(json, filename);
                }
            });
        }

        /** Сохранить JSON в папку Загрузки (Downloads). */
        @JavascriptInterface
        public void saveToDownloads(final String json, final String filename) {
            final MainActivity a = activityRef.get();
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    a.doSaveToDownloads(json, filename);
                }
            });
        }

        /** Открыть системный выбор файла для импорта бэкапа. */
        @JavascriptInterface
        public void pickBackupFile() {
            final MainActivity a = activityRef.get();
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    a.doPickBackupFile();
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ШАРИНГ: файл в кэш → FileProvider → системный chooser
    // ─────────────────────────────────────────────────────────────────
    private void doShareBackup(String json, String filename) {
        try {
            if (filename == null || filename.trim().isEmpty()) filename = "planner-backup.json";
            // Кладём в cache/backups (этот путь прописан в file_paths.xml)
            File dir = new File(getCacheDir(), "backups");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();

            Uri uri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", f);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, filename);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Сохранить резервную копию"));
            callJs("window.onNativeBackupResult && window.onNativeBackupResult('share','ok','')");
        } catch (Exception e) {
            Log.e(TAG, "shareBackup failed", e);
            callJs("window.onNativeBackupResult && window.onNativeBackupResult('share','error','"
                + jsEscape(e.getMessage()) + "')");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // СОХРАНЕНИЕ В DOWNLOADS
    // Android 10+ (API 29): MediaStore (без разрешений)
    // Android 9-  (API 24-28): прямая запись в public Downloads
    // ─────────────────────────────────────────────────────────────────
    private void doSaveToDownloads(String json, String filename) {
        try {
            if (filename == null || filename.trim().isEmpty()) filename = "planner-backup.json";
            byte[] data = json.getBytes(StandardCharsets.UTF_8);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("openOutputStream failed");
                os.write(data);
                os.close();
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
            }
            callJs("window.onNativeBackupResult && window.onNativeBackupResult('save','ok','"
                + jsEscape(filename) + "')");
        } catch (Exception e) {
            Log.e(TAG, "saveToDownloads failed", e);
            callJs("window.onNativeBackupResult && window.onNativeBackupResult('save','error','"
                + jsEscape(e.getMessage()) + "')");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ИМПОРТ: системный выбор файла → чтение → отдать JSON в JS
    // createChooser обязателен: без него на части устройств (MIUI и др.)
    // intent молча уходит в Google Drive, и выбрать другой источник нельзя.
    // С chooser система показывает список: Файлы, Диск, Telegram, Загрузки…
    // ─────────────────────────────────────────────────────────────────
    private void doPickBackupFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");   // некоторые провайдеры не отдают application/json
            // подсказка системе, какие типы нам интересны (Files подсветит .json)
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream", "*/*"
            });
            i.putExtra(Intent.EXTRA_LOCAL_ONLY, false); // разрешить облачные источники

            Intent chooser = Intent.createChooser(i, "Откуда взять резервную копию?");
            startActivityForResult(chooser, REQ_PICK_BACKUP);
        } catch (Exception e) {
            Log.e(TAG, "pickBackupFile failed", e);
            // запасной путь: ACTION_GET_CONTENT понимают почти все файловые приложения
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.addCategory(Intent.CATEGORY_OPENABLE);
                g.setType("*/*");
                startActivityForResult(
                    Intent.createChooser(g, "Откуда взять резервную копию?"),
                    REQ_PICK_BACKUP);
            } catch (Exception e2) {
                Log.e(TAG, "GET_CONTENT fallback failed", e2);
                callJs("window.onNativeImportResult && window.onNativeImportResult('error','"
                    + jsEscape(e2.getMessage()) + "','')");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BACKUP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            callJs("window.onNativeImportResult && window.onNativeImportResult('cancel','','')");
            return;
        }
        try {
            Uri uri = data.getData();
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) throw new Exception("openInputStream failed");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 20 * 1024 * 1024) throw new Exception("Файл слишком большой");
                bos.write(buf, 0, n);
            }
            is.close();
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            // отдаём JSON в JS через base64, чтобы не ломать кавычками/переносами
            String b64 = android.util.Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            callJs("window.onNativeImportResult && window.onNativeImportResult('ok','','" + b64 + "')");
        } catch (Exception e) {
            Log.e(TAG, "read picked file failed", e);
            callJs("window.onNativeImportResult && window.onNativeImportResult('error','"
                + jsEscape(e.getMessage()) + "','')");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                    REQ_POST_NOTIFICATIONS
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "POST_NOTIFICATIONS granted: " + granted);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Задачи на сегодня",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Постоянное уведомление со списком задач");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sInstance = null;
        webView.destroy();
    }
}
