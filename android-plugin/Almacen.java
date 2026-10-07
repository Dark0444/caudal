package com.caudal.finanzas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Guarda y lee el estado que comparten el servicio de notificaciones y la app.
 *
 * El servicio corre sin WebView, así que no puede tocar el localStorage donde
 * vive la base de Caudal. Por eso todo lo que cruza entre los dos lados pasa
 * por aquí: la cola de cobros detectados, la configuración (tarjetas, apps
 * permitidas) y el resumen que la app deja listo para que los avisos de la
 * noche puedan armarse sin abrir nada.
 */
public class Almacen {

    private static final String PREFS = "caudal_puente";

    public static final String COLA        = "cola_pendientes";
    public static final String CONFIG      = "config";
    public static final String RESUMEN     = "resumen";
    public static final String VISTOS      = "apps_vistas";
    public static final String HUELLAS     = "huellas";   // para no registrar dos veces el mismo cobro

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String leer(Context ctx, String clave, String porDefecto) {
        return prefs(ctx).getString(clave, porDefecto);
    }

    public static void escribir(Context ctx, String clave, String valor) {
        prefs(ctx).edit().putString(clave, valor).apply();
    }

    public static JSONArray leerArreglo(Context ctx, String clave) {
        try {
            return new JSONArray(leer(ctx, clave, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public static void escribirArreglo(Context ctx, String clave, JSONArray arr) {
        escribir(ctx, clave, arr.toString());
    }

    public static JSONObject leerObjeto(Context ctx, String clave) {
        try {
            return new JSONObject(leer(ctx, clave, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Agrega a la cola y la recorta: no tiene sentido acumular cobros viejos sin resolver. */
    public static void encolar(Context ctx, JSONObject item) {
        JSONArray cola = leerArreglo(ctx, COLA);
        cola.put(item);
        while (cola.length() > 120) cola.remove(0);
        escribirArreglo(ctx, COLA, cola);
    }

    /**
     * Devuelve true si este cobro ya se había visto. La huella es el número de
     * autorización cuando viene, porque identifica la transacción sin lugar a
     * dudas; si no, monto + tarjeta + minuto, que basta para atrapar el caso de
     * Wallet y el banco avisando del mismo pago.
     */
    public static boolean yaVisto(Context ctx, String huella) {
        if (huella == null || huella.isEmpty()) return false;
        JSONArray h = leerArreglo(ctx, HUELLAS);
        for (int i = 0; i < h.length(); i++) {
            if (huella.equals(h.optString(i))) return true;
        }
        h.put(huella);
        while (h.length() > 400) h.remove(0);
        escribirArreglo(ctx, HUELLAS, h);
        return false;
    }

    /** Lleva nota de qué apps han mandado algo con pinta de cobro, para la pantalla de ajustes. */
    public static void registrarAppVista(Context ctx, String paquete, String etiqueta) {
        JSONArray vistas = leerArreglo(ctx, VISTOS);
        for (int i = 0; i < vistas.length(); i++) {
            if (paquete.equals(vistas.optJSONObject(i) != null
                    ? vistas.optJSONObject(i).optString("paquete") : null)) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("paquete", paquete);
            o.put("nombre", etiqueta);
            o.put("visto", System.currentTimeMillis());
            vistas.put(o);
            while (vistas.length() > 40) vistas.remove(0);
            escribirArreglo(ctx, VISTOS, vistas);
        } catch (JSONException ignored) { }
    }
}
