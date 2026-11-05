# WebTablet · web 834px

Was off: full desktop search card with wide selects, separate Folders button row, folder card with three stacked buttons and type tiles, a separate record details card, tab icons, desktop table rows.
Fixed: header "Data" (26px) with Folders on the right; folder card with "/" breadcrumb, 22px name, "48 files · 1.24 GB · 12 transcripts", Report (.xlsx) + Download · 1.24 GB on one row, and the three at-a-glance fields (Craft, Location, Review in green) inside the card; no tiles; tabs without icons; list rows with 22px checkbox, 56×42 tags/thumbnails, stem name, "Video · 701 MB · transcript" and one Preview/Play button; selection bar "2 selected · 704 MB · Download selected" without Clear.
Still different, and why:
- The search box and filter pills belong to DataSearchPanel (restyle on fix-0014).
- Rows keep a ⋮ next to the button so Transcript and Download stay reachable; the board shows only the first action.
- The "…" (Save to a folder) button sits next to Download.
- Field values come from the fixture ("Dabu hand block printing", "Bagru, Jaipur, Rajasthan"); the board abbreviates them.
