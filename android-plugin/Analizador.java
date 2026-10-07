package com.caudal.finanzas;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el texto de una notificación bancaria en un cobro entendible.
 *
 * Está escrito contra los formatos reales de Promerica GT, que son fijos:
 *
 *   Consumo PROMERICA **8240 Monto 156.20 QUETZALES Comercio ANTHROPIC*CLAUDE
 *   SUB +14152360599 US 15/09/2026 03:04:43 Aut. 780737.
 *
 *   Pago recibido Q739.98 en tu tarjeta ***8240, nuevo pago contado Q0.00/USD0.00
 *   pago minimo Q0.00/USD0.00 Vencimiento de pago 02/06/26
 *
 * Y con un camino genérico para Google Wallet y cualquier otra app, que todavía
 * no conocemos al dedillo. Ante la duda, marca el cobro como "revisar" en vez de
 * inventarse un monto: un 70.50 donde iban 7.05 ensucia el libro mayor, y eso es
 * peor que no capturar nada.
 */
public class Analizador {

    /* Monto con separador decimal explícito: 156.20 · 1,234.56 · 7,05 */
    private static final Pattern MONTO_CLARO = Pattern.compile(
            "(?:Q|GTQ|QUETZALES|\\$|USD)?\\s*([0-9]{1,3}(?:[,.][0-9]{3})*[.,][0-9]{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /* "Monto 156.20 QUETZALES" — el más fiable de Promerica */
    private static final Pattern MONTO_PROMERICA = Pattern.compile(
            "Monto\\s+([0-9.,]+)\\s*(QUETZALES|DOLARES|USD)", Pattern.CASE_INSENSITIVE);

    /* "Pago recibido Q739.98" */
    private static final Pattern MONTO_PAGO = Pattern.compile(
            "Pago\\s+recibido\\s*(?:Q|GTQ)?\\s*([0-9.,]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TARJETA = Pattern.compile(
            "(?:\\*{2,}|x{3,}|terminad[ao]\\s+en|final(?:izada)?\\s+en|•{2,})\\s*([0-9]{4})",
            Pattern.CASE_INSENSITIVE);

    /* El comercio va entre "Comercio " y la fecha dd/mm/aaaa */
    private static final Pattern COMERCIO_PROMERICA = Pattern.compile(
            "Comercio\\s+(.+?)\\s+\\d{2}/\\d{2}/\\d{4}", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMERCIO_GENERICO = Pattern.compile(
            "\\ben\\s+([A-Za-zÁÉÍÓÚÑáéíóúñ0-9&'.,\\- ]{3,45})", Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTORIZACION = Pattern.compile(
            "Aut\\.?\\s*([0-9]{4,12})", Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_HORA = Pattern.compile(
            "(\\d{2})/(\\d{2})/(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})");

    private static final Pattern VENCIMIENTO = Pattern.compile(
            "Vencimiento\\s+de\\s+pago\\s+(\\d{2})/(\\d{2})/(\\d{2,4})", Pattern.CASE_INSENSITIVE);

    /* Cola de ruido que Promerica pega al nombre del comercio: país y teléfono */
    private static final Pattern COLA_COMERCIO = Pattern.compile(
            "\\s*(\\+?\\d{8,15})?\\s*(GT|US|MX|ES|PA|CR|SV|HN|NI)\\s*$");

    /**
     * @return JSON con el cobro, o null si el texto no parece un movimiento de dinero.
     *         Campos: tipo (gasto|pago), monto, moneda, tarjeta, comercio, fecha,
     *         hora, autorizacion, huella, confianza (alta|revisar), vencimiento.
     */
    public static JSONObject analizar(String titulo, String texto, String paquete, long cuando) {
        String t = ((titulo == null ? "" : titulo) + " " + (texto == null ? "" : texto)).trim();
        if (t.length() < 8) return null;

        try {
            JSONObject r = new JSONObject();
            r.put("origen", paquete);
            r.put("textoCrudo", t);
            r.put("recibido", cuando);

            boolean esPago = t.toLowerCase(Locale.ROOT).contains("pago recibido");
            r.put("tipo", esPago ? "pago" : "gasto");

            /* ---- Monto ---- */
            double monto = 0;
            String moneda = "GTQ";
            boolean confiable = false;

            Matcher m = MONTO_PROMERICA.matcher(t);
            if (m.find()) {
                monto = aNumero(m.group(1));
                moneda = m.group(2).toUpperCase(Locale.ROOT).startsWith("QUETZ") ? "GTQ" : "USD";
                confiable = true;
            } else {
                m = MONTO_PAGO.matcher(t);
                if (m.find()) {
                    monto = aNumero(m.group(1));
                    confiable = true;
                } else {
                    m = MONTO_CLARO.matcher(t);
                    if (m.find()) {
                        monto = aNumero(m.group(1));
                        confiable = true;   // trae decimales explícitos: no hay ambigüedad
                        if (t.toUpperCase(Locale.ROOT).contains("USD")
                                || t.contains("$")) moneda = "USD";
                    }
                }
            }
            if (monto <= 0) return null;           // sin monto no hay cobro que registrar
            r.put("monto", monto);
            r.put("moneda", moneda);

            /* ---- Tarjeta ---- */
            m = TARJETA.matcher(t);
            String tarjeta = m.find() ? m.group(1) : "";
            r.put("tarjeta", tarjeta);

            /* ---- Comercio ---- */
            String comercio = "";
            m = COMERCIO_PROMERICA.matcher(t);
            if (m.find()) {
                comercio = limpiarComercio(m.group(1));
            } else if (!esPago) {
                m = COMERCIO_GENERICO.matcher(t);
                if (m.find()) comercio = limpiarComercio(m.group(1));
            }
            r.put("comercio", comercio);

            /* ---- Fecha y hora reales del cobro ---- */
            String fecha = null, hora = null;
            m = FECHA_HORA.matcher(t);
            if (m.find()) {
                fecha = m.group(3) + "-" + m.group(2) + "-" + m.group(1);
                hora = m.group(4) + ":" + m.group(5);
            }
            if (fecha == null) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTimeInMillis(cuando);
                fecha = String.format(Locale.US, "%04d-%02d-%02d",
                        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.DAY_OF_MONTH));
                hora = String.format(Locale.US, "%02d:%02d",
                        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
            }
            r.put("fecha", fecha);
            r.put("hora", hora);

            /* ---- Autorización y huella para no duplicar ---- */
            m = AUTORIZACION.matcher(t);
            String aut = m.find() ? m.group(1) : "";
            r.put("autorizacion", aut);
            r.put("huella", !aut.isEmpty()
                    ? "aut:" + aut
                    : String.format(Locale.US, "m:%.2f:%s:%s", monto, tarjeta, fecha + " " + hora));

            /* ---- Vencimiento de la tarjeta, cuando el aviso lo trae ---- */
            m = VENCIMIENTO.matcher(t);
            if (m.find()) {
                String anio = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
                r.put("vencimiento", anio + "-" + m.group(2) + "-" + m.group(1));
            }

            r.put("confianza", confiable ? "alta" : "revisar");
            return r;

        } catch (JSONException e) {
            return null;
        }
    }

    /** Quita el país y el teléfono que Promerica pega al final del comercio. */
    private static String limpiarComercio(String s) {
        if (s == null) return "";
        String c = s.trim().replaceAll("\\s+", " ");
        c = COLA_COMERCIO.matcher(c).replaceAll("");
        return c.trim();
    }

    /**
     * Interpreta el número respetando qué separador es el decimal. "1,234.56" y
     * "1.234,56" valen lo mismo: manda el separador que aparece de último.
     */
    private static double aNumero(String s) {
        if (s == null) return 0;
        String n = s.replaceAll("[^0-9.,]", "");
        int coma = n.lastIndexOf(','), punto = n.lastIndexOf('.');
        if (coma > -1 && punto > -1) {
            n = coma > punto ? n.replace(".", "").replace(',', '.') : n.replace(",", "");
        } else if (coma > -1) {
            n = (n.length() - coma - 1) == 2 ? n.replace(',', '.') : n.replace(",", "");
        }
        try { return Double.parseDouble(n); } catch (NumberFormatException e) { return 0; }
    }
}
