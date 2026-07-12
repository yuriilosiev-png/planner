package com.planner.app;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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

// Runtime-запрос POST_NOTIFICATIONS (Android 13+ / API 33). Задел на Этап 2:
// без этого разрешения система не покажет наше persistent-уведомление со списком
// задач. На Этапе 1 уведомлений ещё нет, но код запроса ставим сразу.
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

/**
 * Planner — нативная обёртка (WebView) для веб-планировщика.
 *
 * ГИБРИД-РЕЖИМ загрузки:
 *   1. При старте пробуем загрузить актуальную версию с GitHub Pages (PAGES_URL).
 *   2. Если сети нет / страница не грузится (onReceivedError по главному фрейму) —
 *      падаем на офлайн-копию, вшитую в APK (ASSET_URL = file:///android_asset/index.html).
 *   Это даёт свежий index.html без пересборки APK при наличии сети, и рабочее
 *   приложение офлайн.
 *
 * ОТЛИЧИЯ ОТ Options Analyst (for-options/MainActivity.java):
 *   - убран весь Firebase/FCM слой (fetchFcmToken, injectFcmToken, requestFcmToken,
 *     pendingFcmToken, сервис сообщений, google-services);
 *   - убран fullscreen immersive режим — планировщику нужен обычный статусбар;
 *   - добавлен onReceivedError → офлайн-фолбэк на assets;
 *   - AndroidBridge оставлен КАРКАСОМ (пустой задел под Этап 2 — updateNotificationTasks).
 */
public class MainActivity extends Activity {

    private static final String TAG = "PlannerMain";

    // requestCode для запроса POST_NOTIFICATIONS (Android 13+). Задел на Этап 2.
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    // Канал уведомлений. На Этапе 2 сюда будет вешаться persistent-уведомление
    // (importance LOW, чтобы не жужжать). Создаём канал заранее.
    private static final String CHANNEL_ID = "planner_tasks";

    private WebView webView;

    // Гибрид: онлайн-источник (GitHub Pages репозитория planner).
    // ВАЖНО: подставь реальный URL страницы планировщика на Pages.
    private static final String PAGES_URL =
        "https://yuriilosiev-png.github.io/planner/index.html";
    // Офлайн-фолбэк: копия index.html внутри APK (app/src/main/assets/index.html).
    private static final String ASSET_URL = "file:///android_asset/index.html";

    // Флаг: уже переключились на офлайн-копию, чтобы onReceivedError не зациклился,
    // если и офлайн-файл вдруг отдаст ошибку.
    private volatile boolean loadedOfflineFallback = false;

    // Слабая ссылка на экземпляр — пригодится на Этапе 2 (сервис уведомлений
    // будет дёргать WebView, как в Options Analyst).
    private static WeakReference<MainActivity> sInstance;

    public static MainActivity getInstance() {
        return sInstance != null ? sInstance.get() : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new WeakReference<>(this);

        // Канал уведомлений (Android 8+). Importance LOW — persistent-уведомление
        // не должно звенеть/вибрировать при каждом обновлении (Этап 2).
        createNotificationChannel();

        // Запрос POST_NOTIFICATIONS (Android 13+). Задел на Этап 2.
        requestNotificationPermission();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // localStorage планировщика
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Весь онлайн-контент (Pages) по HTTPS — mixed content не нужен.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Нужно для загрузки офлайн-копии из assets (file:///android_asset/...).
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(
            settings.getUserAgentString() + " PlannerApp/1.0"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // JS-мост. Каркас — на Этапе 2 добавим updateNotificationTasks(json).
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Безопасно: не принимаем невалидные сертификаты. Pages отдаёт
                // валидный HTTPS. Ошибка сертификата → отклоняем (ниже сработает
                // onReceivedError и мы уйдём в офлайн-копию).
                handler.cancel();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // Реагируем только на провал загрузки ГЛАВНОГО документа (не картинок/шрифтов).
                // Если Pages не загрузился (нет сети, DNS, таймаут) — грузим офлайн-копию.
                if (request != null && request.isForMainFrame() && !loadedOfflineFallback) {
                    loadedOfflineFallback = true;
                    Log.w(TAG, "Pages load failed, falling back to offline asset. code="
                        + (error != null ? error.getErrorCode() : "?"));
                    view.post(() -> view.loadUrl(ASSET_URL));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);

        // Гибрид: стартуем с Pages. При ошибке onReceivedError переключит на assets.
        webView.loadUrl(PAGES_URL);
    }

    // ─────────────────────────────────────────────────────────────────
    // AndroidBridge — JS-интерфейс. Методы вызываются из index.html:
    //   AndroidBridge.getVersion()
    // На Этапе 2 сюда добавится updateNotificationTasks(json) — index.html
    // будет отдавать задачи на сегодня для persistent-уведомления.
    // В JS обязательно оборачивать вызовы в:  if (typeof AndroidBridge !== 'undefined')
    // чтобы в браузере (без натива) не падало.
    // ─────────────────────────────────────────────────────────────────
    public static class AndroidBridge {
        private final WeakReference<MainActivity> activityRef;

        public AndroidBridge(MainActivity
