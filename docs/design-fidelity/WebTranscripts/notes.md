# WebTranscripts · transcripts tab

Was off: icons on buttons, status pills purple, failed row pill bordered, small "Show all" footer button, file names with extensions, a 25-row first page.
Fixed: crumb "3rd Craft Toolkit Workshop › Giriraj Prasad Chhipa", 24px "Transcripts", Export all (.docx) + purple Transcripts (.zip) · 12; 520px search, dark All pill and outline Ready/Translating/Failed pills, Language ▾ pill menu; table card with 44 / 1fr / 140 / 160 / 220 columns, titles "Interview · Dabu mud resist · Take 1", "Marwari → English · 4 speakers", ✓ Ready green, Translating 70% amber with a 4px bar and "Transcribed · translating to English", failed row fully red with white tag and red Try again, Read / .docx / Read original; "Showing 5 of 12 · Show all".
Still different, and why:
- The tab strip sits above the header (it is the app's one tab strip) instead of under it.
- The failed row reads "The transcript couldn't be made." The batch API sends no reason ("No speech found…").
- No quote snippet on the first row without a search: the list API sends snippets only for a search.
- "close-up" becomes "close up" in the prettified title.
