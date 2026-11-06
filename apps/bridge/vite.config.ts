import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: './',
  build: {
    outDir: fileURLToPath(new URL('../mobile/android/app/src/main/assets/bridge', import.meta.url)),
    emptyOutDir: true,
    target: 'es2022',
    sourcemap: false,
    minify: 'esbuild',
    rollupOptions: { input: fileURLToPath(new URL('index.html', import.meta.url)) }
  }
});
