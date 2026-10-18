"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Images, Upload } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { Markdown } from "@/components/Markdown";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { useServerSearchList } from "@/components/forms/useServerSearchList";
import { readCarryContext } from "@/lib/carryContext";
import { failedOriginals, initialMediaTarget } from "@/lib/mediaTarget";
import { queueMediaOnly, uploadFailureIsOffline } from "@/lib/offline";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { bytes, countNoun, formatDateTime } from "@/lib/format";
import { rowClickOpensRecord } from "@/lib/recordView";
import { locationFromForm, textValue } from "@/lib/forms";
import { inferMediaType, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { MediaFile, MediaType, PageResult } from "@/lib/types";

// ---------------------------------------------------------------------------
// Android parity: mediaLinkModes — the EXACT list + labels of record types a
// miscellaneous-media upload can be linked to (MainActivity `mediaLinkModes`).
// ---------------------------------------------------------------------------

const LINK_TYPES: Array<{ value: string; label: string }> = [
  { value: "artisan", label: "Artisan" },
  { value: "workshop", label: "Workshop" },
  { value: "craft", label: "Craft" },
  { value: "tool", label: "Tool" },
  { value: "product", label: "Product" },
  { value: "process", label: "Process" },
  { value: "questionnaire", label: "Questionnaire" },
  { value: "media", label: "Miscellaneous Media" }
];

const LINK_TYPE_LABEL = new Map(LINK_TYPES.map((t) => [t.value, t.label]));

/**
 * QUESTIONNAIRE V2 (DESIGN §4.1): interview audio enters only through the session workspace, so the
 * upload form stops offering "Questionnaire" as a link type. Existing rows keep their label above.
 */
const V2_LINK_TYPES = LINK_TYPES.filter((t) => t.value !== "questionnaire");

type ProcessListItem = {
  id: string;
  name: string;
  product?: { productName?: string | null } | null;
  createdAt?: string;
};

/** A11: each record type's list route, searched on the server when its first page is not all of it. */
const LINK_ENDPOINT: Record<string, string> = {
  artisan: "/artisans",
  workshop: "/workshops",
  craft: "/crafts",
  tool: "/tools",
  product: "/products",
  process: "/processes",
  questionnaire: "/questionnaire/interviews",
  media: "/media"
};

type EntryRow = { id: string; createdAt?: string } & Record<string, unknown>;

/** Android `loadViewEntries` parity: label each entry with its human name/title. */
function entryLabel(type: string, row: EntryRow): string {
  const text = (value: unknown) => (typeof value === "string" ? value.trim() : "");
  switch (type) {
    case "artisan":
      return [text(row.name), text(row.place)].filter(Boolean).join(" · ") || "Artisan";
    case "workshop":
      return text(row.title) || "Untitled workshop";
    case "craft":
      return [text(row.name), text(row.place)].filter(Boolean).join(" · ") || "Craft";
    case "tool":
      return [text(row.toolkitName), text(row.artisanName)].filter(Boolean).join(" · ") || "Tool";
    case "product":
      return [text(row.productName), text(row.artisanName)].filter(Boolean).join(" · ") || "Product";
    case "process": {
      const product = row.product as ProcessListItem["product"];
      return [text(row.name), text(product?.productName)].filter(Boolean).join(" · ") || "Process";
    }
    case "questionnaire":
      return text(row.title) || "Untitled interview";
    case "media": {
      const linked = text(row.linkedRecordType);
      const tag = linked ? linked.charAt(0).toUpperCase() + linked.slice(1) : null;
      return [text(row.originalFilename) || "Media", text(row.mediaType), tag].filter(Boolean).join(" · ");
    }
    default:
      return row.id;
  }
}

// ---------------------------------------------------------------------------
// Android parity: FieldRepository.mediaFilename — the uploaded object name is
// `PREFIX_NamePart_TYPECODE_index_ddMMyyyyHHmmss.ext` built from the linked
// record type and the "Media title / object name" (falling back to caption,
// then the original filename).
// ---------------------------------------------------------------------------

function safeToken(value: string) {
  const cleaned = value.trim().replace(/\s+/g, "").replace(/[^A-Za-z0-9]/g, "").slice(0, 60);
  return cleaned || "Record";
}

function typeCode(mediaType: MediaType) {
  if (mediaType === "IMAGE") return "IMG";
  if (mediaType === "AUDIO") return "AUD";
  if (mediaType === "VIDEO") return "VID";
  return "DOC";
}

function buildObjectName(
  recordType: string,
  title: string | null,
  caption: string | null,
  mediaType: MediaType,
  index: number,
  originalName: string
) {
  const dot = originalName.lastIndexOf(".");
  const extension = dot > 0 ? originalName.slice(dot + 1) : null;
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const timestamp = `${pad(now.getDate())}${pad(now.getMonth() + 1)}${now.getFullYear()}${pad(now.getHours())}${pad(
    now.getMinutes()
  )}${pad(now.getSeconds())}`;
  const prefix = safeToken(recordType || "MEDIA").toUpperCase();
  const nameSource = title?.trim() || caption?.trim() || (dot > 0 ? originalName.slice(0, dot) : originalName);
  const base = [prefix, safeToken(nameSource), typeCode(mediaType), String(index), timestamp].join("_");
  return extension ? `${base}.${extension}` : base;
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

/** Section id this page's batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "misc-media";
const MEDIA_SECTION_LABEL = "Miscellaneous Media";

export default function MediaPage() {
  return (
    <UploadsProvider>
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <MediaPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

/**
 * ── DICTATION ON THE MEDIA FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ───────
 *
 * DICTATED: Media title / object name · Caption · (and Village, inside the location card, which owns
 * its own decision).
 *
 * The title is the name of the object in the photograph — "Bagru indigo block, 4 inch" — spoken by
 * somebody holding both the phone and the object, which is the clearest case for a microphone
 * anywhere in the app. Neither box is title-cased: `mediaTitle` and `caption` are both absent from
 * the API's title-cased set (`backend/app/services/records.py:339-354`), so a "Will be saved as …"
 * hint would promise a normalisation that never happens.
 *
 * NOT DICTATED: **Capture media** (a file picker), **Linked record type** (a closed vocabulary behind
 * a themed dropdown) and **Linked entry** (a record picker).
 *
 * THIS FORM CLEARS ITSELF IN PLACE, which is why the two boxes are wired the way they are — see the
 * note beside `mediaTitle`/`resetNonce` below. It is the trap `DictatedTextInput`'s header is about.
 *
 * ONE SENTENCE FOR THE WHOLE FORM: both boxes pass `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once, above them.
 */
function MediaPageBody() {
  const confirm = useConfirm();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");

  /*
    ── THIS FORM CALLS `formElement.reset()`, AND THAT IS WHY THESE TWO EXIST ──────────────────
    `reset()` rewrites the DOM nodes and tells React NOTHING. A box whose value React is holding —
    which is every dictated box, because dictation writes from outside the keyboard — is therefore
    re-painted with the PREVIOUS upload's text on the next render, and the researcher's second
    photograph arrives carrying the first one's caption.

    TWO MECHANISMS BECAUSE THE TWO COMPONENTS HAVE TWO CONTRACTS. `DictatedTextInput` is controlled
    by its caller, so the title lives here and is cleared in the same block as `reset()`.
    `DictatedTextArea` owns its value and re-seeds on remount, so the caption is keyed on a nonce
    that the same block bumps.
  */
  const [mediaTitle, setMediaTitle] = useState("");
  const [resetNonce, setResetNonce] = useState(0);

  const searchParams = useSearchParams();
  const { user } = useAuth();
  const questionnaireConfig = useQuestionnaireConfig();
  const linkTypes = questionnaireConfig?.v2Enabled ? V2_LINK_TYPES : LINK_TYPES;
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  /*
    A9 — THE TARGET IS REMEMBERED. It opens on the record named in the link or the one the researcher
    was last working in (`initialMediaTarget`), and it STAYS after a batch: the next photographs are
    almost always for the same record.
  */
  const [initialTarget] = useState(() => initialMediaTarget(searchParams, readCarryContext(user?.id ?? null)));
  const [linkedType, setLinkedType] = useState(initialTarget?.type ?? "");
  const [linkedEntryId, setLinkedEntryId] = useState(initialTarget?.id ?? "");
  const [notice, setNotice] = useState<string | null>(null);
  // A11: one page of the chosen type, searched on the server when that page is not the whole list.
  const entryList = useServerSearchList<EntryRow>(linkedType ? LINK_ENDPOINT[linkedType] ?? null : null, "entries");
  const offPageEntry = useRecordOffPage<EntryRow>(linkedType ? LINK_ENDPOINT[linkedType] ?? "/media" : "/media", linkedEntryId, entryList.items);
  const entryOptions = useMemo(() => {
    const rows = offPageEntry && !entryList.items.some((row) => row.id === offPageEntry.id) ? [offPageEntry, ...entryList.items] : entryList.items;
    return rows.map((row) => ({ value: row.id, label: entryLabel(linkedType, row) }));
  }, [entryList.items, offPageEntry, linkedType]);
  const loadingEntries = Boolean(linkedType) && entryList.state === "pending";

  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState<BatchProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);

  const load = useCallback(
    async (pageToLoad: number, term: string) => {
      try {
        setData(await listResource<MediaFile>("/media", { page: pageToLoad, pageSize: 20, search: term || undefined }));
        setError(null);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to load media");
      }
    },
    []
  );

  // Live search: debounce keystrokes, reload most-recent-first from page 1.
  useEffect(() => {
    const timer = setTimeout(() => load(page, search), 300);
    return () => clearTimeout(timer);
  }, [page, search, load]);

  /**
   * Android parity: every uploaded object is renamed to `PREFIX_Name_TYPE_index_timestamp.ext`.
   * Renaming the File itself (rather than keeping a parallel name) lets this page share the one
   * resilient upload path in lib/media — per-byte progress, ETA, and a fresh presign per retry.
   */
  function renameForUpload(form: FormData) {
    const title = textValue(form, "mediaTitle");
    const caption = textValue(form, "caption");
    return selectedFiles.map((file, index) => {
      const objectName = buildObjectName(linkedType, title, caption, inferMediaType(file), index + 1, file.name);
      return new File([file], objectName, { type: file.type, lastModified: file.lastModified });
    });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedFiles.length === 0) {
      setError("Choose or record at least one file first");
      return;
    }
    if (!linkedType) {
      setError("Choose the type of record this media belongs to");
      return;
    }
    setUploading(true);
    setError(null);
    setNotice(null);
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const renamed = renameForUpload(form);
    const batch = {
      files: renamed,
      linkedRecordType: linkedType,
      linkedRecordId: linkedEntryId || null,
      caption: textValue(form, "caption") ?? undefined,
      location: locationFromForm(form)
    };
    /** A batch that is done with: the files and the two dictated boxes go, the TARGET stays (A9). */
    const clearBatch = () => {
      formElement.reset();
      // The two dictated boxes, which `reset()` alone cannot clear. See the note beside their state.
      setMediaTitle("");
      setResetNonce((nonce) => nonce + 1);
      setSelectedFiles([]);
    };
    const queue = async () => {
      // A9: no connection — the files wait in this browser's outbox and upload when it returns.
      await queueMediaOnly({ label: `Media · ${renamed.length} file${renamed.length === 1 ? "" : "s"}`, media: [batch] });
      clearBatch();
      setNotice(`Saved on this device. ${renamed.length === 1 ? "It uploads" : "They upload"} when you reconnect.`);
    };
    try {
      if (typeof navigator !== "undefined" && navigator.onLine === false) {
        await queue();
        return;
      }
      const { uploaded, failed } = await uploadMediaBatch({ ...batch, onProgress: setProgress });
      setProgress(null);
      // The uploaded files surface twice: as chips under this form and in the page-level tray.
      addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
      // Anything that got through is already in the repository, so refresh the table either way.
      load(page, search);
      if (failed.length) {
        // A9: only the files that failed stay selected, so "Upload" retries exactly those.
        setSelectedFiles(failedOriginals(selectedFiles, renamed, failed.map((item) => item.name)));
        setError(`${failed.length} of ${renamed.length} didn’t upload: ${failed.map((item) => item.name).join(", ")}. Upload again to retry them.`);
        return;
      }
      clearBatch();
    } catch (err) {
      if (uploadFailureIsOffline(err)) {
        try {
          await queue();
          return;
        } catch {
          /* fall through to the message */
        }
      }
      setError(err instanceof Error ? err.message : "Unable to upload media");
    } finally {
      setUploading(false);
      setProgress(null);
    }
  }

  async function remove(id: string) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this media file?",
        // Android's "Permanently delete recording?" says the same thing: the file leaves storage, so
        // there is nothing left to re-link afterwards.
        "Deletes the file and its transcript. This can’t be undone.",
        undefined
      ),
      confirmLabel: "Remove file"
    });
    if (!ok) return;
    try {
      await apiFetch(`/media/${id}`, { method: "DELETE" });
      await load(page, search);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to remove media file");
    }
  }

  const uploadLabel = uploading
    ? "Uploading batch..."
    : !linkedType
      ? "Choose a record type"
      : `Upload ${selectedFiles.length || ""} media file${selectedFiles.length === 1 ? "" : "s"}`;

  return (
    <>
      <PageHeader
        title="Miscellaneous Media"
        description="Photos, video, audio and files. Audio is transcribed."
        icon={<Images className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {notice ? (
        <div role="status" className="mb-4 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm text-purple-950">
          {notice}
        </div>
      ) : null}

      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <MediaCaptureField
          files={selectedFiles}
          onFilesChange={setSelectedFiles}
          title="Capture media"
          description="Photos, video, audio and files. Audio is transcribed."
        />
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Both dictated boxes below pass `explainWhenUnavailable={false}`, as does the location card's
          village. ABOVE the controls it speaks for, like every other form: a sentence explaining why
          something is missing is no use underneath the place it is missing from.
        */}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2">
          <DictatedTextInput
            name="mediaTitle"
            label="Media title / object name"
            placeholder="Names the uploaded object (optional)"
            explainWhenUnavailable={false}
            value={mediaTitle}
            onChange={setMediaTitle}
          />
          {/* The asterisk leaves the label STRING and becomes `required`, so `RequiredMark` draws it.
              Typed into the text it was the one required mark no shared primitive could reach, which
              is exactly how a form ends up with two kinds of asterisk on it. */}
          <Field label="Linked record type" required>
            <Dropdown
              value={linkedType}
              onChange={(next) => {
                if (next !== linkedType) setLinkedEntryId("");
                setLinkedType(next);
              }}
              options={linkTypes}
              placeholder="Choose the type of record"
              ariaLabel="Linked record type"
            />
          </Field>
        </div>
        {linkedType ? (
          <Field label="Linked entry (optional)">
            <ComboBox
              options={entryOptions}
              value={linkedEntryId}
              onChange={setLinkedEntryId}
              onSearch={entryList.setQuery}
              placeholder={
                loadingEntries ? "Loading…" : entryOptions.length === 0 ? "No entries for this type" : "Select an entry"
              }
              name="linkedRecordId"
            />
            {entryList.searching ? <p className="mt-1 text-xs text-ink-500">Searching…</p> : null}
            <CappedListNotice cuts={[entryList.cut]} />
          </Field>
        ) : null}
        {/* KEYED ON THE RESET NONCE. `DictatedTextArea` owns its value and re-seeds only on remount,
            and this form clears itself with `formElement.reset()` — which rewrites the DOM node and
            tells React nothing. Without the key, the second photograph of the day arrives carrying
            the first one's caption, on a real file, with nothing on screen saying so. */}
        <DictatedTextArea
          key={resetNonce}
          name="caption"
          label="Caption"
          explainWhenUnavailable={false}
        />
        <LocationFields />
        <UploadProgress progress={progress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} />
        <div>
          <button className="field-button" disabled={uploading || selectedFiles.length === 0 || !linkedType}>
            <Upload className="h-4 w-4" aria-hidden />
            {uploadLabel}
          </button>
        </div>
      </form>

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Search media by filename, caption, or MIME type"
          />
        </div>
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No media uploaded" body={search ? "Nothing matches this search." : undefined} />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1200px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  {["Preview", "File", "Type", "Size", "Linked record", "Transcript", "Status", "Uploaded", "Actions"].map(
                    (heading) => (
                      <th
                        key={heading}
                        className={`resize-x overflow-hidden px-4 py-3 ${heading === "Actions" ? "text-right" : ""}`}
                        style={{ minWidth: heading === "Preview" ? 160 : 96 }}
                      >
                        {heading}
                      </th>
                    )
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((item) => (
                  // A file's read-only view IS its preview — the lightbox, with its Download button —
                  // so a click anywhere on the row opens that, as the thumbnail always did. The
                  // thumbnail's own button is the keyboard route; Delete and the transcript keep
                  // their own clicks (`rowClickOpensRecord`).
                  <tr
                    key={item.id}
                    className={item.url ? "cursor-pointer transition hover:bg-field-100" : undefined}
                    onClick={(event) => {
                      if (!item.url || !rowClickOpensRecord(event.target as Element, event.currentTarget)) return;
                      if (window.getSelection()?.toString()) return;
                      setActivePreview({
                        key: item.id,
                        id: item.id,
                        name: item.originalFilename,
                        mediaType: item.mediaType,
                        mimeType: item.mimeType,
                        sizeBytes: item.sizeBytes,
                        url: item.url,
                        caption: item.caption,
                        transcriptStatus: item.transcriptStatus,
                        transcriptText: item.transcriptText,
                        transcriptError: item.transcriptError
                      });
                    }}
                  >
                    <td className="px-4 py-3">
                      {item.url ? (
                        <div className="w-36">
                          <MediaPreviewTile
                            item={{
                              key: item.id,
                              id: item.id,
                              name: item.originalFilename,
                              mediaType: item.mediaType,
                              mimeType: item.mimeType,
                              sizeBytes: item.sizeBytes,
                              url: item.url,
                              caption: item.caption
                            }}
                            onOpen={() =>
                              setActivePreview({
                                key: item.id,
                                id: item.id,
                                name: item.originalFilename,
                                mediaType: item.mediaType,
                                mimeType: item.mimeType,
                                sizeBytes: item.sizeBytes,
                                url: item.url,
                                caption: item.caption,
                                transcriptStatus: item.transcriptStatus,
                                transcriptText: item.transcriptText,
                                transcriptError: item.transcriptError
                              })
                            }
                          />
                        </div>
                      ) : (
                        <span className="text-ink-500">No URL</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{item.originalFilename}</div>
                      {item.caption ? <div className="max-w-xs truncate text-xs text-ink-500">{item.caption}</div> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{item.mediaType}</td>
                    <td className="px-4 py-3 text-ink-700">{bytes(item.sizeBytes)}</td>
                    <td className="px-4 py-3 text-ink-700">
                      {item.linkedRecordType ? LINK_TYPE_LABEL.get(item.linkedRecordType) ?? item.linkedRecordType : "-"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {item.transcriptText ? (
                        <details data-row-ignore>
                          <summary className="cursor-pointer font-semibold text-field-700 dark:text-purple-300">View transcript</summary>
                          <div className="mt-2 max-h-64 min-w-64 overflow-auto rounded-md bg-field-100 p-3">
                            <Markdown text={item.transcriptText} />
                          </div>
                        </details>
                      ) : (
                        item.transcriptStatus ?? "-"
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {adminMode ? (
                        <RowActions>
                          <button className={rowAction("danger")} onClick={() => remove(item.id)}>
                            Delete
                          </button>
                        </RowActions>
                      ) : (
                        <span className="text-xs text-ink-500">Admin only</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}
