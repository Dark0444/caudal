#!/usr/bin/env node
/* Ajustes al proyecto Android que Capacitor genera.

   El proyecto nativo (android/) NO se versiona: se regenera en cada
   compilación con `npx cap add android`. Por eso todo lo que haya que
   cambiarle se aplica aquí, de forma repetible, en vez de a mano.

   Qué hace:
     1. Permisos de notificación y alarmas exactas en el AndroidManifest.
     2. Firma con la llave del repo, para que cada APK nuevo se pueda
        instalar ENCIMA del anterior sin desinstalar.
     3. Número de versión incremental, para que Android reconozca la
        actualización.
     4. Copia el ícono monocromo de la barra de estado.
     5. Instala el componente nativo propio (lector de notificaciones del banco,
        resúmenes y cola de cobros) y lo registra en MainActivity.
*/
const fs = require('fs');
const path = require('path');

const raiz = path.resolve(__dirname, '..');
const android = path.join(raiz, 'android');
if (!fs.existsSync(android)) {
  console.error('::error title=Falta el proyecto Android::Ejecuta `npx cap add android` antes de este script.');
  process.exit(1);
}

const hechos = [];
function leer(p) { return fs.readFileSync(p, 'utf8'); }
function escribir(p, s) { fs.writeFileSync(p, s, 'utf8'); }

/* ---------- 1. Permisos ---------- */
const manifestPath = path.join(android, 'app/src/main/AndroidManifest.xml');
let manifest = leer(manifestPath);
const permisos = [
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.SCHEDULE_EXACT_ALARM',
  'android.permission.USE_EXACT_ALARM',
  'android.permission.VIBRATE',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  'android.permission.WAKE_LOCK'
];
const faltantes = permisos.filter(p => !manifest.includes('"' + p + '"'));
if (faltantes.length) {
  const bloque = faltantes.map(p => `    <uses-permission android:name="${p}" />`).join('\n');
  manifest = manifest.replace('</manifest>', bloque + '\n</manifest>');
  escribir(manifestPath, manifest);
  hechos.push(`permisos agregados (${faltantes.length})`);
} else {
  hechos.push('permisos ya presentes');
}

/* ---------- 2 y 3. Firma y versión ---------- */
const gradlePath = path.join(android, 'app/build.gradle');
let gradle = leer(gradlePath);

const keystore = path.join(raiz, 'keystore', 'caudal.jks');
if (!fs.existsSync(keystore)) {
  console.error('::error title=Falta la llave de firma::No existe keystore/caudal.jks');
  process.exit(1);
}

