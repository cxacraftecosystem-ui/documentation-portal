# States · loading, empty, no results, offline, couldn't load (web)

Was off: grey skeleton bars, a round icon empty state with the generic body, a red bar for load errors with the server message, a plain amber offline line.
Fixed: skeleton shaped like the page (70%/50% bars, two 40px blocks, three 44px rows) in the board's soft gradient; empty folder with a 56px purple tile, 18px "Nothing recorded here yet", "Files appear as soon as someone records for <folder>." and Record now; couldn't-load card fully red with "!" tile, "This folder didn’t load", "Trying again in 8 s. Your selection and filters are kept." and filled Try now; offline banner amber (#fef3c7 / #fcd34d) "You're offline. Showing what was loaded … Downloads pause and pick up again by themselves when you're back online."
Still different, and why:
- Each state is captured inside the page (impl-loading/empty/noresults/offline/error.png) rather than as five cards side by side.
- Offline on the web is a banner over kept data; the board's card is labelled Android.
- No results is DataSearchPanel (agent ac290e23, fix-0014). Agreed 2026-11-06: ac290e23 builds it end to end; /search/unified returns `suggestionTotal` and the panel renders "Did you mean indigo? 38 results." ("1 result.", nothing at 0) on web and Android (designer-portal b52643b5, documentation-portal 7ff7a542). This branch's fixture seeds suggestionTotal 38, so the shot shows it once fix-0014 lands.
- The board's caption in the loading card is annotation, not product copy.

## Android

- Android shots: impl-android-loading, -empty, -noresults, -offline, -error, each with a -dark variant (noresults is light only) at phone size, composed in the real ExplorerScreen (HANG/FAIL fakes, an empty artisan, offline with a 14-minute-old cache).
- Before: state cards with 24dp corners, 16sp titles, 14sp body, pill buttons, empty icon on white, and the offline banner shown twice (card and bottom strip).
- Fixed: 16dp cards, 56dp icon tile (lavender for empty, white on amber/red), 18sp-class bold title, 13-14sp body, 44dp buttons with 12dp corners.
- Offline shows the cached data's age ("Showing what was loaded 14 min ago") and "Saved files still open"; the bottom strip only appears when there is no cached copy.
- Couldn't load counts down ("Trying again in 8 s"), offers Try now, and keeps selection and filters.
- Loading skeleton follows the page: record card bars and three file rows with tiles.
- No results is the unified search's state (fix-0014); there is no result count after "Did you mean" because the API returns none for the suggestion.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.

