# WebTables · web tables and report states

Was off: icons on buttons, colour dots in the sheet list, a "#" row-number column, plain review text, a percent-less progress card, a small red Problem bar.
Fixed: 18px folder title and "7 tables · built 2 min ago · updates when the folder changes"; 320px search box, This table (.csv) and Full report (.xlsx) without icons; 240px sheet list with "3 of 120" counts and full-tint selection; pill "3 rows match “indigo”" + "across all 120 tools, not just the preview"; table head uppercase on surface-50, first column bold, Review values as green/amber pills; "Full report (.xlsx) is being prepared · 7 sheets · The file downloads by itself when it’s ready." dashed card with a 200px bar; red error card "The report couldn't be built. Nothing was saved. We’ll try again automatically in N s." + white Try now (impl-error.png).
Still different, and why:
- Tables and Transcripts now use the full 1360px width: the tree folds away and a Folders button in the page header brings it back beside the content (choosing a folder there keeps it open). Workshop folders put their card and overview full width above the tree.
- The fixture's Tools sheet has 11 columns (the phone board says "All 11 columns"), so the desktop table shows more than the board's five.
- "Tables 7" on the tab now comes from /data/report/sheets (sheets with rows); the number appears once known.
- The board's "Example error state:" label is board annotation, not product copy.
- The minimised download card stays visible bottom-right while the report runs.
