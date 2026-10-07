package com.caudal.finanzas;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Escucha las notificaciones del teléfono y se queda únicamente con los cobros.
 *
 * Android exige que el usuario conceda a mano el permiso de "acceso a
 * notificaciones" para que este servicio reciba algo. Mientras no lo haga, esta
 * clase no se ejecuta nunca.
 *
 * Qué mira y qué no:
 *   · Solo apps de la lista blanca (banco y billetera). Lo demás se descarta
 *     antes de leer su contenido.
 *   · De las apps permitidas, solo el texto que tiene pinta de movimiento de
 *     dinero. Si no trae monto, no se guarda nada.
 *   · De las demás apps únicamente se anota el nombre del paquete, y solo
 *     cuando el texto parece un cobro, para poder ofrecértelas en los ajustes.
 *     Su contenido no se guarda.
 */
public class LectorNotificaciones extends NotificationListenerService {

    /** Apps que vigilamos por omisión. El usuario puede sumar otras desde la app. */
    private static final String[] POR_OMISION = {
            "com.promerica.gt",                       // Promerica GT
            "gt.com.promerica",
            "com.google.android.apps.walletnfcrel",   // Google Wallet
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.bi.bienlinea",                       // Banco Industrial
            "com.bancoindustrial.bienlinea"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try { procesar(sbn); } catch (Throwable ignored) { }
    }

    private void procesar(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Context ctx = getApplicationContext();
        String paquete = sbn.getPackageName();
        if (paquete == null || paquete.equals(getPackageName())) return;   // nunca a nosotros mismos

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        String titulo = textoDe(extras, Notification.EXTRA_TITLE);
        String cuerpo = textoDe(extras, Notification.EXTRA_BIG_TEXT);
        if (cuerpo.isEmpty()) cuerpo = textoDe(extras, Notification.EXTRA_TEXT);
        String completo = (titulo + " " + cuerpo).trim();
        if (completo.length() < 8) return;

        boolean permitida = estaPermitida(ctx, paquete);

        /* De las apps que no vigilamos solo anotamos que existen, y únicamente si
           su texto parece un cobro. Así los ajustes pueden sugerírtelas sin que
           la app guarde el contenido de notificaciones ajenas. */
        if (!permitida) {
            if (pareceCobro(completo)) {
                Almacen.registrarAppVista(ctx, paquete, etiquetaApp(ctx, paquete));
            }
            return;
        }

        JSONObject cobro = Analizador.analizar(titulo, cuerpo, paquete, sbn.getPostTime());
        if (cobro == null) return;

        /* Mismo cobro avisado dos veces (billetera + banco): se ignora el segundo. */
        if (Almacen.yaVisto(ctx, cobro.optString("huella"))) return;

        try {
            cobro.put("id", "p" + System.currentTimeMillis()
                    + Integer.toString((int) (Math.random() * 999)));
            cobro.put("estado", "pendiente");
            cobro.put("app", etiquetaApp(ctx, paquete));
        } catch (Exception ignored) { }

        Almacen.encolar(ctx, cobro);
        Avisos.avisarCobroDetectado(ctx, cobro);
    }

    private boolean estaPermitida(Context ctx, String paquete) {
        JSONObject cfg = Almacen.leerObjeto(ctx, Almacen.CONFIG);
        JSONArray lista = cfg.optJSONArray("apps");
        if (lista != null && lista.length() > 0) {
            for (int i = 0; i < lista.length(); i++) {
                if (paquete.equalsIgnoreCase(lista.optString(i))) return true;
            }
            /* La lista del usuario manda, pero las de omisión siguen valiendo
               para que no se le apague el banco por error al configurar. */
        }
        for (String p : POR_OMISION) if (paquete.equalsIgnoreCase(p)) return true;
        return false;
    }

    /** Heurística muy conservadora: una cifra con decimales junto a una marca de dinero. */
    private boolean pareceCobro(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        boolean marca = s.contains("q") || s.contains("gtq") || s.contains("$")
                || s.contains("usd") || s.contains("quetzal");
        boolean cifra = t.matches("(?s).*\\d+[.,]\\d{2}.*");
        boolean verbo = s.contains("compra") || s.contains("consumo") || s.contains("pago")
                || s.contains("cobro") || s.contains("transacc") || s.contains("debit");
        return marca && cifra && verbo;
    }

    private String etiquetaApp(Context ctx, String paquete) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString();
        } catch (Exception e) {
            return paquete;
        }
    }

    private String textoDe(Bundle extras, String clave) {
        CharSequence cs = extras.getCharSequence(clave);
        return cs == null ? "" : cs.toString().trim();
    }
}
