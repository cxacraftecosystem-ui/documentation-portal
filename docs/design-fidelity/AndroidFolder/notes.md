# AndroidFolder

## Android

- Shots: impl-android (light), -dark, -font2, -files (list scrolled into view). Composed from ScreensShotsTest with the real ExplorerScreen and staged data.
- Before: back arrow, title "Data", search-all and reload icons in the bar; Material filter chips (8dp corners, 40dp); breadcrumb in purple links with "Repository"; review value uncoloured; 2 dp-wide FlowRow details; an extra "All transcripts" button; each file a separate card with a second row of Transcript/Preview/All formats buttons; segmented tab box.
- Fixed: header is a chevron, "View data" and a 44dp bordered downloads button with the count badge; search scrolls with the page (no sticky header).
- Organise and include chips are 999-radius pills (36/34dp), purple when on; breadcrumb is muted "All workshops › 3rd Craft Toolkit"; details are a two-column grid with Review tinted green/amber/red; "Show all 34 fields" is a flush text button.
- Download card: Report (.xlsx) outlined + Download folder filled, 48dp, 12dp corners; the transcripts zip lives only on the Transcripts tab now.
- Files are cells of one 16dp card with hairline dividers, 48dp VID/AUD/DOC tiles or photo thumbnails, one-line names without the media extension, "Video · 701 MB · transcript" and a Save button; preview, transcript and all-formats moved into a ⋮ menu on the row.
- Tabs are underline tabs (Files 48 · Tables · Transcripts 12), as on the tablet board; the phone board shows a "Files 48" card header instead, which would repeat the tab.
- Reload is pull-to-refresh; "Search everything" stays reachable from the search field (keyboard search and the "Search everything for …" button).
- Sizes read like the board: 1.24 GB, 61 MB, 0.8 MB (two decimals under 10 GB, no trailing .0 for MB/KB).
- Still differs: the search field and filter chips are DataRecordSearch/SearchFilterBar, owned by fix-0014 (agent ac290e23); they restyled them to this board on fix-0014 (afed54e3) but keep "Everything", type chips and "Filters" because their tests depend on them.
- Still differs: "New since you looked +N" shows on record folders (from the WorkshopOverview board's per-folder visit data); the folder board has no such chip.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
- New API facts appear only when the server has measured them: "Image · 3.4 MB · 4032 × 3024", "Document · 0.8 MB · 2 pages". The record card reads "48 files · 1.24 GB · 6 tools", and the Tables tab shows the number of report sheets that have rows ("Tables 7").
- In the Transcripts tab, a failed row shows the server's reason (statusMessage, e.g. "No speech found in the recording") on its full-red background.
