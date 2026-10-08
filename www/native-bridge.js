/* ============================================================
   Caudal — puente nativo (Capacitor)

   Este archivo es lo único que separa a la app web del APK. Se carga
   ANTES del script principal y, cuando detecta que corre dentro del
   contenedor Android, publica `window.NebulaNativo` con la misma forma
   que el script principal ya esperaba. Así la app no necesita saber si
   está en un navegador o en el teléfono: llama a las mismas funciones.

   Lo que aporta, y que la PWA nunca pudo hacer:
     · Notificaciones PROGRAMADAS con AlarmManager de Android, que suenan
       aunque la app esté cerrada y el teléfono bloqueado.
     · Guardado de archivos en Documentos + hoja de compartir del sistema.
     · Vibración nativa.

   Fuera del APK no hace nada: no define NebulaNativo y la app sigue por
   su camino web de siempre.
   ============================================================ */
(function () {
  'use strict';

  var LLAVE_DB = 'nebula.finanzas.v1';
  var CANAL = 'caudal-avisos';
  var HORAS_AVISO = [9, 18];   // mañana y tarde, hora local
  var DIAS_AVISO = 3;          // cuántos días antes empieza a insistir
  var MESES_ADELANTE = 4;      // cuántos meses de obligaciones mensuales programar
  var TOPE_AVISOS = 150;       // Android no acepta alarmas exactas sin límite

  function cap() { return window.Capacitor; }
  function esNativo() {
    try { return !!(cap() && cap().isNativePlatform && cap().isNativePlatform()); }
    catch (e) { return false; }
  }
  function plugin(n) {
    try { return cap().Plugins[n] || null; } catch (e) { return null; }
  }

  /* Los plugins se resuelven al arrancar, no al cargar el archivo: Capacitor
     inyecta su puente en la página y no hay garantía de que ya esté listo
     cuando este script se parsea. */
  var LN = null, FS = null, Share = null, App = null, Haptics = null;
  var permisoOk = false;      // se consulta de verdad al arrancar; aquí solo cacheamos

  /* ---------- Permisos ---------- */
  async function revisarPermiso() {
    if (!LN) return false;
    try {
      var r = await LN.checkPermissions();
      permisoOk = r && r.display === 'granted';
    } catch (e) { permisoOk = false; }
    return permisoOk;
  }
  async function pedirPermiso() {
    if (!LN) return false;
    try {
      var r = await LN.requestPermissions();
      permisoOk = r && r.display === 'granted';
    } catch (e) { permisoOk = false; }
    return permisoOk;
  }

  /* ---------- Aviso inmediato ---------- */
  function mostrarNotificacion(titulo, cuerpo) {
    if (!LN) return false;
    LN.schedule({
      notifications: [{
        id: Math.floor(Math.random() * 100000) + 900000,   // rango alto: no choca con los programados
        title: titulo,
        body: cuerpo,
        channelId: CANAL,
        smallIcon: 'ic_stat_caudal',
        schedule: { at: new Date(Date.now() + 400) }
      }]
    }).catch(function () { });
    return true;
  }

  /* ---------- Utilidades de fecha ---------- */
  function hoyLocal() {
    var d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' +
           String(d.getDate()).padStart(2, '0');
  }
  function diasEnMes(y, m) { return new Date(y, m, 0).getDate(); }
  function aFecha(iso, hora) {
    var p = String(iso).split('-');
    return new Date(+p[0], +p[1] - 1, +p[2], hora || 0, 0, 0, 0);
  }
  /* ID entero estable a partir de una cadena: así reprogramar reemplaza
     el aviso anterior en vez de duplicarlo. */
  function idDe(txt) {
    var h = 5381;
    for (var i = 0; i < txt.length; i++) h = ((h << 5) + h + txt.charCodeAt(i)) | 0;
    return Math.abs(h) % 800000 + 1000;   // 1000..800999, lejos del rango inmediato
  }
  /* "2026-10-13" → "lun 13 oct", para que el aviso diga la fecha sin ocupar línea. */
  function diaCorto(iso) {
    try {
      return aFecha(iso, 12).toLocaleDateString('es-GT',
        { weekday: 'short', day: 'numeric', month: 'short' });
    } catch (e) { return iso; }
  }
  function moneda(n) {
    try {
      return new Intl.NumberFormat('es-GT', { style: 'currency', currency: 'GTQ' }).format(Number(n) || 0);
    } catch (e) { return 'Q' + (Number(n) || 0).toFixed(2); }
  }

  /* ---------- Lectura directa de la base ---------- */
  function leerDB() {
    try {
      var raw = localStorage.getItem(LLAVE_DB);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  /* Fecha de vencimiento de una obligación en un mes dado, con la misma
     regla que usa la app: el día se recorta al último día del mes. */
  function vencimientoEn(ob, y, m) {
    var dia = Math.min(Math.max(Number(ob.dia) || 1, 1), diasEnMes(y, m));
    return y + '-' + String(m).padStart(2, '0') + '-' + String(dia).padStart(2, '0');
  }

  /* ¿Ya se pagó esa ocurrencia? Misma lógica que estadoObligacion:
     las de pago único cuentan pagos de cualquier mes; las mensuales,
     solo los del mes de esa ocurrencia. */
  function yaPagada(db, ob, mesClave) {
    var pagos = (db.transacciones || []).filter(function (t) {
      if (t.obligacionId !== ob.id) return false;
      if (ob.repite === false) return true;
      return String(t.fecha || '').slice(0, 7) === mesClave;
    });
    var total = pagos.reduce(function (a, t) { return a + (Number(t.monto) || 0); }, 0);
    return total >= (Number(ob.monto) || 0) - 0.01;
  }

  /* ---------- Programación de avisos reales ---------- */
  async function programarAvisos() {
    if (!LN || !permisoOk) return 0;
    var db = leerDB();
    if (!db || !Array.isArray(db.obligaciones)) return 0;

    var prefs = (db.config && db.config.notifPrefs) || {};
    if (prefs.obligaciones === false) {    // el usuario apagó este tipo de aviso
      await cancelarProgramados();
      return 0;
    }

    var ahora = Date.now();
    var lista = [];
    var hoy = hoyLocal();
    var diasAviso = Number(prefs.diasAnticipacion);
    if (!isFinite(diasAviso) || diasAviso < 0) diasAviso = DIAS_AVISO;
    diasAviso = Math.min(diasAviso, 10);

    db.obligaciones.forEach(function (ob) {
      var ocurrencias = [];
      if (ob.repite === false) {
        if (ob.fecha) ocurrencias.push(ob.fecha);
      } else {
        var d = new Date();
        for (var i = 0; i < MESES_ADELANTE; i++) {
          ocurrencias.push(vencimientoEn(ob, d.getFullYear(), d.getMonth() + 1));
          d.setMonth(d.getMonth() + 1);
        }
      }

      ocurrencias.forEach(function (venc) {
        if (venc < hoy) return;                       // ya pasó
        if (yaPagada(db, ob, venc.slice(0, 7))) return;

        var monto = moneda(ob.monto);
        var nombre = ob.nombre || 'Obligación';

        /* Una cuenta por pagar no se recuerda una sola vez: se insiste cada
           día desde unos días antes, por la mañana y por la tarde, hasta que
           llega la fecha. El texto dice cuánto falta, así que mirar la
           notificación basta para saber si urge. */
        for (var d = diasAviso; d >= 0; d--) {
          for (var h = 0; h < HORAS_AVISO.length; h++) {
            var cuando = new Date(aFecha(venc, HORAS_AVISO[h]).getTime() - d * 86400000);
            if (cuando.getTime() <= ahora + 60000) continue;

            var tarde = HORAS_AVISO[h] >= 12;
            var titulo, cuerpo;
            if (d === 0) {
              titulo = tarde ? 'Vence hoy, aún sin pagar' : 'Hoy toca pagar';
              cuerpo = nombre + ' · ' + monto + (tarde ? ' · se vence hoy' : '');
            } else if (d === 1) {
              titulo = 'Vence mañana';
              cuerpo = nombre + ' · ' + monto;
            } else {
              titulo = 'Faltan ' + d + ' días';
              cuerpo = nombre + ' · ' + monto + ' · vence el ' + diaCorto(venc);
            }

            lista.push({
              id: idDe('ob:' + ob.id + ':' + venc + ':' + d + ':' + HORAS_AVISO[h]),
              title: titulo,
              body: cuerpo,
              channelId: CANAL,
              smallIcon: 'ic_stat_caudal',
              schedule: { at: cuando, allowWhileIdle: true }
            });
          }
        }
      });
    });

    // Android limita cuántas alarmas exactas puede tener una app; recortamos
    // a las más cercanas en el tiempo, que son las que de verdad importan.
    lista.sort(function (a, b) { return a.schedule.at - b.schedule.at; });
    lista = lista.slice(0, TOPE_AVISOS);

    await cancelarProgramados();
    if (!lista.length) return 0;
    try { await LN.schedule({ notifications: lista }); } catch (e) { return 0; }
    return lista.length;
  }

  async function cancelarProgramados() {
    if (!LN) return;
    try {
      var p = await LN.getPending();
      var ids = (p.notifications || [])
        .map(function (n) { return { id: n.id }; })
        .filter(function (n) { return n.id < 900000; });   // no tocamos los inmediatos
      if (ids.length) await LN.cancel({ notifications: ids });
    } catch (e) { }
  }

  /* ---------- Guardar archivos (respaldo) ---------- */
  async function guardarArchivo(nombre, base64, mime) {
    if (!FS) return false;
    try {
      var r = await FS.writeFile({
        path: nombre,
        data: base64,
        directory: 'DOCUMENTS',
        recursive: true
      });
      if (Share && r && r.uri) {
        try {
          await Share.share({
            title: 'Respaldo de Caudal',
            text: 'Respaldo de tus finanzas: ' + nombre,
            url: r.uri,
            dialogTitle: 'Guardar o enviar el respaldo'
          });
        } catch (e) { /* el usuario canceló la hoja de compartir: el archivo ya quedó guardado */ }
      }
      return true;
    } catch (e) { return false; }
  }

  /* ---------- Interfaz que el script principal ya esperaba ---------- */
  var API = {
    mostrarNotificacion: mostrarNotificacion,
    tienePermiso: function () { return permisoOk; },
    pedirPermiso: pedirPermiso,
    abrirAjustes: function () {
      // Capacitor no abre los ajustes de notificación directamente; pedir el
      // permiso de nuevo lleva al usuario al diálogo del sistema, y si ya lo
      // negó, Android muestra el acceso a los ajustes de la app.
      pedirPermiso().then(function () {
        if (typeof window.renderNotifEstado === 'function') window.renderNotifEstado();
      });
    },
    programarAvisos: programarAvisos,
    guardarArchivo: guardarArchivo,
    vibrar: function (ms) {
      if (Haptics) { try { Haptics.vibrate({ duration: ms || 20 }); return; } catch (e) { } }
      try { navigator.vibrate && navigator.vibrate(ms || 20); } catch (e) { }
    }
  };

  /* ---------- Arranque ---------- */
  var yaArranco = false;
  async function arrancarNativo() {
    if (yaArranco) return;
    if (!esNativo()) return;
    yaArranco = true;          // navegador: la app sigue por su camino web

    LN = plugin('LocalNotifications');
    FS = plugin('Filesystem');
    Share = plugin('Share');
    App = plugin('App');
    Haptics = plugin('Haptics');

    window.NebulaNativo = API;        // a partir de aquí la app se sabe nativa
    if (typeof window.renderNotifEstado === 'function') {
      try { window.renderNotifEstado(); } catch (e) { }
    }

    if (LN) {
      try {
        await LN.createChannel({
          id: CANAL,
          name: 'Avisos de Caudal',
          description: 'Obligaciones por vencer, presupuestos y metas',
          importance: 4,              // alta: aparece en pantalla
          visibility: 1,
          vibration: true,
          lights: true
        });
      } catch (e) { }

      await revisarPermiso();
      if (!permisoOk) await pedirPermiso();

      // Al tocar la notificación, abrimos el centro de alertas.
      try {
        LN.addListener('localNotificationActionPerformed', function () {
          if (typeof window.toggleNotifPanel === 'function') {
            setTimeout(window.toggleNotifPanel, 400);
          }
        });
      } catch (e) { }
    }

    // La app ya cargó su base; programamos con lo que haya.
    setTimeout(programarAvisos, 1500);

    // Al volver del segundo plano: revisar alertas y reprogramar, por si
    // cambió algo o ya pasó una fecha.
    if (App) {
      try {
        App.addListener('appStateChange', function (estado) {
          if (estado && estado.isActive) {
            if (typeof window.runAlertEngine === 'function') { try { window.runAlertEngine(); } catch (e) { } }
            // Al volver a la app recogemos lo que el lector haya capturado mientras
            // estuvo cerrada, y dejamos el resumen de la noche al día.
            if (typeof window.sincronizarPendientes === 'function') {
              try { window.sincronizarPendientes(false); } catch (e) { }
            }
            if (typeof window.actualizarResumenNativo === 'function') {
              try { window.actualizarResumenNativo(); } catch (e) { }
            }
            // Por si la app ya estaba viva y le mandaron un respaldo a abrir.
            if (typeof window.revisarArchivoEntrante === 'function') {
              try { window.revisarArchivoEntrante(); } catch (e) { }
            }
            programarAvisos();
          }
        });
      } catch (e) { }
    }
  }

  // Capacitor avisa cuando su puente terminó de inyectarse; si ese evento no
  // llega (navegador), el arranque simplemente sale por el chequeo de esNativo.
  document.addEventListener('deviceready', arrancarNativo, { once: true });
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(arrancarNativo, 0); });
  } else {
    setTimeout(arrancarNativo, 0);
  }
})();
