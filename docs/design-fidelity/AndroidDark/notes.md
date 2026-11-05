# AndroidDark

## Android

- Shots: impl-android (dark folder with a saved and a failed result), -results (scrolled to them); every other board also has a -dark shot.
- Before: dark chips and buttons used the light outline colour; results were tall cards with Share/Open and Dismiss/Retry button rows; only one finished result could show.
- Fixed: controls use the #3a3651 control line on #1e1b2e cards over #14121f; chips, primary buttons and badge are #C090F5 with #2a0f47 text (bold, as the board).
- Results are compact: "✓ Saved Bagru workshop" with Open in #86efac on #123b25/#15803d; "Kiln-firing_wide_07.mov" with Retry in #fca5a5 on #4a1717/#b91c1c. Up to three recent results show; failed single files show too.
- Review value tints #86efac; type tiles have dark variants (VID #3b1f5c/#e7d5fe, AUD #3b2a06/#fcd34d, DOC #0c2a3e/#7dd3fc).
- No side stripes anywhere; selection and playing rows use a full tint.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
