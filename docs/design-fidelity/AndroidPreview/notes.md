# AndroidPreview · Android and phone-web video preview

Board: ../boards/AndroidPreview.dc.html (390×844). Build: impl-android.png (and -dark), impl-phone-web.png.

## What was off in 0.0.13
- The video sat in a rounded card with a separate play button and slider and a one-line caption under it.
- The follow toggle was a Material chip, and .docx was a filled button.

## Fixed
- Header: the title without its extension, "Video · 701 MB · 3 of 48", and a filled Save.
- 219 dp stage: a poster until playback starts, a centred 60 dp play button, a subtitle bubble, and a time and progress row over the video.
- Transcript / English / Details tabs, and a legend with "✓ Follows video".
- The search word is marked in the lines.
- Bottom bar: ‹, .docx, Share, ›.

## Still different, and why
- Android type sizes snap to the app's scale (14 and 16 sp).
- The emulator cannot decode the fixture video, so the stage shows the board's poster state.
