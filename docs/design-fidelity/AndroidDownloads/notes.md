# AndroidDownloads

## Android

- Shots: impl-android, -dark, -font2. The Downloads sheet body is the real DownloadsSheetBody; the three notification cards above it are a Compose rendering of the real notifications (title, text, big text and action labels read from ExplorerNotifications.progress(), finishedTitle()/finishedText()), because system notifications cannot be captured inside a Compose test.
- Before: Material sheet (28dp corners, 32dp handle), every job card with full-width button rows (Cancel/Pause, Dismiss/Retry), report and single files with Share + Open rows.
- Fixed: 24dp sheet corners and a 40×4 hairline handle (also on the real ModalBottomSheet), "Downloads" title, cards 14dp radius.
- Running card: title + purple percent, 6dp bar, "4.43 of 7.15 GB · 574 of 926 files · 7 min left".
- Saved folder: fully green card, "✓ Saved", "47 files · 1.23 GB · Downloads/DesignWorkshop/", Share (white, green border) + Open (#15803d).
- Failed: fully red row with the reason and a red Retry button; report/single files: one compact row "✓ Report (.xlsx)" with Open.
- Still differs: running cards keep small Pause and Cancel text actions (the board shows none, but the sheet is where selection and report downloads are controlled). "Clear finished" shows only when two or more jobs are done.
- Notifications use the portal name (Design Workshop here, Field Repository in FR).
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
