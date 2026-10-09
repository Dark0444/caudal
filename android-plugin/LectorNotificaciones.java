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
 *   · De cualquier app se anota solo el nombre del paquete, su etiqueta y un
 *     contador, para que en Ajustes se pueda ver cómo se llama de verdad la app
 *     del banco y marcarla. El contenido de las apps que no se vigilan nunca se
 *     guarda ni se analiza.
 *   · De las apps vigiladas (lista de fábrica, nombre con pinta de banco, o
 *     marcadas por el usuario) se analiza el texto buscando un movimiento de
 *     dinero. Si no trae monto, no se guarda nada.
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

    /**
     * Los nombres de paquete de los bancos no son adivinables, así que además de
     * la lista de arriba vale cualquier app cuyo paquete o etiqueta suene a banco
     * o a billetera. Esto se decide con el nombre, nunca con el contenido.
     */
    private static final String[] PISTAS = {
            "promerica", "banco", "bancar", "bank", "banrural", "bantrab",
            "industrial", "bienlinea", "bi en linea", "interbanco", "bam",
            "gyt", "g&t", "continental", "azteca", "ficohsa", "visa",
            "mastercard", "wallet", "googlepay", "google pay", "gpay",
            "billetera", "tarjeta"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try { procesar(sbn); } catch (Throwable ignored) { }
    }

    /**
     * Cuando Android enlaza el servicio —al conceder el permiso, al reinstalar la
     * app o al reiniciar el teléfono— repasamos lo que ya está en la barra. Sin
     * esto, los avisos que llegaron mientras el servicio estaba desenlazado se
     * perderían para siempre, que es justo lo que pasa al actualizar el APK.
     */
    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] activas = getActiveNotifications();
            if (activas == null) return;
            for (StatusBarNotification sbn : activas) {
                try { procesar(sbn); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private void procesar(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Context ctx = getApplicationContext();
        String paquete = sbn.getPackageName();
        if (paquete == null || paquete.equals(getPackageName())) return;   // nunca a nosotros mismos

        String etiqueta = etiquetaApp(ctx, paquete);
        boolean permitida = estaPermitida(ctx, paquete, etiqueta);

        /* De las apps que no vigilamos no se lee el contenido: solo queda
           constancia de que existen, para poder marcarlas desde Ajustes. */
        if (!permitida) {
            Almacen.registrarAppVista(ctx, paquete, etiqueta, false, false);
            return;
        }

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) { Almacen.registrarAppVista(ctx, paquete, etiqueta, true, false); return; }

        String titulo = textoDe(extras, Notification.EXTRA_TITLE);
        String cuerpo = textoDe(extras, Notification.EXTRA_BIG_TEXT);
        if (cuerpo.isEmpty()) cuerpo = textoDe(extras, Notification.EXTRA_TEXT);
        if (cuerpo.isEmpty()) cuerpo = textoDe(extras, Notification.EXTRA_SUMMARY_TEXT);
        if (cuerpo.isEmpty()) cuerpo = lineasDe(extras);
        String completo = (titulo + " " + cuerpo).trim();

        if (completo.length() < 8) {
            Almacen.registrarAppVista(ctx, paquete, etiqueta, true, false);
            Almacen.anotarDiagnostico(ctx, paquete, completo, "texto vacío o muy corto");
            return;
        }

        String clave = null;
        try { clave = sbn.getKey(); } catch (Throwable ignored) { }
        JSONObject cobro = Analizador.analizar(titulo, cuerpo, paquete, sbn.getPostTime(), clave);
        if (cobro == null) {
            Almacen.registrarAppVista(ctx, paquete, etiqueta, true, false);
            Almacen.anotarDiagnostico(ctx, paquete, completo, "sin monto reconocible");
            return;
        }

        /* Mismo cobro avisado dos veces (billetera + banco, o repaso al enlazar
           el servicio): se ignora el segundo. */
        if (Almacen.yaVisto(ctx, cobro.optString("huella"))) {
            Almacen.registrarAppVista(ctx, paquete, etiqueta, true, false);
            Almacen.anotarDiagnostico(ctx, paquete, completo, "repetido, ya estaba registrado");
            return;
        }

        try {
            cobro.put("id", "p" + System.currentTimeMillis()
                    + Integer.toString((int) (Math.random() * 999)));
            cobro.put("estado", "pendiente");
            cobro.put("app", etiqueta);
        } catch (Exception ignored) { }

        Almacen.encolar(ctx, cobro);
        Almacen.registrarAppVista(ctx, paquete, etiqueta, true, true);
        Almacen.anotarDiagnostico(ctx, paquete, completo,
                "capturado: " + cobro.optString("tipo") + " " + cobro.optDouble("monto", 0)
                        + " tarjeta " + cobro.optString("tarjeta"));
        Avisos.avisarCobroDetectado(ctx, cobro);
    }

    private boolean estaPermitida(Context ctx, String paquete, String etiqueta) {
        JSONObject cfg = Almacen.leerObjeto(ctx, Almacen.CONFIG);

        /* Lo que el usuario desmarcó a mano gana sobre todo lo demás. */
        JSONArray fuera = cfg.optJSONArray("bloqueadas");
        if (fuera != null) {
            for (int i = 0; i < fuera.length(); i++) {
                if (paquete.equalsIgnoreCase(fuera.optString(i))) return false;
            }
        }

        JSONArray lista = cfg.optJSONArray("apps");
        if (lista != null) {
            for (int i = 0; i < lista.length(); i++) {
                if (paquete.equalsIgnoreCase(lista.optString(i))) return true;
            }
        }

        for (String p : POR_OMISION) if (paquete.equalsIgnoreCase(p)) return true;

        /* Y cualquier app cuyo nombre suene a banco o billetera. */
        String aguja = (paquete + " " + (etiqueta == null ? "" : etiqueta)).toLowerCase(Locale.ROOT);
        for (String pista : PISTAS) if (aguja.contains(pista)) return true;

        return false;
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
        try {
            CharSequence cs = extras.getCharSequence(clave);
            return cs == null ? "" : cs.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Algunos bancos mandan el detalle en el estilo de bandeja, no en EXTRA_TEXT. */
    private String lineasDe(Bundle extras) {
        try {
            CharSequence[] lineas = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lineas == null || lineas.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : lineas) {
                if (c == null) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(c.toString().trim());
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
