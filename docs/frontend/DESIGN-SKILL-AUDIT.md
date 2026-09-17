# Design source audit

Read/retrieved 2026-09-06. Source material guides original components; no third-party template markup, brand assets, commercial fonts or book content copied into product source. Latest user request authorized implementation of the confirmed B / Clarity direction, so the prior four-way concept interview was not repeated.

| Source | Actual material read / execution | Status | Decision and affected surfaces |
|---|---|---|---|
| [Awesome DESIGN.md](https://github.com/VoltAgent/awesome-design-md) | README; `design-md/ibm/DESIGN.md` palette/type/component sections; `design-md/hashicorp/DESIGN.md` palette/type/surface sections | READ, APPLIED | Original semantic tokens and flat surface discipline. Reject marketing display sizes, brand identity and per-product gradients. `DESIGN.md`, `tokens.css`, panels. Community analysis, not official console specification. |
| [Vercel guidelines](https://github.com/vercel-labs/agent-skills/tree/main/skills/web-design-guidelines) | SKILL.md and current [command.md](https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md) | READ, APPLIED | Semantic links/buttons, labels, focus, bounded tables, responsive drawer, theme color, noncolor statuses and source errors. No installation claimed; applied source workflow. Chinese copy and 14px density governed by G01. |
| [diguike book](https://github.com/diguike/book-claude-plugins) | `01-dev-tools/05-feature-dev.md`, `01-dev-tools/11-playground.md`, `04-domain-plugins/01-frontend-design.md` | READ, APPLIED | Adapted exploration → implementation → review; not a plugin invocation. Existing design choice preserved. The old playground is historical; this deliverable is a real Vue client. CC BY-NC-SA book prose not copied. |
| [Anthropic frontend-design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md) | Current primary skill | READ, APPLIED | Infrastructure vocabulary, restrained hierarchy and task-specific charts. Reject decorative marketing patterns; retain user-selected Clarity. |
| [UI/UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | SKILL.md; `references/quick-reference.md` via equivalent base template; `references/pro-rules.md`; inspected local search script and imports; design-system / Vue / chart queries | READ, SCRIPT_EXECUTED, APPLIED | Temporary sparse checkout commit `314307f156aeab0c6b567bbaa1ce4e7aabd5a636`. Minimal enterprise direction aligns; reject generated orange CTA and remote font pairing because selected tokens govern. Chart result supports lines and accessible tables. Initial Vue table-keyboard query had zero matches and was retried with accessibility; fallback is native semantics / Vercel rules where no match. No generated MASTER persisted. |
| [StyleKit sidebar](https://www.stylekit.top/zh/styles/sidebar-fixed) | Retrieval attempted; web tool returned Internal Error | BLOCKED | Use supplied G/F fixed-sidebar contract: persistent desktop, mobile modal drawer, restrained geometry. No fresh external verification claim. |
| `frontend-tailwind-css` installed skill | SKILL.md; application-ui guide; searched sidebar/table; read sidebar-with-header and sortable-table patterns | READ, APPLIED, SCRIPT_EXECUTED | Original grid/sidebar/table components; no template copying because underlying block rights are not established by public availability. Tailwind 4.3.3 integration independently pinned. |
| Frontend testing/debugging | Installed SKILL.md | READ, APPLIED | Browser plugin and its browser skill absent from this session; regular Playwright chosen. Real API, no route interception/fake responses. |

## Execution evidence

- `tailwind_templates.py search --query sidebar --product application-ui --target-compliant`
- `tailwind_templates.py search --query table --product application-ui --target-compliant`
- UIUX local verified `search.py 'infrastructure operations console' --design-system -p NOERIVA`
- `search.py 'data table keyboard navigation' --stack vue` returned no match; retry `search.py 'accessibility' --stack vue`.
- `search.py 'time series missing data' --domain chart` returned Trend Over Time, Anomaly Detection, Time-Series Forecast; only the trend/accessibility principles were applicable.

## Review deltas

- `src/App.vue`: corrected malformed option closing tag caught by production build.
- `src/stores/preferences.ts`: synchronized browser theme metadata with light/dark tokens.
- `src/styles/base.css`: added dialog overscroll containment, background scroll lock and touch control sizing.
- `src/components/BandwidthHeatmap.vue`: equalized plotting margins so the plot, not merely its outer wrapper, is square.
- Browser/contrast results and remaining gaps are recorded in VISUAL-REVIEW.md and ACCESSIBILITY-REVIEW.md. `REVIEW_PASSED` is not inferred from this table.
