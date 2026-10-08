package com.caudal.finanzas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Arma y publica las notificaciones propias de Caudal.
 *
 * Todas se construyen aquí, en código nativo, porque el texto depende de datos
 * que solo se conocen en el momento de dispararse: cuánto llevas gastado hoy,
 * qué cobro acaba de entrar. Una notificación programada desde JavaScript
 * llevaría el texto escrito desde que se programó, y quedaría desfasada.
 */
public class Avisos {

    public static final String CANAL_COBROS  = "caudal-cobros";
    public static final String CANAL_RESUMEN = "caudal-resumen";

    public static final String ACCION_CONFIRMAR = "com.caudal.finanzas.CONFIRMAR";
    public static final String ACCION_DESCARTAR = "com.caudal.finanzas.DESCARTAR";

    public static void crearCanales(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel cobros = new NotificationChannel(CANAL_COBROS,
                "Cobros detectados", NotificationManager.IMPORTANCE_HIGH);
        cobros.setDescription("Compras y pagos leídos de las notificaciones de tu banco");
        cobros.enableVibration(true);
        nm.createNotificationChannel(cobros);

        NotificationChannel resumen = new NotificationChannel(CANAL_RESUMEN,
                "Resúmenes", NotificationManager.IMPORTANCE_DEFAULT);
        resumen.setDescription("Resumen del día, corte semanal y avisos de presupuesto");
        nm.createNotificationChannel(resumen);
    }

