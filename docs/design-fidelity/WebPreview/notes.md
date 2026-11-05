# WebPreview · web video preview

Board: ../boards/WebPreview.dc.html (1440×900). Build: impl.png, impl-dark.png. 0.0.13: before.png.

## What was off in 0.0.13
- It used the same dialog as audio: the header overflowed the screen and the transcript sat in a narrow column.
- The browser's own video controls, with no subtitle bubble style, no search ticks on the timeline, and no CC or full-screen control.
- Actions sat in a footer row; there was no Save · size in the header.

## Fixed
- Two-column grid. The header runs across both columns.
- Dark video stage: a poster and a centred play button until the first frame, a live subtitle bubble, and a control bar with play, time, progress with yellow hit ticks, duration, speed, CC and full screen.
- 440 px side panel:
  - Transcript / English / Details tabs.
  - Find with an icon and "N of M".
  - Speaker legend with "✓ Follows playback".
  - The lines, with the current one tinted.
  - Footer with .docx, .srt and Copy.
- The meta line reads like the board: type, size, length, artisan, date, file N of M.

## Still different, and why
- The board shows English sentences under "Transcript". The fixture's original language is Hindi, as on WebTranscripts, so the capture shows the English tab to reproduce the "2 of 3" find state.
- The capture says "file 1 of 48" where the board says 3. The fixture orders files as on the Main board, and the boards disagree on the order.
- Find has ↑ ↓ step buttons, as on WebAudio. WebPreview omits them, but stepping with a mouse needs them.
