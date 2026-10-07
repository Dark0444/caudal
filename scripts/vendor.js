#!/usr/bin/env node
/* Copia a www/vendor/ las librerías y fuentes que la app necesita para
   funcionar SIN internet dentro del APK. Se ejecuta en la compilación,
   después de npm install, porque esos archivos no se versionan.

   Si algo falta, no rompe la compilación: solo avisa. La app tiene
   respaldo por CDN para SheetJS, y las fuentes caen al tipo del sistema. */
const fs = require('fs');
const path = require('path');

const raiz = path.resolve(__dirname, '..');
const destino = path.join(raiz, 'www', 'vendor');
fs.mkdirSync(destino, { recursive: true });

const avisos = [];

function copiar(origenRel, nombreDestino) {
  const origen = path.join(raiz, 'node_modules', origenRel);
  if (!fs.existsSync(origen)) { avisos.push('falta ' + origenRel); return false; }
  fs.copyFileSync(origen, path.join(destino, nombreDestino));
  return true;
}

/* ---- SheetJS (respaldo en Excel) ---- */
copiar('xlsx/dist/xlsx.full.min.js', 'xlsx.full.min.js');

/* ---- Fuentes ---- */
const fuentes = [
  { paq: '@fontsource/manrope', familia: 'Manrope', pesos: [400, 500, 600, 700, 800] },
  { paq: '@fontsource/space-grotesk', familia: 'Space Grotesk', pesos: [400, 500, 600, 700] }
];

let css = '/* Fuentes locales: el APK no depende de Google Fonts */\n';
for (const f of fuentes) {
  for (const peso of f.pesos) {
    const base = f.paq.split('/')[1];
    const archivo = `${base}-latin-${peso}-normal.woff2`;
    const rel = path.join(f.paq, 'files', archivo);
    if (copiar(rel, archivo)) {
      css += `@font-face{font-family:'${f.familia}';font-style:normal;font-weight:${peso};`
           + `font-display:swap;src:url('${archivo}') format('woff2');}\n`;
    }
  }
}
fs.writeFileSync(path.join(destino, 'fuentes.css'), css, 'utf8');

const copiados = fs.readdirSync(destino);
console.log(`vendor: ${copiados.length} archivo(s) en www/vendor`);
if (avisos.length) {
  console.log('::warning title=Recursos locales incompletos::' + avisos.join(', '));
}
