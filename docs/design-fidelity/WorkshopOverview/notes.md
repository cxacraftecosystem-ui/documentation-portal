# WorkshopOverview · workshop totals and coverage

Was off: "0 files · 0 B" in the fixture, Card styling, filled purple Partial and bordered Missing squares, a field select for sort, pills for review, small progress bars, the caption inside the card.
Fixed: five KPI cards (28px numbers) with the purple "New since you looked +38 · Show" card; "Artisans · what's been captured" with Complete/Partial/Missing dots and a "Sort: least complete ▾" pill menu; Sections A–H with 22px squares (purple, lilac, red-tinted missing); 120px field bars red/purple/green by completeness; Media, Transcripts, Review as coloured words, Open links; caption under the card.
Still different, and why:
- The header is the folder card: "3rd Craft Toolkit Workshop", the workshop pill, type tiles and a "…" menu, instead of the board's subtitle line (place, dates, crafts); Download workshop · 7.15 GB matches.
- On a workshop folder the folder card, record details and the overview span the full 1360px width above the tree and file list, so the tree stays in reach; the search card stays above.

## Android

- Android shots: impl-android-phone, -phone-dark, -tablet (the workshop folder scrolled to the overview).
- Before: a grey tile per total, complete squares in green and partial in amber, artisan rows as grey boxes with a percentage only.
- Fixed: tiles as bordered cards with a big number and a sub-line (Files 926 / 7.15 GB, Recorded fields 87% / 418 of 480, Transcripts 112 / 104 ready · 6 translating · 2 failed, Review 9 / 12), plus the lavender "New since you looked +38" tile.
- Legend Complete / Partial / Missing; squares purple, light purple, light red with a red edge, as the board; each square opens that section.
- Artisans sorted least complete first, one row each with craft, review tint, Open, the section squares, a fields bar (red under half, purple, green when complete) with "18 / 40", and files · size · transcripts.
- Footnote "Tap a square to jump to that section."
- Still differs: phones stack the board's table columns inside each row; the web board's sort dropdown is fixed to least complete first.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.

