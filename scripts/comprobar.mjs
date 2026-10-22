/* Comprueba la coherencia de los números con datos reales, en un navegador de
   verdad, antes de publicar. Nació después de tres errores seguidos del mismo
   tipo: clasificar un movimiento de una forma nueva y romper el total en otra
   pantalla sin notarlo.

   Uso:  node scripts/comprobar.mjs <respaldo.json> [...]
   Sale con código 1 si alguna cuenta no cuadra, para que no se publique.      */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const archivos = process.argv.slice(2);
if (!archivos.length) {
  console.error('Falta el respaldo: node scripts/comprobar.mjs datos.json');
  process.exit(2);
}

const pagina = 'file://' + path.resolve('www/index.html');
/* En el runner de GitHub, Playwright trae su propio Chromium y no hay que
   decirle dónde está; en el entorno local sí. */
const navegador = await chromium.launch(
  process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}
);
let malos = 0;

for (const archivo of archivos) {
  const datos = JSON.parse(fs.readFileSync(archivo, 'utf8'));
  const p = await navegador.newPage({ viewport: { width: 412, height: 915 } });
  const errores = [];
  p.on('pageerror', e => errores.push('error de página: ' + e.message));

  await p.addInitScript(d => localStorage.setItem('nebula.finanzas.v1', JSON.stringify(d)), datos);
  await p.goto(pagina);
  await p.waitForTimeout(1800);

  const fallos = await p.evaluate(() => {
    try { return window.comprobarCoherencia(); }
    catch (e) { return ['no se pudo comprobar: ' + e.message]; }
  });

  const todos = errores.concat(fallos);
  const nombre = path.basename(archivo);
  /* Un caso llamado "debe-fallar-…" existe para comprobar que la comprobación
     sirve: si ESE pasa limpio, la red tiene un agujero. */
  const esperaFallo = nombre.startsWith('debe-fallar-');
  const bien = esperaFallo ? todos.length > 0 : todos.length === 0;

  console.log(`\n${nombre}: ${bien ? '✓' : '✗'} ${
    esperaFallo
      ? (todos.length ? 'detecta el problema, como debe' : 'NO detectó el problema que debía detectar')
      : (todos.length ? todos.length + ' problema(s)' : 'cuadra')}`);
  if (!esperaFallo || !bien) todos.forEach(f => console.log('   - ' + f));
  if (!bien) malos++;
  await p.close();
}

await navegador.close();
console.log(malos ? `\n${malos} archivo(s) con problemas.` : '\nTodo cuadra.');
process.exit(malos ? 1 : 0);
