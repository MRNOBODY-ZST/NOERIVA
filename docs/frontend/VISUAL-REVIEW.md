# Browser and visual review

Date: 2026-09-06. Selected B / Clarity, Chinese, light default. Browser plugin/browser skill was absent; regular Playwright 1.63.0 with Chromium 153 was used against real local services.

## Environment

- Console: `http://127.0.0.1:5174`
- API: `http://127.0.0.1:18081/api/v1`, explicit DEMO profile, in-memory session tokens.
- Existing unrelated applications on 5173 and 8080 were left untouched. CI uses clean 5173/8080 defaults; local overrides are configurable.
- Final production-container browser smoke: `http://127.0.0.1:18000`, CONNECTED profile, real Nginx proxy and ClickHouse heatmap query. The existing backend fixture is explicitly synthetic.
- No Playwright API response interception or frontend fallback fixtures were used.

## Evidence

- `pnpm test`: 12 tests covering unknown vs zero, rates/time formatting, request failures, memory-only Basic/Bearer auth, mutation header, denied/empty states and real ECharts SVG heatmap/native graph rendering in both themes.
- `pnpm build`: strict Vue/TypeScript check and production Vite build pass.
- `NOERIVA_E2E_PORT=5174 NOERIVA_API_PROXY=http://127.0.0.1:18081 pnpm test:e2e`: 6 successful real-backend workflows. The native graph workflow was rerun after the final fix and additionally checks immediate dark-theme button contrast ≥4.5, five native vector nodes and no image elements.
- `node tests/qa-screenshots.mjs`: overview at 390, 768, 1280, 1440 and 1920; device/table/detail/graph in light/dark; phone detail. No page-level horizontal overflow in measured views. All measured desktop device rows 44px. No captured application pageerror/console error.
- Final captures and machine-readable checks are retained in [screenshots/](screenshots/): [overview desktop](screenshots/overview-1440.png), [overview tablet](screenshots/overview-768.png), [overview phone](screenshots/overview-390.png), [device detail](screenshots/device-1440.png), [connections](screenshots/connections-1440.png), [connections dark](screenshots/connections-dark-1440.png), [device phone dark](screenshots/device-dark-390.png), and [checks](screenshots/checks.json). Captures were taken after a clean backend demo reset; no mutation workflows were rerun against that snapshot.

- `node tests/qa-connected-smoke.mjs`: final rebuilt CONNECTED console authenticated, inventory prefix search opened the existing fixture, and an actual ClickHouse heatmap response/rendered accessible table showed one 800 bit/s hour among 168 cells. No browser console/page errors or stored authorization. [Machine-readable evidence](connected-browser-smoke.json). No backend data mutations, credential logs or screenshots were produced. Initial locator assumptions (first-page asset visibility, substring search and duplicate route links) were corrected before the successful run.

## Workflows exercised

1. Login → inventory filter → device → charts → interfaces → scoped graph list → activity source dialog → keyboard global search.
2. Register asset through real POST → committed device route → UNKNOWN with no invented metrics.
3. Viewer login → write controls absent → browser stores contain no auth secret → reload requires login.
4. Phone navigation drawer → route selection/Escape → no shell overflow → dark theme.
5. Real revision-aware alert acknowledgement → backend ACKNOWLEDGED → separate from RESOLVED.
6. Native ECharts node click → inspector; zoom/fit; drag; double-click → selected device route.

## Issues found and fixed

- Invalid option closing tag caught by build.
- The missing/future heatmap series lacked a matching visualMap; ECharts raised an exception and disrupted tab rendering. Added a separate hidden piecewise mapping and an actual ECharts SVG regression test.
- At 768px a desktop sidebar compressed the ribbon and top bar. The tablet shell now uses the navigation drawer before text becomes cramped.
- Two-line identifiers made default table rows taller than the agreed 44px; cell typography/padding now preserves 44px measured height.
- Stale/dark text and control boundaries were measured and adjusted where required; details in ACCESSIBILITY-REVIEW.md.
- Parent visual review caught a transient dark-theme contrast failure and missing node images in a theme-switch capture. Computed styles reproduced white backgrounds with dark-theme text during a 150ms background-only transition. Buttons now switch foreground/background atomically. Image-based graph symbols were replaced with native ECharts vector paths, theme-specific semantic strokes and explicit state text. Graph tooltip and edge colors now use explicit theme colors. New native SVG tests failed before this fix and pass afterward; both final graph captures were regenerated and visually reviewed.
- Graph render timestamps no longer recreate graph data; series merge preserves ECharts node positions. Initial physics is bounded, with explicit controls to resume/pause it.
- Playwright selectors were scoped to visible textboxes/main content and graph selection was re-read after dragging; test locator failures are not reported as API failures.

## Limits

The six workflow tests prove the implemented frontend interacting with the explicit demo backend; the additional read-only smoke proves the rebuilt production console interacting with CONNECTED services and the pre-existing synthetic ClickHouse fixture. It does not verify real device adapters, production sizing, SSO, all domain pages, complete localization or full WCAG conformance. Backend production integration has its own evidence. Polling is implemented; a centralized SSE client is not claimed.
