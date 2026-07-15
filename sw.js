/* Planner service worker — network-first for HTML, cache-first for static assets.
   App is fully local (localStorage), no backend needed.

   СТРАТЕГИЯ (важно для авто-обновлений):
   - index.html и навигация → NETWORK-FIRST: всегда пробуем свежую версию из сети,
     кэш используем только если сети нет (офлайн). Значит правки index.html
     подтягиваются АВТОМАТИЧЕСКИ при следующем открытии с интернетом — без смены
     версии кэша и без переустановки.
   - иконки/манифест (статика) → CACHE-FIRST: они меняются редко, быстрее из кэша.

   Версию CACHE всё равно меняем при релизе, чтобы очистить старый кэш иконок. */

const CACHE = 'planner-v3-6f543e13';
const ASSETS = [
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-192.png',
  './icon-maskable-512.png',
  './apple-touch-icon.png',
  './favicon.png'
];

// Install: pre-cache только статику (НЕ index.html — он всегда из сети)
self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

// Activate: удалить все старые кэши
self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Определяем, является ли запрос HTML-документом / навигацией
function isHtmlRequest(req) {
  if (req.mode === 'navigate') return true;
  const url = req.url || '';
  const accept = req.headers.get('accept') || '';
  return accept.includes('text/html')
      || url.endsWith('/')
      || url.endsWith('index.html');
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  if (isHtmlRequest(req)) {
    // NETWORK-FIRST: сначала сеть (свежий index.html), кэш — только офлайн-фолбэк.
    e.respondWith(
      fetch(req)
        .then((res) => {
          // положить свежую копию в кэш (для будущего офлайна)
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put('./index.html', copy));
          }
          return res;
        })
        .catch(() =>
          // сети нет → отдать последний закэшированный index.html
          caches.match('./index.html').then((cached) =>
            cached || caches.match(req)
          )
        )
    );
    return;
  }

  // СТАТИКА (иконки, манифест): cache-first, сеть как фолбэк.
  e.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req).then((res) => {
        if (res && res.status === 200 && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        }
        return res;
      });
    })
  );
});
