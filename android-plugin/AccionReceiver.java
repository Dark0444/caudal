package com.caudal.finanzas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Atiende los botones de la notificación de un cobro detectado.
 *
 * Confirmar no escribe en el libro mayor directamente: el servicio corre sin
 * WebView y no puede tocar la base de la app. Lo que hace es marcar el cobro
 * como confirmado en la cola; la próxima vez que abras Caudal, la app lo
 * ingresa como movimiento. Así el toque es inmediato y el dato no se pierde,
 * pero el registro lo sigue haciendo un solo lado, que es el que sabe de
 * saldos, categorías y automatizaciones.
 */
public class AccionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String id = intent.getStringExtra("id");
        if (id == null) return;

        boolean confirmar = Avisos.ACCION_CONFIRMAR.equals(intent.getAction());
        String nuevoEstado = confirmar ? "confirmado" : "descartado";

        JSONArray cola = Almacen.leerArreglo(ctx, Almacen.COLA);
        JSONObject tocado = null;
        for (int i = 0; i < cola.length(); i++) {
            JSONObject c = cola.optJSONObject(i);
            if (c != null && id.equals(c.optString("id"))) {
                try {
                    c.put("estado", nuevoEstado);
                    c.put("resueltoEn", System.currentTimeMillis());
                    c.put("via", "notificacion");
                } catch (Exception ignored) { }
                tocado = c;
                break;
            }
        }
        Almacen.escribirArreglo(ctx, Almacen.COLA, cola);
        Avisos.cerrar(ctx, id);

        if (confirmar && tocado != null) {
            String comercio = tocado.optString("comercio", "");
            Avisos.simple(ctx, 7010,
                    "Listo, queda registrado",
                    (comercio.isEmpty() ? "El cobro" : comercio) + " por "
                            + Avisos.moneda(tocado.optDouble("monto", 0))
                            + " entrará al libro mayor la próxima vez que abras Caudal.");
        }
    }
}