if (!gradle.includes('signingConfigs')) {
  const bloqueFirma = `
    signingConfigs {
        release {
            storeFile file('../../keystore/caudal.jks')
            storePassword 'caudal2026'
            keyAlias 'caudal'
            keyPassword 'caudal2026'
        }
    }
`;
  // Se inserta justo después de abrir el bloque android { }
  gradle = gradle.replace(/android\s*\{/, match => match + bloqueFirma);
  hechos.push('configuración de firma insertada');
}

if (!gradle.includes('signingConfig signingConfigs.release')) {
  // Dentro de buildTypes { release { ... } }
  gradle = gradle.replace(
    /(buildTypes\s*\{[\s\S]*?release\s*\{)/,
    '$1\n            signingConfig signingConfigs.release'
  );
  hechos.push('release firmado');
}

// Versión: el número de ejecución del workflow sirve como versionCode creciente
const build = parseInt(process.env.GITHUB_RUN_NUMBER || '1', 10);
const pkg = JSON.parse(leer(path.join(raiz, 'package.json')));
gradle = gradle.replace(/versionCode\s+\d+/, 'versionCode ' + build);
gradle = gradle.replace(/versionName\s+"[^"]*"/, `versionName "${pkg.version}.${build}"`);
hechos.push(`versión ${pkg.version}.${build} (code ${build})`);

escribir(gradlePath, gradle);

/* ---------- 4. Ícono de la barra de notificaciones ---------- */
const origenRes = path.join(raiz, 'android-res');
const destinoRes = path.join(android, 'app/src/main/res');
let iconos = 0;
if (fs.existsSync(origenRes)) {
  for (const carpeta of fs.readdirSync(origenRes)) {
    const dir = path.join(destinoRes, carpeta);
    fs.mkdirSync(dir, { recursive: true });
    for (const archivo of fs.readdirSync(path.join(origenRes, carpeta))) {
      fs.copyFileSync(path.join(origenRes, carpeta, archivo), path.join(dir, archivo));
      iconos++;
    }
  }
}
hechos.push(`${iconos} ícono(s) de notificación copiados`);

/* ---------- 5. Componente nativo propio ---------- */
const paquete = 'com.caudal.finanzas';
const destinoJava = path.join(android, 'app/src/main/java', ...paquete.split('.'));
const origenJava = path.join(raiz, 'android-plugin');
let clases = 0;
if (fs.existsSync(origenJava)) {
  fs.mkdirSync(destinoJava, { recursive: true });
  for (const archivo of fs.readdirSync(origenJava)) {
    if (!archivo.endsWith('.java')) continue;
    fs.copyFileSync(path.join(origenJava, archivo), path.join(destinoJava, archivo));
    clases++;
  }
}
hechos.push(`${clases} clase(s) nativas instaladas`);

/* El servicio lector y los receptores viven en el manifiesto, dentro de <application> */
manifest = leer(manifestPath);
if (!manifest.includes('LectorNotificaciones')) {
  const bloque = `
        <service
            android:name=".LectorNotificaciones"
            android:label="Caudal"
            android:exported="false"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

        <receiver android:name=".AccionReceiver" android:exported="false">
            <intent-filter>
                <action android:name="com.caudal.finanzas.CONFIRMAR" />
                <action android:name="com.caudal.finanzas.DESCARTAR" />
            </intent-filter>
        </receiver>

        <receiver android:name=".ResumenReceiver" android:exported="false">
            <intent-filter>
                <action android:name="com.caudal.finanzas.RESUMEN_DIARIO" />
                <action android:name="com.caudal.finanzas.RESUMEN_SEMANAL" />
            </intent-filter>
        </receiver>

        <receiver android:name=".ArranqueReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
`;
  manifest = manifest.replace('</application>', bloque + '    </application>');
  escribir(manifestPath, manifest);
  hechos.push('servicio y receptores declarados');
}

/* Abrir un respaldo con Caudal desde el gestor de archivos: así restaurar no
   depende del selector de archivos del sistema, que en algunos teléfonos deja
   los .json sin poder seleccionarse. */
manifest = leer(manifestPath);
if (!manifest.includes('CAUDAL_ABRIR_RESPALDO')) {
  const filtros = `
            <!-- CAUDAL_ABRIR_RESPALDO -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="application/json" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/json" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
            </intent-filter>
`;
  const marca = '<category android:name="android.intent.category.LAUNCHER" />';
  const cierre = manifest.indexOf('</intent-filter>', manifest.indexOf(marca));
  if (manifest.includes(marca) && cierre > -1) {
    const corte = cierre + '</intent-filter>'.length;
    manifest = manifest.slice(0, corte) + filtros + manifest.slice(corte);
    escribir(manifestPath, manifest);
    hechos.push('abrir respaldos con la app');
  } else {
    console.log('::warning title=Manifiesto::No se pudo añadir el filtro para abrir respaldos.');
  }
}

/* Capacitor no descubre solo un plugin que vive en la propia app: hay que
   registrarlo en MainActivity antes de que arranque el puente. */
const mainPath = path.join(destinoJava, 'MainActivity.java');
if (fs.existsSync(mainPath)) {
  let main = leer(mainPath);
  if (!main.includes('CaudalPlugin.class')) {
    if (/public\s+class\s+MainActivity[^{]*\{/.test(main)) {
      main = main.replace(/(public\s+class\s+MainActivity[^{]*\{)/,
        `$1
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(CaudalPlugin.class);
        super.onCreate(savedInstanceState);
    }
`);
      escribir(mainPath, main);
      hechos.push('plugin registrado en MainActivity');
    } else {
      console.log('::warning title=MainActivity::No se pudo registrar CaudalPlugin automáticamente.');
    }
  }
} else {
  console.log('::warning title=MainActivity::No se encontró MainActivity.java en ' + destinoJava);
}

console.log('patch-android: ' + hechos.join(' · '));
