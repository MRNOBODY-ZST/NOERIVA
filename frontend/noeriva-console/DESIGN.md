# NOERIVA · 澄观 — B / Clarity

Implementation baseline: 2026-09-06. The current implementation request authorizes the selected visual direction. Exact navigation/tab details below are implementation choices carried from the previous proposal, not retroactive claims that the previous review froze them.

1. **Atmosphere.** Calm Chinese operational workstation. The first viewport establishes scope, critical situations, freshness and a useful next step.
2. **Palette.** Cool-white canvas `#f3f6fa`, white surface, ink `#172538`, muted `#536579`, blue accent `#255ea8`. Dark tokens separately define every surface, border and state. Green/red/amber indicate labelled meanings only.
3. **Typography.** System Chinese/sans stack, 14px body, at least 12px supporting text, 24px title. Technical identifiers and timestamps use tabular numerals; monospace only where it improves scanning. No remote fonts.
4. **Components.** Semantic links/buttons, labelled fields, visible keyboard focus, loading/disabled/error states. Primary actions remain scarce. Errors expose recovery and request IDs without stack traces.
5. **Layout.** 256px persistent sidebar, 72px collapsed. Top bar 60px, broad work area, no oversized hero. Operations, devices, anomalies, investigation; system below. Device detail retains context while changing tabs.
6. **Depth.** 1px boundaries and quiet surfaces. Floating dialogs use modest shadow. No nested card stacks.
7. **Do / don't.** Preserve evidence and missingness; never display absent telemetry as healthy zero. No gradients, glass, oversized type, pill-heavy controls, invented causal certainty or fake functionality.
8. **Responsive.** Sidebar becomes modal drawer below 1024px; secondary columns stack. Tables remain labelled horizontal scroll regions. Phone charts retain accessible tabular alternatives.
9. **Implementation.** Vue Composition API and strict TypeScript; route-level lazy pages; Tailwind 4 plus documented CSS tokens; original UI primitives. Real API errors remain visible. No frontend fixture fallback.
10. **Density.** Default rows 44px, optional compact 38px. Touch actions at least 44px. Density is token-based.
11. **Status.** Health, availability and freshness are independent fields. Unknown, stale, partial, offline, denied and unsupported have explicit text and noncolor cues.
12. **Tables/charts.** Sort/filter assets; source-aware native ECharts trends; missing samples create gaps; every chart exposes tabular values or accessible list/summary. Rate units are decimal bit/s.
13. **Topology.** ECharts force graph, scoped neighbors, draggable nodes, zoom/fit, click inspector, double-click details plus explicit buttons and device list alternative. Structure and metric updates remain separate.
14. **Evidence.** Event sources and timestamps visible; observed evidence separate from operator decisions. No simulated signature/WORM claims.
15. **Themes.** Light default, complete dark palette, matching color-scheme. Theme preference alone may persist; credentials never do.
16. **Accessibility.** WCAG 2.2 AA target: semantic landmarks, skip link, focus restoration/trapping, keyboard operation, visible labels, noncolor status, reduced motion, chart alternatives. Validation recorded separately.
17. **Motion.** Theme colors switch atomically to preserve contrast. Force simulation respects reduced motion. No continuous decorative animation.
18. **Review.** Build/typecheck and critical unit/component tests; browser interaction and responsive screenshot checks; separate frontend implementation from backend/device verification.

## Time and capabilities

The console uses the timezone returned by the API / selected explicitly for a query. UTC is a visible initial selector choice, not a claim about the user's workspace. The heatmap covers seven local dates through the selected date and 24 hour intervals. Zero, missing, future and partial intervals are distinguishable. DST behavior is owned by the backend contract.

## Elevation order

Content 0; sticky table 10; top bar 20; sidebar 30; drawer backdrop 40; drawer 50; modal/command palette 60; notifications 70.
