# Caudal

App de finanzas personales: cuentas, presupuestos, tarjeta de crédito, obligaciones,
metas de ahorro, comparativas y reportes mensuales. Todo vive en el teléfono.

## Cómo instalar la app

Descarga el APK desde la última versión publicada:

**https://github.com/Dark0444/caudal/releases/download/apk-latest/Caudal.apk**

Se instala encima de la versión anterior sin desinstalar nada, porque siempre va
firmado con la misma llave. La primera vez que la abras te pedirá el permiso de
notificaciones: acéptalo, es lo que hace que los avisos suenen con la app cerrada.

## Cómo está armado

El corazón de la app es un solo archivo, `www/index.html`, con toda la lógica en
JavaScript. Capacitor lo envuelve en una app Android nativa.

```
www/index.html         la app completa (interfaz + lógica)
www/native-bridge.js   el puente con Android: notificaciones, archivos, vibración
www/manifest.json      para usarla también como PWA desde el navegador
capacitor.config.json  nombre, identificador y ajustes del contenedor
scripts/vendor.js      copia SheetJS y las fuentes para que funcione sin internet
scripts/patch-android.js  permisos, firma e íconos del proyecto nativo
keystore/caudal.jks    llave de firma
```

La carpeta `android/` no está versionada: se genera en cada compilación y el script
`patch-android.js` le aplica encima todo lo que necesita. Así no hay un proyecto
nativo desactualizado arrastrándose en el repo.

## Notificaciones

Es la diferencia real entre el APK y la versión web. El puente programa alarmas con
el sistema operativo, no con el navegador:

- Tres días antes de que venza una obligación, a las 9:00.
- El día que vence, a las 9:00.
- Se reprograman solas cada vez que agregas, editas, pagas o borras algo.
- Suenan aunque la app esté cerrada y el teléfono bloqueado.

Además, código nativo arma en el momento estos avisos, así que el texto siempre
refleja el dato del instante en que suena:

- **21:00** — lo gastado hoy y en cuántos movimientos, o la felicitación si no gastaste.
- **Domingo 19:00** — la semana contra la anterior.

## Lectura de cobros del banco

Con el permiso de *acceso a notificaciones* de Android, Caudal lee los avisos de
Promerica y de Google Wallet y los convierte en cobros. No se registran solos: caen
en la bandeja de **Cobros detectados**, y los apruebas desde la propia notificación
o desde la app, donde puedes corregir monto, cuenta y categoría antes.

Formatos que entiende hoy sin adivinar:

```
Consumo PROMERICA **8240 Monto 156.20 QUETZALES Comercio ...  → gasto
Pago recibido Q739.98 en tu tarjeta ***8240 ...               → abono a la tarjeta
```

El número de autorización sirve de huella, así que si el banco y la billetera
avisan del mismo pago, solo se registra una vez. Cada cuenta lleva sus últimos
cuatro dígitos para saber a cuál cargar el cobro, y la app aprende qué categoría
corresponde a cada comercio la primera vez que se la indicas.

Solo se leen las apps de la lista blanca. De las demás no se guarda contenido.

## Compilar

Cada empujón a `main` compila el APK en GitHub Actions y lo publica en la versión
`apk-latest`. También se puede lanzar a mano desde la pestaña Actions.

Para compilar en tu máquina hace falta Android SDK y JDK 21:

```bash
npm install
node scripts/vendor.js
npx cap add android
node scripts/patch-android.js
cd android && ./gradlew assembleRelease
```

## Sobre la llave de firma

`keystore/caudal.jks` está en el repositorio para que las compilaciones sean
automáticas y los APK se puedan actualizar entre sí. Como el repositorio es público,
cualquiera podría firmar un APK que se haga pasar por este. Para una app personal que
no se distribuye es un riesgo acotado; si algún día la publicas, hay que mover la
llave a un secreto del repositorio.
