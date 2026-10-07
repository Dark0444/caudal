/* Service worker de Nébula Finanzas.
   Objetivos:
   1) Permitir instalar la app (Agregar a pantalla de inicio) y que funcione sin conexión.
   2) Ser el requisito que necesita Android/Chrome para mostrar notificaciones del sistema
      (showNotification), en vez de solo el aviso dentro de la app.
   No hay servidor ni "push" real: todas las notificaciones las decide y muestra la propia
   app (o este worker, cuando la app está cerrada, ver mensaje "programar-recordatorio" abajo).
*/
const CACHE = 'nebula-shell-v1';
const APP_SHELL = ['./', './index.html', './manifest.json',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png'];

self.addEventListener('install', evt=>{
  self.skipWaiting();
  evt.waitUntil(caches.open(CACHE).then(c=>c.addAll(APP_SHELL)).catch(()=>{}));
});

self.addEventListener('activate', evt=>{
  evt.waitUntil((async ()=>{
    const keys = await caches.keys();
    await Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)));
    await self.clients.claim();
  })());
});

// Red primero (para tener siempre la versión más reciente cuando hay conexión),
// y si falla (sin datos/wifi), se sirve la última copia guardada.
self.addEventListener('fetch', evt=>{
  if(evt.request.method!=='GET') return;
  const url = new URL(evt.request.url);
  if(url.origin!==self.location.origin) return;
  evt.respondWith((async ()=>{
    try{
      const fresh = await fetch(evt.request);
      const cache = await caches.open(CACHE);
      cache.put(evt.request, fresh.clone());
      return fresh;
    }catch(e){
      const cached = await caches.match(evt.request, {ignoreSearch:true});
      return cached || caches.match('./index.html');
    }
  })());
});

// Al tocar una notificación, enfoca la app si ya está abierta o la abre.
self.addEventListener('notificationclick', evt=>{
  evt.notification.close();
  evt.waitUntil((async ()=>{
    const all = await self.clients.matchAll({type:'window', includeUncontrolled:true});
    for(const c of all){ if('focus' in c) return c.focus(); }
    if(self.clients.openWindow) return self.clients.openWindow('./index.html');
  })());
});

// Alarmas locales programadas por la propia app (ver registrarRecordatorios en index.html).
// No usan red ni servidor: solo Periodic Background Sync, disponible en Chrome/Android
// cuando la app está instalada. Si el navegador no lo soporta, la app revisa igual
// cada vez que la abres.
self.addEventListener('periodicsync', evt=>{
  if(evt.tag==='nebula-revision') evt.waitUntil(revisarDesdeWorker());
});

async function revisarDesdeWorker(){
  try{
    const all = await self.clients.matchAll({type:'window'});
    if(all.length){ all.forEach(c=>c.postMessage({tipo:'revisar-alertas'})); return; }
    // Sin ninguna pestaña abierta no hay acceso a los datos (viven en localStorage,
    // no en el worker), así que no se puede armar el mensaje real. Se deja constancia
    // de que tocaba revisar para cuando el usuario vuelva a abrir la app.
  }catch(e){}
}
