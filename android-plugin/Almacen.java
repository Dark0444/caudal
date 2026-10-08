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
    public static final String DIAG        = "diagnostico";

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

    /**
     * Lleva nota de qué apps mandan notificaciones, para la pantalla de ajustes.
     * Solo el nombre del paquete, su etiqueta y un contador: nunca el contenido.
     * Así, cuando el banco no aparezca en la lista blanca, se puede ver cómo se
     * llama de verdad su app y marcarla a mano, en vez de adivinar el paquete.
     */
    public static void registrarAppVista(Context ctx, String paquete, String etiqueta,
                                         boolean permitida, boolean capturado) {
        JSONArray vistas = leerArreglo(ctx, VISTOS);
        try {
            for (int i = 0; i < vistas.length(); i++) {
                JSONObject o = vistas.optJSONObject(i);
                if (o != null && paquete.equals(o.optString("paquete"))) {
                    o.put("avisos", o.optInt("avisos") + 1);
                    o.put("visto", System.currentTimeMillis());
                    o.put("permitida", permitida);
                    if (capturado) o.put("capturados", o.optInt("capturados") + 1);
                    escribirArreglo(ctx, VISTOS, vistas);
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("paquete", paquete);
            o.put("nombre", etiqueta);
            o.put("visto", System.currentTimeMillis());
            o.put("avisos", 1);
            o.put("capturados", capturado ? 1 : 0);
            o.put("permitida", permitida);
            vistas.put(o);
            while (vistas.length() > 60) vistas.remove(0);
            escribirArreglo(ctx, VISTOS, vistas);
        } catch (JSONException ignored) { }
    }

    /**
     * Rastro de las últimas notificaciones de apps vigiladas y qué se hizo con
     * ellas. Es la única forma de saber por qué un cobro no se registró sin
     * tener el teléfono enchufado a una computadora.
     */
    public static void anotarDiagnostico(Context ctx, String paquete, String texto, String resultado) {
        try {
            JSONArray d = leerArreglo(ctx, DIAG);
            JSONObject o = new JSONObject();
            o.put("cuando", System.currentTimeMillis());
            o.put("paquete", paquete);
            o.put("texto", texto == null ? "" : (texto.length() > 400 ? texto.substring(0, 400) : texto));
            o.put("resultado", resultado);
            d.put(o);
            while (d.length() > 12) d.remove(0);
            escribirArreglo(ctx, DIAG, d);
        } catch (JSONException ignored) { }
    }
}
