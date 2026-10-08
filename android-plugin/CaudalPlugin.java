package com.caudal.finanzas;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * La puerta entre la app (JavaScript) y todo lo nativo.
 *
 * La app usa esto para: saber si tiene el permiso de leer notificaciones,
 * pedirlo, recoger los cobros detectados, marcarlos como procesados, y dejar
 * guardado el resumen con el que se arman los avisos de la noche.
 */
@CapacitorPlugin(name = "CaudalNativo")
public class CaudalPlugin extends Plugin {

    /* Si el usuario abre o comparte un archivo con Caudal estando la app ya
       viva, Android no reemplaza el intent original: llega por aquí. */
    private Intent intentEntrante;

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        intentEntrante = intent;
    }

    /**
     * Devuelve el contenido del archivo con el que se abrió la app, si lo hubo.
     * Es la vía fiable para restaurar un respaldo: el usuario toca el .json en
     * su gestor de archivos y elige Caudal, sin pasar por el selector de
     * archivos del sistema, que en algunos teléfonos deja los .json en gris.
     */
    @PluginMethod
    public void leerArchivoEntrante(PluginCall call) {
        JSObject r = new JSObject();
        String texto = null, base64 = null, nombre = null;
        try {
            Intent i = intentEntrante;
            if (i == null && getActivity() != null) i = getActivity().getIntent();

            android.net.Uri uri = null;
            if (i != null) {
                String accion = i.getAction();
                if (Intent.ACTION_VIEW.equals(accion)) uri = i.getData();
                else if (Intent.ACTION_SEND.equals(accion)) uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri == null && i.hasExtra(Intent.EXTRA_TEXT)) texto = i.getStringExtra(Intent.EXTRA_TEXT);
            }

            if (uri != null) {
                java.io.InputStream in = getContext().getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                byte[] datos = bos.toByteArray();

                nombre = uri.getLastPathSegment();
                String bajo = nombre == null ? "" : nombre.toLowerCase(java.util.Locale.ROOT);
                if (bajo.endsWith(".xlsx") || bajo.endsWith(".xls")) {
                    base64 = android.util.Base64.encodeToString(datos, android.util.Base64.NO_WRAP);
                } else {
                    texto = new String(datos, "UTF-8");
                }
            }

            /* Se consume: si no, al volver del segundo plano se repetiría. */
            intentEntrante = null;
            if (i != null) { i.setAction(null); i.setData(null); i.removeExtra(Intent.EXTRA_TEXT); }
        } catch (Exception e) {
            r.put("error", e.getMessage() == null ? "No se pudo leer el archivo" : e.getMessage());
        }
        r.put("texto", texto == null ? "" : texto);
        r.put("base64", base64 == null ? "" : base64);
        r.put("nombre", nombre == null ? "" : nombre);
        call.resolve(r);
    }

    @Override
    public void load() {
        Context ctx = getContext();
        Avisos.crearCanales(ctx);
        ResumenReceiver.programarTodo(ctx);
        reenlazarLector(ctx);
    }

    /**
     * Al reinstalar el APK, Android deja el permiso de acceso a notificaciones
     * concedido pero suelta el servicio, así que deja de recibir avisos sin que
     * nada lo diga. Pedir el reenlace cada vez que arranca la app lo arregla sin
     * que el usuario tenga que apagar y prender el permiso a mano.
     */
    private void reenlazarLector(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.service.notification.NotificationListenerService.requestRebind(
                        new ComponentName(ctx, LectorNotificaciones.class));
            }
        } catch (Throwable ignored) { }
    }

    @PluginMethod
    public void reactivarLector(PluginCall call) {
        reenlazarLector(getContext());
        JSObject r = new JSObject();
        r.put("acceso", accesoConcedido());
        call.resolve(r);
    }

    /* ---------- Permiso de acceso a notificaciones ---------- */

    @PluginMethod
    public void tieneAccesoNotificaciones(PluginCall call) {
        JSObject r = new JSObject();
        r.put("valor", accesoConcedido());
        call.resolve(r);
    }

    private boolean accesoConcedido() {
        Context ctx = getContext();
        String activados = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        if (activados == null || activados.isEmpty()) return false;
        ComponentName propio = new ComponentName(ctx, LectorNotificaciones.class);
        for (String s : activados.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (cn != null && cn.equals(propio)) return true;
        }
        return false;
    }

    @PluginMethod
    public void abrirAjustesAcceso(PluginCall call) {
        try {
            Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir la pantalla de acceso a notificaciones", e);
        }
    }

    /* ---------- Cola de cobros detectados ---------- */

    @PluginMethod
    public void obtenerPendientes(PluginCall call) {
        JSObject r = new JSObject();
        r.put("cola", Almacen.leerArreglo(getContext(), Almacen.COLA));
        r.put("acceso", accesoConcedido());
        call.resolve(r);
    }

    /**
     * La app avisa qué cobros ya ingresó o descartó, y se quitan de la cola.
     * Recibe { ids: ["p123", ...] }.
     */
    @PluginMethod
    public void marcarProcesados(PluginCall call) {
        /* getArray devuelve el JSArray de Capacitor, que hereda de JSONArray. */
        JSArray ids = call.getArray("ids", new JSArray());
        JSONArray cola = Almacen.leerArreglo(getContext(), Almacen.COLA);
        JSONArray queda = new JSONArray();

        for (int i = 0; i < cola.length(); i++) {
            JSONObject c = cola.optJSONObject(i);
            if (c == null) continue;
            boolean fuera = false;
            for (int j = 0; j < ids.length(); j++) {
                if (c.optString("id").equals(ids.optString(j))) { fuera = true; break; }
            }
            if (fuera) Avisos.cerrar(getContext(), c.optString("id"));
            else queda.put(c);
        }
        Almacen.escribirArreglo(getContext(), Almacen.COLA, queda);
        call.resolve();
    }

    /** Cambia el estado de un pendiente desde la app (confirmado / descartado). */
    @PluginMethod
    public void resolverPendiente(PluginCall call) {
        String id = call.getString("id", "");
        String estado = call.getString("estado", "confirmado");
        JSONArray cola = Almacen.leerArreglo(getContext(), Almacen.COLA);
        for (int i = 0; i < cola.length(); i++) {
            JSONObject c = cola.optJSONObject(i);
            if (c != null && id.equals(c.optString("id"))) {
                try { c.put("estado", estado); } catch (Exception ignored) { }
                Avisos.cerrar(getContext(), id);
                break;
            }
        }
        Almacen.escribirArreglo(getContext(), Almacen.COLA, cola);
        call.resolve();
    }

    /* ---------- Configuración y resumen ---------- */

    /** { apps: [...paquetes] } — qué apps vigila el lector además de las de fábrica. */
    @PluginMethod
    public void configurar(PluginCall call) {
        JSObject datos = call.getData();
        Almacen.escribir(getContext(), Almacen.CONFIG, datos.toString());
        call.resolve();
    }

    @PluginMethod
    public void appsDetectadas(PluginCall call) {
        JSObject r = new JSObject();
        r.put("apps", Almacen.leerArreglo(getContext(), Almacen.VISTOS));
        r.put("config", Almacen.leerObjeto(getContext(), Almacen.CONFIG));
        call.resolve(r);
    }

    /**
     * Todo lo que hace falta para ver por qué un cobro no entró: si el permiso
     * está puesto, qué apps mandan avisos y qué pasó con las últimas de las apps
     * vigiladas.
     */
    @PluginMethod
    public void diagnostico(PluginCall call) {
        Context ctx = getContext();
        JSObject r = new JSObject();
        r.put("acceso", accesoConcedido());
        r.put("apps", Almacen.leerArreglo(ctx, Almacen.VISTOS));
        r.put("config", Almacen.leerObjeto(ctx, Almacen.CONFIG));
        r.put("registro", Almacen.leerArreglo(ctx, Almacen.DIAG));
        r.put("cola", Almacen.leerArreglo(ctx, Almacen.COLA));
        call.resolve(r);
    }

    /** { paquete, permitir: true|false } — vigilar o dejar de vigilar una app. */
    @PluginMethod
    public void permitirApp(PluginCall call) {
        String paquete = call.getString("paquete", "");
        boolean permitir = Boolean.TRUE.equals(call.getBoolean("permitir", true));
        if (paquete.isEmpty()) { call.reject("Falta el paquete"); return; }

        Context ctx = getContext();
        JSONObject cfg = Almacen.leerObjeto(ctx, Almacen.CONFIG);
        try {
            JSONArray apps = sinPaquete(cfg.optJSONArray("apps"), paquete);
            JSONArray bloq = sinPaquete(cfg.optJSONArray("bloqueadas"), paquete);
            if (permitir) apps.put(paquete); else bloq.put(paquete);
            cfg.put("apps", apps);
            cfg.put("bloqueadas", bloq);
            Almacen.escribir(ctx, Almacen.CONFIG, cfg.toString());
        } catch (Exception e) {
            call.reject("No se pudo guardar la lista de apps", e);
            return;
        }
        reenlazarLector(ctx);
        call.resolve();
    }

    private JSONArray sinPaquete(JSONArray origen, String paquete) {
        JSONArray salida = new JSONArray();
        if (origen == null) return salida;
        for (int i = 0; i < origen.length(); i++) {
            String p = origen.optString(i);
            if (!p.isEmpty() && !p.equalsIgnoreCase(paquete)) salida.put(p);
        }
        return salida;
    }

    /** Borra el rastro de diagnóstico y las huellas, para poder volver a probar. */
    @PluginMethod
    public void limpiarDiagnostico(PluginCall call) {
        Context ctx = getContext();
        Almacen.escribir(ctx, Almacen.DIAG, "[]");
        Almacen.escribir(ctx, Almacen.HUELLAS, "[]");
        Almacen.escribir(ctx, Almacen.VISTOS, "[]");
        call.resolve();
    }

    /**
     * La app deja aquí lo que los avisos de la noche necesitan saber:
     * { fecha, gastadoHoy, movsHoy, diario, gastadoSemana, gastadoSemanaPrevia }
     */
    @PluginMethod
    public void guardarResumen(PluginCall call) {
        Almacen.escribir(getContext(), Almacen.RESUMEN, call.getData().toString());
        ResumenReceiver.programarTodo(getContext());
        call.resolve();
    }

    /* ---------- Avisos sueltos que la app quiere empujar al sistema ---------- */

    @PluginMethod
    public void avisar(PluginCall call) {
        String titulo = call.getString("titulo", "Caudal");
        String cuerpo = call.getString("cuerpo", "");
        int codigo = call.getInt("codigo", 7020);
        Avisos.simple(getContext(), codigo, titulo, cuerpo);
        call.resolve();
    }

    /** Para el botón de prueba: lanza el resumen del día tal como saldría a las 21:00. */
    @PluginMethod
    public void probarResumen(PluginCall call) {
        Avisos.resumenDiario(getContext());
        call.resolve();
    }
}
