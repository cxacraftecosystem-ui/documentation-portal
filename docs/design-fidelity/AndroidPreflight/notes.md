# AndroidPreflight

## Android

- Shots: impl-android, -dark, -font2. PreflightBody is the real sheet content, framed at the board's size over the board's scrim.
- Before: Material sheet, grey-filled rows, Material switches, a thin progress bar for storage, Not now / Download at equal width, an extra "Keeps going" line.
- Fixed: "Download 3rd Craft Toolkit", "926 files · 7.15 GB · about 20 min on this mobile data"; amber mobile-data row with an icon; white rows with 14dp radius and hairline border; 44×26 switches.
- Storage bar shows used (#a7a3bc), this download (purple) and free; "Enough space" in green.
- Save to with a Change link; Smaller download (skip videos · size · time); Not now 1 : Download on Wi-Fi 2, 52dp, 14dp corners; footnote centred.
- Phone storage total now comes from StatFs so the bar shows the used share.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
