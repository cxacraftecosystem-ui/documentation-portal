# AndroidLargeText

## Android

- Shots: impl-android (folder at fontScale 2 with a download running), -download (scrolled to the progress card), -dark; every board also has a -font2 shot.
- New test ExplorerLargeTextUiTest: at fontScale 2 it walks the folder (record card, download card, progress, recent files, tabs and file rows) and fails if any Text reports visual overflow; it also checks Download folder sits above Report at full width and the select bar stacks Share under Download.
- Before: Report/Download already stacked, but file rows squeezed Save beside long names, the progress facts sat in a 2×2 grid that wrapped badly, and the select bar crowded "Select all".
- Fixed: the record card stacks its title, file count and details in one column; file rows move Save and ⋮ to a full-width row under the name; progress shows "Downloading 62%", the byte line and "File 30 of 48 · 1 min left" as separate lines with Pause and Cancel full width; the select bar stacks its buttons and "Select all" sits under the count.
- Still differs: the board shows the download card without include chips at 200%; the app keeps them (they wrap and nothing is cut).
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
