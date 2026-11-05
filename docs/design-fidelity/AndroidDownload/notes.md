# AndroidDownload

## Android

- Shots: impl-android, -dark, -font2 (folder scrolled to the running download).
- Before: progress card title in the display face, Material bars, facts as two loose rows, Now panel and note with 8dp corners, pill-shaped Pause/Cancel; recent rows 11-12sp with no row dividers.
- Fixed: card 16dp radius with the #d6b6fb border and soft shadow, 15sp bold title, 22sp-class percent, 10dp bar on the #d9c4e9 track, 2×2 Downloaded/Files/Speed/Time left grid, Now panel (12dp radius, own #9148d2 bar, "266 of 701 MB"), amber note radius 10, Pause outlined and Cancel outlined in #fecaca/#b91c1c, 48dp, 12dp corners.
- Sizes read "4.43 of 7.15 GB" and "266 of 701 MB" (unit once when both sides share it).
- Recent files: header with a hairline and "All 926", rows 13sp with 22dp status circles; saved rows fully #dcfce7 with #bbf7d0-like dividers, retrying fully red, the running file neutral with its percent; oldest at the top, the current file last.
- "Saving to Downloads/DesignWorkshop/…" centred under the cards.
- Still differs: the board's bar has no downloads button while a download runs; the app keeps it so the Downloads sheet stays one tap away.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
