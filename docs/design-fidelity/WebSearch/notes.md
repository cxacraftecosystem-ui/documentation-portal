# WebSearch · unified search

The build-side change lives on fix-0014 (agent ac290e23 owns DataSearchPanel.tsx, lib/dataSearch.ts and lib/unifiedSearch.ts). The lead asked not to merge fix-0014 here, so this branch's impl shows the pre-restyle panel until both land.
Asked of ac290e23 (applied on fix-0014: designer-portal afed54e3, documentation-portal 0c968e96): 16px form card; 48px input with the purple focus ring and "/" key; 48px Search button; pill filter triggers (Workshop ▾ …) tinted when set; This portal / Both portals segment; "38 results" + dark All pill and outline type pills; 16px result cards with 44px AUD/TOOL/PROC tiles, purple 15px titles, highlighted words, 12px meta, 36px "Play from 12:04" / "Open in Data" without icons; centred Show more; tablet bare input + pills; phone input with ⋮ Filters.
Not applied, and why: the "Workshop:" prefix inside the tinted pill and an inline ✕ (the trigger text belongs to the shared SearchableSelect; "Clear N filters" clears them); the All view keeps "Load more" (it pages every group) and a single type says "Show N more".
The capture scene stays in frontend/e2e/fidelity-build-web.spec.ts (query "indigo mud resist").
