const CACHE='paper-jump-v15';
const SHELL=['./','index.html','styles.css?v=12','app.js?v=15','mobile-local-recognizer.js?v=1','private-sync.js?v=1','natural-recognizer.js?v=3','vendor/opencv.js?v=1','manifest.webmanifest','pages.json','custom_nodes.json','icons/paperjump-icon.png?v=11'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  if(event.request.mode==='navigate'){
    event.respondWith(fetch(event.request).catch(()=>caches.match('./')));
    return;
  }
  if(new URL(event.request.url).pathname.endsWith('private-data.enc.json'))return;
  event.respondWith(fetch(event.request).then(response=>{
    if(response.ok&&new URL(event.request.url).origin===self.location.origin)
      caches.open(CACHE).then(cache=>cache.put(event.request,response.clone()));
    return response;
  }).catch(()=>caches.match(event.request)));
});
