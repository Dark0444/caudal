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

console.log('patch-android: ' + hechos.join(' · '));
