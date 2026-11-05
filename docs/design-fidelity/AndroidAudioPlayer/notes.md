# AndroidAudioPlayer · mini player, lock screen and notification

Board: ../boards/AndroidAudioPlayer.dc.html. Build: impl-android.png (mini player), impl-android-notification.png (the real notification shade on the emulator), impl-phone-web.png. 0.0.13: before-*.png.

## What was off in 0.0.13
- The Android mini player was a light card with a purple ♪ tile and the file name, with back, play and close all showing.
- The web mini player was a light card with a music icon and forward, expand and close buttons.

## Fixed
- Both mini players now follow the board:
  - Dark card (#1e1b2e, 18 px radius) with a 3 px progress bar.
  - Amber ♪ tile, title, and the spoken English line as `Sunita: "…"`.
  - ↺10 and a lilac play/pause button. ✕ shows only while paused.
- Web: closing the preview while a recording plays carries it on in the mini player. Media Session metadata carries the title and the current line.
- Android media session: the title is the recording title and the subtitle is the current spoken line. Back 10 s and forward 10 s buttons now appear on the notification and lock screen.
- The playing file row is tinted and reads "Playing · 18:20 of 41:02". The folder rows are on the web and Android board branches.

## Still different, and why
- Android draws the lock screen and the notification, not the app. The board's layout, colours and quote box cannot be set by an app; the app controls only the text and the buttons. The capture is the real shade.
- The emulator capture shows the notification shade, not the lock screen. One media session feeds both.
