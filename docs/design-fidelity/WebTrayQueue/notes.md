# WebTrayQueue · downloads tray with a queue

Was off: icon header, close X, bordered buttons, saved rows only lightly tinted, order by time, "Stopped at" copy.
Fixed: 440px tray, 20px radius, "Downloads" 15px + "1 running · 1 waiting · 2 done · 1 failed", Clear done and "—" minimise; jobs ordered running, waiting, saved, failed; running job with purple %, 6px bar, "x of y · file N of 926 · rate · time left", "Now: …", text Pause / red Cancel; waiting job on surface-50 with "Starts when the one above finishes · Start now"; saved jobs fully green with ✓ and "Saved"; failed fully red with filled Retry and Dismiss; footer "Keeps going if you switch tabs. Closing this tab asks first."; a partial file failure now reads "Connection lost at 140 of 220 MB. Retrying picks up from there." (useDownloads).
Still different, and why:
- No "Show in folder": a web page cannot open a folder on the computer, so the button would be dead.
- The CI scene fails Kiln with a 404 ("This file is no longer on the server."), because a half-sent body could not be produced reliably in CI; the partial-failure copy is in the code but not in this shot.
- Sizes are KB/MB from the fixture; report jobs show "Report (.xlsx) · <folder>" with the folder actually used.
