const C="mk7";
self.addEventListener("install",e=>self.skipWaiting());
self.addEventListener("activate",e=>e.waitUntil(self.clients.claim()));
self.addEventListener("fetch",e=>{
  const r=e.request;
  if(r.method!=="GET")return;
  if(r.mode==="navigate"){
    e.respondWith(
      fetch(r).then(x=>{caches.open(C).then(c=>c.put(r,x.clone()));return x;})
        .catch(()=>caches.match(r).then(x=>x||caches.match("index.html")))
    );
    return;
  }
  e.respondWith(
    caches.match(r).then(x=>x||fetch(r).then(y=>{
      if(y.ok)caches.open(C).then(c=>c.put(r,y.clone()));
      return y;
    }))
  );
});
