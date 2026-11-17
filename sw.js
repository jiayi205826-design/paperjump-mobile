const CACHE='paper-jump-v16';
const LIB_CACHE='paper-jump-libraries-v1';
const OPENCV='./vendor/opencv.js?v=1';
const SHELL=['./','index.html','styles.css?v=12','app.js?v=16','mobile-local-recognizer.js?v=1','private-sync.js?v=1','natural-recognizer.js?v=4','manifest.webmanifest','pages.json','custom_nodes.json','icons/paperjump-icon.png?v=11'];
self.addEventListener('install',event=>event.waitUntil(Promise.all([
  caches.open(CACHE).then(cache=>cache.addAll(SHELL)),
  caches.match(OPENCV).then(old=>old&&caches.open(LIB_CACHE).then(cache=>cache.put(OPENCV,old))),
]).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key!==CACHE&&key!==LIB_CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  if(event.request.mode==='navigate'){
    event.respondWith(fetch(event.request).catch(()=>caches.match('./')));
    return;
  }
  if(new URL(event.request.url).pathname.endsWith('private-data.enc.json'))return;
  if(new URL(event.request.url).pathname.endsWith('/vendor/opencv.js')){
    event.respondWith(caches.open(LIB_CACHE).then(async cache=>{
      const saved=await cache.match(event.request);
      if(saved)return saved;
      const response=await fetch(event.request);
      if(response.ok)event.waitUntil(cache.put(event.request,response.clone()));
      return response;
    }));
    return;
  }
  event.respondWith(fetch(event.request).then(response=>{
    if(response.ok&&new URL(event.request.url).origin===self.location.origin)
      caches.open(CACHE).then(cache=>cache.put(event.request,response.clone()));
    return response;
  }).catch(()=>caches.match(event.request)));
});
