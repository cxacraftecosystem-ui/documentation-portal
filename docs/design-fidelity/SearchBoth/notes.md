# SearchBoth · both-portals search (phone web)

The build-side change lives on fix-0014 (agent ac290e23, DataSearchPanel.tsx), applied in designer-portal afed54e3 and documentation-portal 0c968e96; not merged here on the lead's instruction.
Asked: 52px search with ✕; full-width This app / Both portals segment; per-portal count pills (Design Workshop purple, Field Repository teal #ccfbf1 / #115e59, with dots); cards with the portal badge, "Transcript · 03:12", title, highlighted snippet, two 44px buttons "Play from 03:12" / "Open in Field Repository ↗"; the same artisan in both portals as one dashed card with DW + FR badges and "Same person in both portals · 48 files here, 31 in Field Repository"; footer "Opening a Field Repository result signs you in there if you have access."
Not applied: card titles stay purple-700 (as on desktop) where the board shows ink.
The capture scene stays in frontend/e2e/fidelity-build-web.spec.ts (query "indigo vat", Both portals).

## Android

- Shots: impl-android, -dark (scope switched to Both portals).
- Ownership as AndroidSearch: fix-0014 applied the deviation list (portal count pills, a DW/FR badge on every card, "Open in Field Repository ↗", the dashed merged-artisan card, and the footnote DataSearchText.footnote).
- Deviation they kept: the dot stays the existing "●" in the badge strings; the DW badge uses the app's mark token #f2e9fe.

