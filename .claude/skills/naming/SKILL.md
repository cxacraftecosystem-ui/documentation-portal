---
name: naming
description: The one naming convention for designer-portal (DesignWorkshop) and documentation-portal (FieldRepository). Load BEFORE generating any name for data in either portal, on backend, web, Android, workflows or scripts. That covers S3 object keys, uploaded or captured file names (recordings, photos, videos, identity cards), download and export file names (xlsx, csv, pdf, docx, zip, vtt, txt), reports, backups, APK and release names, and generated interview, session or workshop titles. Also load it when reviewing a change that adds or alters any of these.
---

# Naming: one convention for every generated name

The full rationale, audit and examples are in `C:/dev/IIT/docs/conventions/NAMING_2026-10-18.md` (owner correction, 18 Oct 2026). This skill gives you the rules you need to apply it.

## Grammar for file names

```
Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
```

- `_` separates fields. `-` joins the words inside a field. Nothing else is a separator: no spaces, middle dots, colons, `+`, `&`, brackets, or dots other than the one before the extension (`.sql.gz` is allowed).
- Fields always appear in this order. Leave out a field that is unknown. Never write `Unknown`, `NA`, or a guess.
- **Portal:** `DesignWorkshop` (designer-portal) or `FieldRepository` (documentation-portal). Always present.
- **Entity:** `Workshop`, `Interview`, `Group-Interview`, `Artisan`, `Product`, `Tool`, `Process`, `Questionnaire`, `Dataset`, `Annual-Plan`, `Sanction-Orders`, `Ministry`, `Shared-Data`, `Account`, `Database`, `Android-App`.
- **Date:** `YYYY-MM-DD` in IST. Use the capture day for captures and recordings, and the generation day for exports, reports and backups. A plan period (`FY2026-27`) replaces the date.
- **Time:** `HHMM` in IST, 24-hour. Only for captures and for snapshots that can repeat within a day. Never seconds.
- **Place:** `Locality-District`, else `District`, else `State`.
- **Craft:** Title-Case words, e.g. `Hand-Block-Printing`.
- **Subject:** the record's name or code, e.g. `Giriraj-Prasad-Chhipa` or `DW-RJ-0012`.
- **What:** `Recording`, `Photo`, `Video`, `Transcript`, `Subtitles`, `Subtitles-With-Speakers`, `Report`, `Export`, `Pro-Forma`, `Questions`, `Artisan-Cards`, `Artisan-List`, `Record-Code`, `Identity-Card`, `Recovery-Codes`, `Backup`, `Release-Build`, `Debug-Build`, and similar.
- **Sequence:** question `D-Q03`, take `D-Q03-T2`, whole section `D-All`, photo `03`, version `v1.1.17`.
- **`n`:** add `_2`, `_3` only when names collide inside one folder or zip. Assign them in a fixed order (createdAt, then id) so repeat downloads give the same names. This is the only bare-number field and it is always last.

## Characters, case, length

- File names are ASCII only: `[A-Za-z0-9-]` inside a field, `_` between fields.
- Title-Case each word. Codes, acronyms and versions keep their own case. Colons in codes become `-`.
- Transliteration: use the stored romanised name if there is one. Otherwise use Python `anyascii`, web `any-ascii`, or Android `Transliterator.getInstance("Any-Latin; Latin-ASCII")`, then Title-Case. Never let a name collapse to `_`. If the result is empty, leave the field out.
- At most 150 characters in total. Truncate Subject first (to 40), then Craft, then Place. Never cut Portal, Entity, Date, Time, What, Sequence, `n` or the extension.
- Guard Windows reserved stems (CON, PRN, AUX, NUL, COM1-9, LPT1-9).

## S3 keys

- New keys use `media/<userId>/<uuid32hex>/<FileName>`. The uuid is what makes a key unique, and the ASCII leaf is the file name above. Keep the `media/<userId>/` prefix: authorisation checks it.
- Backups use `backups/<repo-name>/YYYY-MM-DD.sql.gz` (unchanged).
- **Never rename existing keys and never rewrite `originalFilename`.** The readable name is applied when the file is downloaded: compute it from the row's data (`display_filename` on the backend, `mediaFileName` on the web) and send it via `Content-Disposition` (`filename=` ASCII plus `filename*=UTF-8''`) or the S3 `ResponseContentDisposition`. Keep parsing old capture codes (`D_SEC_`, `ddMMyyyyHHmm[ss]`) for section, question and time.

## Display titles

- Generated defaults for interview, session and workshop titles use `Entity_Date_Place_Craft_Subject`. There is no Portal, What or Sequence. Original script is allowed, and the length limit is 120.
  - `Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa`
  - `Group-Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_6-Artisans`
  - `Workshop_2026-06-18_Bagru-Jaipur_Hand-Block-Printing_DW-RJ-0012`
- Never rewrite a title the user typed. The generated default is the only fallback for a blank title: no `Interview`, `Untitled workshop` and so on.

## Examples

- `DesignWorkshop_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a`
- `DesignWorkshop_Product_2026-06-22_1043_Bagru-Jaipur_Hand-Block-Printing_Indigo-Dabu-Saree_Photo_03.jpg`
- `DesignWorkshop_Workshop_2026-10-18_Bagru-Jaipur_Hand-Block-Printing_DW-RJ-0012_Report.pdf`. The server, web offline and Android builds must give this report an identical name.
- `FieldRepository_Dataset_2026-10-18_1430_Bagru-Jaipur_Export.zip`
- `DesignWorkshop_Product_2026-10-18_Export.csv`
- `DesignWorkshop_Annual-Plan_Pro-Forma.xlsx`
- `DesignWorkshop_Annual-Plan_FY2026-27_Export.xlsx`
- `DesignWorkshop_Database_2026-10-18_Backup.sql.gz`
- `DesignWorkshop_Android-App_Release-Build_v1.1.17.apk`. Change `app_release.py` and the `GetTheAppPanel` copy together.
- `DesignWorkshop_Account_2026-10-18_Recovery-Codes.txt`. Never put an email or phone number in a name.

## How to apply

1. Never format a name inline. Call the runtime's single helper: Python `app/services/naming.py`, web `frontend/lib/naming.ts`, Android `data/Naming.kt`. If the helper does not exist yet, add it rather than hand-building the string.
2. Add a golden test row for every new kind of name, and keep the three runtimes' tables identical.
3. Exempt: Android private storage directories, scratch temp files, and parts inside a docx. These are not data names.
