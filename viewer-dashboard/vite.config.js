import { defineConfig } from 'vite';

export default defineConfig({
  root: '.',
  base: '/waspstream/',
  build: {
    outDir: 'dist',
    sourcemap: false,
    minify: 'esbuild',
  },
  server: {
    port: 3000,
    open: true,
  },
});
