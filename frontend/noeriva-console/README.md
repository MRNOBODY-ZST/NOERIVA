# NOERIVA Console

Vue 3 + TypeScript + Vite, TanStack Vue Query, Pinia, Apache ECharts and Tailwind CSS. This client calls the real `/api/v1` API. It contains no browser fake-response fallback.

## Local run

Node 24 and pnpm 11.19.0 (exact dependency versions and lockfile committed).

```sh
pnpm install --frozen-lockfile
NOERIVA_API_PROXY=http://127.0.0.1:18081 pnpm dev --port 5174
```

Start the separately provided API DEMO profile on 18081. Standard proxy default is 8080 and Vite default is 5173; explicit alternate local ports avoid conflicts with other applications. Demo credentials come from the backend demo configuration (`admin / noeriva-local-demo`, or `viewer / noeriva-local-demo`); connected profiles do not use a client-supplied default secret.

The client exchanges Basic login for a Bearer session when supplied by `/session`. Authorization is retained only in module memory; reload requires another login. Only the non-sensitive theme preference is persisted.

## Verification

```sh
pnpm test
pnpm build
pnpm exec playwright install chromium
NOERIVA_E2E_PORT=5174 NOERIVA_API_PROXY=http://127.0.0.1:18081 pnpm test:e2e
```

Playwright builds and serves production assets with Vite preview on an isolated loopback port; it does not reuse an existing development server. `NOERIVA_API_PROXY` is inherited by preview. The real API must already be running. E2E tests create demo assets and acknowledge demo alerts. Restarting the in-memory DEMO profile restores clean fixtures. `tests/qa-screenshots.mjs` captures responsive visual evidence without intercepting API responses.

For the local CONNECTED Compose stack on port 18000, `node tests/qa-connected-smoke.mjs` reads the ignored root `.env` credential in memory and the existing rollup fixture metadata, then checks inventory, the rendered 800 bit/s heatmap, and browser integrity. It creates no backend data and records no credentials.

`DESIGN.md` is the canonical visual contract. `../../docs/frontend/` records page scope, source audit, accessibility and verification evidence. Polling is explicitly shown at 20 seconds; no fake live stream is claimed.
