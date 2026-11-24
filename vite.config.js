import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import { hiuiExternalStyles } from './build/scripts/hiui-external-styles.mjs';

export default defineConfig({
  plugins: [hiuiExternalStyles()],
  root: fileURLToPath(new URL('./src/', import.meta.url)),
  base: '/hiui/',
  build: {
    outDir: fileURLToPath(new URL('./web/hiui/', import.meta.url)),
    emptyOutDir: true,
  },
  server: {
    host: '127.0.0.1',
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
  resolve: {
    alias: {
      '@hi-ui/schema-types': fileURLToPath(new URL(
        './.local-context/hiui-design/examples/host-integration/src/shims/schema-types-empty.js',
        import.meta.url,
      )),
    },
  },
});
