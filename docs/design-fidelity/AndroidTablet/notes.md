# AndroidTablet

## Android

- Shots: impl-android, -dark at 1280×800 (density 1).
- Before: the left pane showed only the open folder's children (nothing for an artisan), with the organise card and a Back button; cards of unequal height.
- Fixed: left pane 340dp with "View data" and the downloads button, search, a Workshop/Craft/Type segmented control, and a real tree: workshops with counts, the open workshop expanded (▾, bold) with its artisans indented, the current artisan tinted #f2e9fe in purple.
- Ancestor levels are fetched once per path and cached, so the tree survives deep links.
- Right pane: record and download cards side by side at equal height, underline tabs, and the files as a two-column grid inside one card.
- Still differs: the search slot in the left pane carries the filter chips row from DataRecordSearch (fix-0014).
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
