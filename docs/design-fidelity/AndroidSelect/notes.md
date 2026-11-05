# AndroidSelect · phone web multi-select

Was off: no select mode on phone web; checkboxes always hidden, Select all in the bar, a single-line bar.
Fixed: long-press enters selection; a lilac top bar "✕ · 3 selected · 719 MB · long-press or tap boxes · Select all 48"; dark All pill and type pills; rows with 24px checkboxes and tinted checked rows; dark bottom bar "1 video · 1 image · 1 audio · + 2 transcripts" with Share and "Download 3 · 719 MB".
Still different, and why:
- Type pills come from the folder (Images 22, Videos 4, Audio 14, Documents 8), not the board's sample counts.
- Share uses the system share sheet when the browser has one, otherwise copies the link ("Link copied").
- Rows keep ⋮ for their actions.

## Android

- Shots: impl-android, -dark, -font2 (long-press the video, tick the image and the audio).
- Before: plain top bar, Material checkboxes, each file a separate card, cards above the list still showing, bottom bar white with a pill Download button.
- Fixed: lavender bar (#f2e9fe, #d6b6fb rule) with ✕, "3 selected", "718 MB · long-press or tap boxes", "Select all 48".
- While selecting the folder cards step aside so the type pills (All 48 ink pill, Videos 9, Images 31, Audio 6, Documents 2) and the list lead.
- Rows: 24dp rounded boxes (#a7a3bc border, purple when ticked), 44dp tiles, selected rows fully #faf5ff.
- Bottom bar: dark #1e1b2e card with 20dp corners and shadow, "1 video · 1 image · 1 audio · + 2 transcripts", Share outlined and "Download 3 · 718 MB" in #c090f5/#2a0f47 at 1:2 width; stacked full width at double text size.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.

