# ReportOptions · report options and saved views

Report dialog (left of the board) is owned by agent abbb7d2 on fix-0014-report; they confirmed it matches the board (2-column sheet checkboxes with counts, Original/English/Both, Everything/Only filtered, links toggle, file name, green ready note, Remember / Cancel / Make report). impl.png shows the build on this branch.
Saved views (right of the board) was off: icon title, a grey "Save this screen as" box first, Save and copy link, rows with three bordered buttons each.
Fixed: 420px card, 20px radius, "Saved views" 16px and the board's description; rows with name, summary and Yours/Shared on the right, the chosen row fully tinted; Save current view + purple Copy link; green "✓ Link copied" (impl-saved-views.png).
Still different, and why:
- A "Save this screen as" name field and the share toggle stay, so a view gets a name; Open / Share / Remove appear on the chosen row.
- The weekly email panel (scheduled reports) sits under the list.
