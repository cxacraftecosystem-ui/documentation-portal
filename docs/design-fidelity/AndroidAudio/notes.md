# AndroidAudio · Android and phone-web audio preview

Board: ../boards/AndroidAudio.dc.html (390×844). Build: impl-android.png (and -dark, -font2, -tablet), impl-phone-web.png (and -dark). 0.0.13: before-*.png.

## What was off in 0.0.13
- Android:
  - The title was the raw file name, the meta carried "transcript" and the date, and Save was an outlined button.
  - The tabs sat above the player, and the waveform sat on the page instead of in a card.
  - Clear voices was a full-width settings toggle with a description.
  - The speaker chips were grey, and the .docx button was a filled purple pill.
- Phone web: the desktop dialog was squeezed onto the phone.

## Fixed (both)
- Header: ✕, the title "Section D · Materials · Group of 3", the meta "Audio · 14.2 MB · 41:02 · 7 of 48", and a filled Save button.
- Gradient card with a 20 px radius:
  - 48 dp waveform with played, unplayed and amber hit bars and a playhead.
  - "18:20 · Sunita speaking · 41:02".
  - Speed pill, ↺10, 64 dp play button, 10↻, and a "✓ Clear" pill.
- Tabs: Marwari / English / Find ⌕. Find opens a find field with "N of M".
- Tinted speaker chips.
- The current line is tinted and the search word is marked.
- Bottom bar: ‹, Transcript (.docx), Share, ›.
- On Android, the preview receives the search word from the search screen and opens with the current line one row from the top.
- At font scale 2, Speed and Clear move to a second row and nothing truncates.
- On tablets the preview keeps a 760 dp reading width, centred.

## Still different, and why
- Android text snaps to the app's type scale, which TypeScaleGuardTest enforces: 15 px becomes 16 sp and 13 px becomes 14 sp.
- Android draws ↺ and ↻ in the system font, so the arrow looks smaller than in the board's Inter.
- Phone web shows find state "1 of 4" in the capture; the board shows no counter.
