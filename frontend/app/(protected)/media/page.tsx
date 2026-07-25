"use client";

import { useCallback, useEffect, useState } from "react";
import { Images, Upload } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { Markdown } from "@/components/Markdown";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { locationFromForm, textValue } from "@/lib/forms";
import { inferMediaType, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type {
  Artisan,
  Craft,
  MediaFile,
  MediaType,
  PageResult,
  ProductDocumentation,
  QuestionnaireInterview,
  ToolDocumentation,
  Workshop
} from "@/lib/types";

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

type ProcessListItem = {
  id: string;
  name: string;
  product?: { productName?: string | null } | null;
  createdAt?: string;
};

function sortRecent<T extends { createdAt?: string }>(items: T[]) {
  // Most recent first, even if a backend list ever changes its default ordering.
  return [...items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
}

/** Android `loadViewEntries` parity: label each entry with its human name/title. */
async function loadEntryOptions(type: string): Promise<Array<{ value: string; label: string }>> {
  const params = { pageSize: 100 };
  switch (type) {
    case "artisan": {
      const page = await listResource<Artisan>("/artisans", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.name} · ${x.place}` }));
    }
    case "workshop": {
      const page = await listResource<Workshop>("/workshops", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: x.title?.trim() || "Untitled workshop" }));
    }
    case "craft": {
      const page = await listResource<Craft>("/crafts", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: x.place ? `${x.name} · ${x.place}` : x.name }));
    }
    case "tool": {
      const page = await listResource<ToolDocumentation>("/tools", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.toolkitName} · ${x.artisanName}` }));
    }
    case "product": {
      const page = await listResource<ProductDocumentation>("/products", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.productName} · ${x.artisanName}` }));
    }
    case "process": {
      const page = await listResource<ProcessListItem>("/processes", params);
      return sortRecent(page.items).map((x) => ({
        value: x.id,
        label: x.product?.productName ? `${x.name} · ${x.product.productName}` : x.name
      }));
    }
    case "questionnaire": {
      const page = await listResource<QuestionnaireInterview>("/questionnaire/interviews", params);
      return sortRecent(page.items).map((x) => ({ value: x.id, label: x.title?.trim() || "Untitled interview" }));
    }
    case "media": {
      const page = await listResource<MediaFile>("/media", params);
      return sortRecent(page.items).map((x) => {
        const tag = x.linkedRecordType?.trim()
          ? x.linkedRecordType.charAt(0).toUpperCase() + x.linkedRecordType.slice(1)
          : null;
        const name = x.originalFilename?.trim() || "Media";
        return { value: x.id, label: [name, x.mediaType, tag].filter(Boolean).join(" · ") };
      });
    }
    default:
      return [];
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
      <MediaPageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function MediaPageBody() {
  const confirm = useConfirm();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");

  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [linkedType, setLinkedType] = useState("");
  const [linkedEntryId, setLinkedEntryId] = useState("");
  const [entryOptions, setEntryOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [loadingEntries, setLoadingEntries] = useState(false);

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

  // Android parity: when the linked record type changes, load that type's entries
  // for the optional second dropdown.
  useEffect(() => {
    setLinkedEntryId("");
    setEntryOptions([]);
    if (!linkedType) return;
    let cancelled = false;
    setLoadingEntries(true);
    loadEntryOptions(linkedType)
      .then((options) => {
        if (!cancelled) setEntryOptions(options);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : `Unable to load ${LINK_TYPE_LABEL.get(linkedType) ?? linkedType} entries`);
      })
      .finally(() => {
        if (!cancelled) setLoadingEntries(false);
      });
    return () => {
      cancelled = true;
    };
  }, [linkedType]);

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
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    try {
      const { uploaded, failed } = await uploadMediaBatch({
        files: renameForUpload(form),
        linkedRecordType: linkedType || null,
        linkedRecordId: linkedEntryId || null,
        caption: textValue(form, "caption") ?? undefined,
        location: locationFromForm(form),
        onProgress: setProgress
      });
      setProgress(null);
      // The uploaded files surface twice: as chips under this form and in the page-level tray.
      addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
      // Anything that got through is already in the repository, so refresh the table either way and
      // keep the form (and its selection) intact when part of the batch still needs another attempt.
      load(page, search);
      if (failed.length) {
        setError(
          `${failed.length} of ${selectedFiles.length} file(s) failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
            "The rest were saved — remove the ones that landed and upload again."
        );
        return;
      }
      formElement.reset();
      setSelectedFiles([]);
      setLinkedType("");
      setLinkedEntryId("");
    } catch (err) {
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
        "This deletes the file from storage and its record from the database. It cannot be undone, and the file can no longer be re-linked.",
        "Any transcript generated from it is deleted with it."
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
        description="Upload media — images, videos, audio and files go to the same repository backend. Audio is queued for transcription after upload."
        icon={<Images className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <MediaCaptureField
          files={selectedFiles}
          onFilesChange={setSelectedFiles}
          title="Capture media"
          description="Images, videos, audio and files upload to the same repository backend. Audio is queued for transcription after upload."
        />
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Media title / object name">
            <TextInput name="mediaTitle" placeholder="Names the uploaded object (optional)" />
          </Field>
          <Field label="Linked record type *">
            <Dropdown
              value={linkedType}
              onChange={setLinkedType}
              options={LINK_TYPES}
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
              placeholder={
                loadingEntries ? "Loading…" : entryOptions.length === 0 ? "No entries for this type" : "Select an entry"
              }
              name="linkedRecordId"
            />
          </Field>
        ) : null}
        <Field label="Caption">
          <TextArea name="caption" />
        </Field>
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
                  <tr key={item.id}>
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
                        <details>
                          <summary className="cursor-pointer font-semibold text-field-700">View transcript</summary>
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