    public static String moneda(double n) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(new Locale("es", "GT"));
            return f.format(n);
        } catch (Exception e) {
            return String.format(Locale.US, "Q%.2f", n);
        }
    }

    private static PendingIntent abrirApp(Context ctx, int codigo, String extra) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) i = new Intent();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (extra != null) i.putExtra("caudal_ir_a", extra);
        return PendingIntent.getActivity(ctx, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Notificación de un cobro recién detectado, con sus dos botones. */
    public static void avisarCobroDetectado(Context ctx, JSONObject cobro) {
        crearCanales(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String id = cobro.optString("id");
        double monto = cobro.optDouble("monto", 0);
        String comercio = cobro.optString("comercio", "");
        String tarjeta = cobro.optString("tarjeta", "");
        boolean esPago = "pago".equals(cobro.optString("tipo"));
        boolean revisar = "revisar".equals(cobro.optString("confianza"));

        String titulo = esPago
                ? "Pago a tu tarjeta · " + moneda(monto)
                : "Gasto detectado · " + moneda(monto);

        StringBuilder cuerpo = new StringBuilder();
        if (!comercio.isEmpty()) cuerpo.append(comercio);
        if (!tarjeta.isEmpty()) {
            if (cuerpo.length() > 0) cuerpo.append(" · ");
            cuerpo.append("•••• ").append(tarjeta);
        }
        if (revisar) {
            if (cuerpo.length() > 0) cuerpo.append("\n");
            cuerpo.append("Revisa el monto antes de confirmar.");
        } else {
            if (cuerpo.length() > 0) cuerpo.append("\n");
            cuerpo.append("Confirma para registrarlo, o ábrelo para corregirlo.");
        }

        int codigo = Math.abs(id.hashCode());

        Intent confirmar = new Intent(ctx, AccionReceiver.class)
                .setAction(ACCION_CONFIRMAR).putExtra("id", id);
        Intent descartar = new Intent(ctx, AccionReceiver.class)
                .setAction(ACCION_DESCARTAR).putExtra("id", id);

        PendingIntent piConfirmar = PendingIntent.getBroadcast(ctx, codigo, confirmar,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piDescartar = PendingIntent.getBroadcast(ctx, codigo + 1, descartar,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CANAL_COBROS)
                : new Notification.Builder(ctx);

        b.setSmallIcon(ctx.getResources().getIdentifier("ic_stat_caudal", "drawable", ctx.getPackageName()))
                .setContentTitle(titulo)
                .setContentText(cuerpo.toString())
                .setStyle(new Notification.BigTextStyle().bigText(cuerpo.toString()))
                .setAutoCancel(true)
                .setContentIntent(abrirApp(ctx, codigo + 2, "pendientes"));

        if (!revisar) {
            b.addAction(new Notification.Action.Builder(null, "Confirmar", piConfirmar).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Editar",
                abrirApp(ctx, codigo + 3, "pendientes")).build());
        b.addAction(new Notification.Action.Builder(null, "Descartar", piDescartar).build());

        nm.notify(codigo, b.build());
    }

    public static void cerrar(Context ctx, String id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Math.abs(id.hashCode()));
    }

    /** Aviso simple, sin botones (resúmenes, presupuesto, tarjeta). */
    public static void simple(Context ctx, int codigo, String titulo, String cuerpo) {
        crearCanales(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CANAL_RESUMEN)
                : new Notification.Builder(ctx);

        b.setSmallIcon(ctx.getResources().getIdentifier("ic_stat_caudal", "drawable", ctx.getPackageName()))
                .setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(new Notification.BigTextStyle().bigText(cuerpo))
                .setAutoCancel(true)
                .setContentIntent(abrirApp(ctx, codigo, null));

        nm.notify(codigo, b.build());
    }

    /* ============================================================
       Resúmenes: el texto se calcula aquí, con el resumen que la app
       dejó guardado más los cobros confirmados que todavía no ha ingresado.
       ============================================================ */

    public static void resumenDiario(Context ctx) {
        JSONObject r = Almacen.leerObjeto(ctx, Almacen.RESUMEN);
        String hoy = fechaHoy();

        double gastado = hoy.equals(r.optString("fecha")) ? r.optDouble("gastadoHoy", 0) : 0;
        int movs = hoy.equals(r.optString("fecha")) ? r.optInt("movsHoy", 0) : 0;

        /* Suma lo confirmado desde la notificación que aún no entra al libro mayor */
        JSONArray cola = Almacen.leerArreglo(ctx, Almacen.COLA);
        for (int i = 0; i < cola.length(); i++) {
            JSONObject c = cola.optJSONObject(i);
            if (c == null) continue;
            if (!"confirmado".equals(c.optString("estado"))) continue;
            if (!"gasto".equals(c.optString("tipo"))) continue;
            if (!hoy.equals(c.optString("fecha"))) continue;
            gastado += c.optDouble("monto", 0);
            movs++;
        }

        String titulo, cuerpo;
        if (movs == 0) {
            titulo = "Hoy no gastaste nada";
            cuerpo = "Un día completo sin movimientos. Así se construye el colchón.";
        } else {
            titulo = "Hoy gastaste " + moneda(gastado);
            cuerpo = movs + (movs == 1 ? " movimiento" : " movimientos") + ".";
        }

        String cierre = proyeccionCiclo(r, gastado, movs == 0);
        if (!cierre.isEmpty()) {
            cuerpo += " " + cierre;
        } else {
            double diario = r.optDouble("diario", 0);
            if (diario > 0 && movs > 0) {
                cuerpo += gastado > diario
                        ? " Te pasaste " + moneda(gastado - diario) + " de tu margen del día."
                        : " Te quedaron " + moneda(diario - gastado) + " de tu margen del día.";
            }
        }
        simple(ctx, 7001, titulo, cuerpo);
    }

    /**
     * Lo que de verdad se quiere saber de noche no es cuánto se fue hoy, sino en
     * qué termina esto. Toma el ritmo de gasto de lo que va del ciclo, lo
     * proyecta sobre los días que faltan y dice con cuánto se cierra.
     *
     * El ritmo del día de hoy pesa aparte: si hoy no se gastó nada, la frase lo
     * aprovecha para mostrar que el cierre mejora.
     */
    private static String proyeccionCiclo(JSONObject r, double gastadoHoy, boolean diaLimpio) {
        int faltan = r.optInt("cicloDiasRestantes", -1);
        if (faltan < 0) return "";
        double libre = r.optDouble("cicloLibre", 0);
        double ritmo = r.optDouble("cicloRitmo", 0);

        if (faltan == 0) {
            return libre > 0
                    ? "Cierras este periodo con " + moneda(libre) + " sin gastar."
                    : "Hoy cierra el periodo.";
        }
        if (libre <= 0) {
            return "Ya no te queda margen para los " + diasTxt(faltan) + " que faltan.";
        }
        if (ritmo <= 0) {
            return "Te quedan " + moneda(libre) + " para " + diasTxt(faltan) + ".";
        }

        double cierre = libre - ritmo * faltan;
        String base = "A este ritmo (" + moneda(ritmo) + " al día) cierras ";
        if (cierre >= 1) {
            return base + "con " + moneda(cierre) + " ahorrado."
                    + (diaLimpio ? " Un día como hoy lo sube." : "");
        }
        if (cierre > -1) return base + "justo en cero.";

        double porDia = libre / faltan;
        return base + "corto por " + moneda(-cierre) + ". Bajando a " + moneda(porDia)
                + " al día llegas sin deber.";
    }

    private static String diasTxt(int n) {
        return n == 1 ? "1 día" : n + " días";
    }

    public static void resumenSemanal(Context ctx) {
        JSONObject r = Almacen.leerObjeto(ctx, Almacen.RESUMEN);
        double semana = r.optDouble("gastadoSemana", 0);
        double previa = r.optDouble("gastadoSemanaPrevia", 0);

        String titulo, cuerpo;
        if (semana <= 0 && previa <= 0) return;   // nada que comparar: mejor callar

        if (previa <= 0) {
            titulo = "Esta semana gastaste " + moneda(semana);
            cuerpo = "Es tu primera semana con datos; a partir de la próxima te la comparo.";
        } else {
            double dif = semana - previa;
            int pct = (int) Math.round(dif / previa * 100);
            if (Math.abs(pct) < 8) {
                titulo = "Semana pareja: " + moneda(semana);
                cuerpo = "Casi lo mismo que la semana pasada (" + moneda(previa) + ").";
            } else if (dif < 0) {
                titulo = "Gastaste " + moneda(-dif) + " menos que la semana pasada";
                cuerpo = "Cerraste en " + moneda(semana) + ", un " + Math.abs(pct) + "% abajo. Bien ahí.";
            } else {
                titulo = "Gastaste " + moneda(dif) + " más que la semana pasada";
                cuerpo = "Cerraste en " + moneda(semana) + ", un " + pct + "% arriba.";
            }
        }
        String cierre = proyeccionCiclo(r, 0, false);
        if (!cierre.isEmpty()) cuerpo += " " + cierre;
        simple(ctx, 7002, titulo, cuerpo);
    }

    private static String fechaHoy() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
