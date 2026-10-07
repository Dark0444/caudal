package com.caudal.finanzas;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * Dispara los resúmenes y vuelve a programarse.
 *
 * Android no tiene alarmas "todos los días a las 21:00" que sobrevivan a todo,
 * así que cada disparo programa el siguiente. El receptor de arranque las
 * restablece cuando reinicias el teléfono.
 */
public class ResumenReceiver extends BroadcastReceiver {

    public static final String DIARIO  = "com.caudal.finanzas.RESUMEN_DIARIO";
    public static final String SEMANAL = "com.caudal.finanzas.RESUMEN_SEMANAL";

    private static final int HORA_DIARIA = 21;    // 9 de la noche
    private static final int HORA_SEMANAL = 19;   // domingo 7 de la noche

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String accion = intent != null ? intent.getAction() : null;
        try {
            if (SEMANAL.equals(accion)) {
                Avisos.resumenSemanal(ctx);
            } else if (DIARIO.equals(accion)) {
                Avisos.resumenDiario(ctx);
            }
        } catch (Throwable ignored) { }
        programarTodo(ctx);   // deja listo el siguiente
    }

    public static void programarTodo(Context ctx) {
        programar(ctx, DIARIO, siguienteDiario(), 9100);
        programar(ctx, SEMANAL, siguienteSemanal(), 9101);
    }

    private static void programar(Context ctx, String accion, long cuando, int codigo) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ResumenReceiver.class).setAction(accion);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            /* Inexacto a propósito: un resumen no necesita el segundo exacto, y así
               Android puede agruparlo con otras tareas y gastar menos batería. */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
            }
        } catch (Throwable ignored) { }
    }

    private static long siguienteDiario() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, HORA_DIARIA);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60000) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static long siguienteSemanal() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, HORA_SEMANAL);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY
                || c.getTimeInMillis() <= System.currentTimeMillis() + 60000) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, HORA_SEMANAL);
            c.set(Calendar.MINUTE, 0);
        }
        return c.getTimeInMillis();
    }
}
