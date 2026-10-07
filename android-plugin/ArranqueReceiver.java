package com.caudal.finanzas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a dejar programados los resúmenes después de reiniciar el teléfono. */
public class ArranqueReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Avisos.crearCanales(ctx);
            ResumenReceiver.programarTodo(ctx);
        } catch (Throwable ignored) { }
    }
}
