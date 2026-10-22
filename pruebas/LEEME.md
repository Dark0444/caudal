# Comprobación de coherencia

Un mismo quetzal aparece en seis pantallas: el disponible del tablero, el flujo
del ciclo, el neto del mes, el reporte, la bóveda y el resumen de la noche. Cada
vez que cambia la forma de clasificar un movimiento es fácil arreglarlo en una
pantalla y romperlo en otra.

Pasó tres veces en un solo día con lo guardado en la bóveda: primero se sumó al
abono de la tarjeta, luego se contó como gasto del ciclo, luego como pérdida del
mes. Ninguna se detectó mirando capturas, porque cada captura se veía bien por
separado.

Esto comprueba las identidades que siempre deben cumplirse:

- El patrimonio es exactamente lo disponible más la bóveda más los planes.
- El saldo de cada cuenta es su saldo inicial más sus movimientos.
- El neto del mes no castiga el ahorro: guardar y abonar no son gastar.
- Lo libre del ciclo nunca pasa del efectivo que existe.
- El techo diario nunca pasa de lo libre.
- Las salidas del ciclo son la suma exacta de sus partes.

## Cómo se usa

    node scripts/comprobar.mjs pruebas/datos/*.json

Sale con código 1 si algo no cuadra. Corre en cada compilación, antes de
generar el APK: si las cuentas no cuadran, no se publica.

Dentro de la app, la misma comprobación corre al abrir Ajustes y muestra ahí lo
que no cuadre, con los datos reales de quien la usa.

## Los casos

| archivo | qué prueba |
|---|---|
| `real-con-boveda.json` | datos reales: bóveda, ciclo de pago, categorías fijas |
| `sin-boveda-sin-ciclo.json` | la app básica, sin nada de eso activado |
| `sin-ingreso.json` | un ciclo que empieza antes de registrar el sueldo |
| `vacio.json` | instalación recién hecha |
| `debe-fallar-cuenta-en-rojo.json` | una cuenta en negativo |

Un archivo que empieza con `debe-fallar-` se espera que dé problemas: comprueba
que la comprobación sirve. Si ese pasa limpio, la red tiene un agujero.
