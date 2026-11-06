const CACHE = "kiplay-v6-1";
const SHELL = ["./", "./index.html", "./icon.png", "./manifest.webmanifest"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener("fetch", e => {
  const r = e.request;
  if (r.method !== "GET") return;
  const u = new URL(r.url);
  if (u.origin !== location.origin) return;

  if (r.mode === "navigate" || u.pathname.endsWith("index.html")) {
    e.respondWith(
      fetch(r).then(res => {
        const cp = res.clone();
        caches.open(CACHE).then(c => c.put(r, cp));
        return res;
      }).catch(() => caches.match("./index.html"))
    );
    return;
  }

  e.respondWith(
    caches.match(r).then(hit => hit || fetch(r).then(res => {
      const cp = res.clone();
      caches.open(CACHE).then(c => c.put(r, cp));
      return res;
    }).catch(() => hit))
  );
});

self.addEventListener("message", e => {
  if (e.data === "skipWaiting") self.skipWaiting();
});
