# WebAudio · web audio preview

Board: ../boards/WebAudio.dc.html (1440×900). Build: impl.png, impl-dark.png, impl-tablet.png. 0.0.13: before.png, before-dark.png, before-tablet.png. Side by side: compare*.png.

## What was off in 0.0.13
- The dialog was taller than the screen, so its header was cut off.
- The transcript was squeezed into a 26rem side column, and the wide left column held only the player and empty space.
- No header row: no AUD tile and no prev/next beside the title. Save and Open in record sat in a bottom row with Copy and Share.
- The waveform was a thin strip with hit dots above it and no playhead. An extra minimise button sat beside Speed and Clear voices.
- Speaker colours were blue, amber, green and pink instead of the board's purple, teal, amber and pink.
- The current find hit was drawn with a ring.

## Fixed
- Board layout:
  - Header: ‹, AUD tile, title and meta, ›, Open in record, Save · size, ✕.
  - Gradient player band: ↺10, 56 px play button, 10↻, and a 72 px waveform with a playhead and amber hit bars. Under it, the time and "N matches"; Speed and ✓ Clear voices are stacked on the right.
  - Speaker lanes.
  - Wide transcript in three columns (time, speaker, text). The current line is tinted, with no stripe.
  - 300 px side panel: tabs, find "N of M" with ↑ ↓, Speakers with talk time, the follow note, and .docx, .srt and copy-line buttons stacked at the bottom.
- The speaker palette now matches the boards: s0 #762CB1, s1 #0F766E, s2 #B45309, s3 #BE185D. Changed in shared/transcript-contract.json, on web and on Android.
- Closing while a recording plays hands it to the mini player, which replaces the extra minimise button.
- The transcript opens with the hit two lines from the top, as on the board. Search marks are plain amber, as on the board.
- Dates read "22 Sep 2026".
- Tablet (834): the side panel is 260 px and the speaker name stacks above the text.

## Still different, and why
- The waveform fills the width of the band. The board's mock has 64 fixed bars that stop a third of the way across, and a waveform that does not span the seek width cannot map a click to a time.
- Talk times and lanes come from the fixture's turns, not the board's illustrative 6/18/11/4 minutes.
- The overlay tint is the app dialog's (45 %) instead of the board's 55 %, because FieldDialog is shared by every dialog in the app.
- The global nav behind the dialog belongs to each portal.
