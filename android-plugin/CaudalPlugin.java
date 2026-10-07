package com.caudal.finanzas;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

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

    @Override
    public void load() {
        Context ctx = getContext();
        Avisos.crearCanales(ctx);
        ResumenReceiver.programarTodo(ctx);
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
        JSONArray ids = call.getArray("ids", new JSONArray());
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
