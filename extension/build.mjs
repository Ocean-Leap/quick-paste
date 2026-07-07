// Bundles the extension's TS entrypoints and copies static assets into dist/.
// dist/ is what you load unpacked in chrome://extensions.
import { build, context } from 'esbuild';
import { cp, mkdir, rm } from 'node:fs/promises';

const watch = process.argv.includes('--watch');
const outdir = 'dist';

await rm(outdir, { recursive: true, force: true });
await mkdir(outdir, { recursive: true });

// Static files copied verbatim (manifest, HTML shells, CSS, icons).
const staticCopies = [
  ['src/manifest.json', `${outdir}/manifest.json`],
  ['src/popup.html', `${outdir}/popup.html`],
  ['src/options.html', `${outdir}/options.html`],
  ['src/offscreen.html', `${outdir}/offscreen.html`],
  ['src/styles.css', `${outdir}/styles.css`],
  ['public', `${outdir}/public`],
];

async function copyStatic() {
  for (const [from, to] of staticCopies) {
    await cp(from, to, { recursive: true });
  }
}

const options = {
  entryPoints: {
    background: 'src/background.ts',
    popup: 'src/popup.ts',
    options: 'src/options.ts',
    offscreen: 'src/offscreen.ts',
  },
  bundle: true,
  format: 'esm',
  target: 'chrome116',
  outdir,
  sourcemap: watch ? 'inline' : false,
  logLevel: 'info',
};

if (watch) {
  const ctx = await context(options);
  await copyStatic();
  await ctx.watch();
  console.log('watching…');
} else {
  await build(options);
  await copyStatic();
  console.log('built → dist/');
}
