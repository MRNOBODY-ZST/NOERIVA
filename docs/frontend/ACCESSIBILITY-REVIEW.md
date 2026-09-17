# Accessibility review — first implemented slice

Target: WCAG 2.2 AA. Review date: 2026-09-06. This is a scoped implementation review, not a claim of a complete WCAG conformance audit.

## Verified

- Semantic navigation links and action buttons; Chinese heading hierarchy and main skip link.
- Every form control has a visible label or accessible name. Login supports password managers and paste. Auth fields are cleared after the request; session secrets are not persisted.
- Native modal dialogs provide modal focus/escape behavior; mobile navigation closes on route selection, Escape and backdrop click. Background scrolling is locked while a dialog is open.
- Keyboard command search supports Ctrl/Cmd+K, arrows, Enter and Escape. Graph list alternatives and explicit zoom/fit controls avoid gesture-only access.
- Health, availability, source freshness and unknown states use explicit labels. A permission failure is not rendered as an empty result; unit/component tests assert this distinction.
- ECharts trends and heatmaps expose data tables, missing samples remain gaps, and graph relationships expose a list and source inspector.
- Visible focus outlines, reduced-motion CSS and graph behavior. Initial graph settling stops after 1.8 seconds; explicit physical simulation can be enabled or fixed by the operator.
- Actual device table row heights measured at 44px. Touch controls use 44px targets; table regions retain accessible horizontal scrolling instead of hidden columns.
- Browser tests exercised desktop and phone navigation, drawers, search, source dialogs, theme switching, role-dependent actions and native graph gestures.

## Measured contrast

WCAG relative-luminance formula; ratios rounded to two decimals. These measurements cover the named tokens against their actual reference surfaces, not every hypothetical combination.

| Foreground | Light surface ratio | Dark surface ratio |
|---|---:|---:|
| Primary text | 15.46 | 13.44 |
| Secondary text | 5.99 | 8.48 |
| Accent / links | 6.47 | 8.00 |
| Healthy text | 5.75 | 8.99 |
| Warning text | 5.32 | 9.72 |
| Critical text | 5.84 | 8.77 |

Final auxiliary text `#607187` against canvas `#f3f6fa`: **4.60:1**. The original faint token measured 4.36 and was darkened. Input/control boundary `#8091a5` against white: **3.23:1**. Dark control boundary `#667d98` against `#172331`: **3.75:1**. Warning text against warning surface: **4.96:1**.

## Remaining scope

- Full VoiceOver/NVDA walkthrough, Windows high-contrast mode, 200%/400% text zoom and browser matrix remain unverified.
- Current visible interface is Chinese; complete message-catalog internationalization is not implemented in this slice.
- Graph edge detail currently follows the selected node and adjacent relationships; there is no graph editor or freeform keyboard positioning workflow.
- The API enforces permissions; visibility of controls is not considered an authorization boundary.

## Graph theme regression review

The final review found a 150ms background-only button transition that momentarily paired dark-theme foreground text with a white background. Removed that transition; a real-browser test now measures all graph toolbar/inspector button text contrast immediately after theme switching and requires at least 4.5:1. Native vector node outlines use theme-aware healthy/warning/critical/unknown colors, with explicit type and health labels. Node images no longer disappear during decoding; graph edges are fully opaque. This correction does not expand the overall WCAG conformance claim.
