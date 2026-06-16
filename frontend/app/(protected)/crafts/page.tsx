"use client";

import { useEffect, useState } from "react";
import { Landmark } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { parseJsonMetadata, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue } from "@/lib/forms";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch } from "@/lib/media";
import { canManageCrafts } from "@/lib/permissions";
import type { Craft, PageResult } from "@/lib/types";

export default function CraftsPage() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const allowManage = canManageCrafts(user);
  const [data, setData] = useState<PageResult<Craft> | null>(null);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Craft | null>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setData(await listResource<Craft>("/crafts", { search, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load crafts");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const description = [textValue(form, "description"), exifRemark].filter(Boolean).join("\n\n") || null;
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const payload = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        category: textValue(form, "category"),
        place: textValue(form, "place"),
        description,
        recordedAt,
        recordedTimezone,
        extraMetadata: exifItems.length ? { ...(parseJsonMetadata(form.get("extraMetadata")) ?? {}), mediaExif: exifItems } : parseJsonMetadata(form.get("extraMetadata"))
      };
      const saved = await apiFetch<Craft>(editing ? `/crafts/${editing.id}` : "/crafts", {
        method: editing ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      if (mediaFiles.length) {
        await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "craft",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
        });
      }
      setEditing(null);
      setMediaFiles([]);
      event.currentTarget.reset();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save craft");
    }
  }

  async function remove(id: string) {
    if (!window.confirm("Delete this craft? Linked records will keep their text craft names.")) return;
    await apiFetch(`/crafts/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader title="Crafts" description="Maintain craft vocabulary used to link artisans, products and tools." icon={<Landmark className="h-5 w-5" aria-hidden />} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {allowManage ? (
      <form key={editing?.id ?? "new"} onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-2 lg:grid-cols-4">
        <Field label="Craft name" required>
          <TextInput name="name" required defaultValue={editing?.name ?? ""} />
        </Field>
        <Field label="Local name">
          <TextInput name="localName" defaultValue={editing?.localName ?? ""} />
        </Field>
        <Field label="Category">
          <TextInput name="category" defaultValue={editing?.category ?? ""} />
        </Field>
        <Field label="Place">
          <TextInput name="place" defaultValue={editing?.place ?? ""} />
        </Field>
        <div className="md:col-span-2 lg:col-span-4">
          <Field label="Description">
            <TextArea name="description" defaultValue={editing?.description ?? ""} />
          </Field>
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <MediaCaptureField files={mediaFiles} onFilesChange={setMediaFiles} title="Craft media" description="Attach or capture craft reference images, audio notes, video, and documents." />
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <RecordedAtField value={editing?.recordedAt} timezone={editing?.recordedTimezone} />
        </div>
        <div className="md:col-span-2 lg:col-span-4">
          <Field label="Extra metadata JSON">
            <TextArea name="extraMetadata" placeholder='{"region":"...","materials":"..."}' />
          </Field>
        </div>
        <div className="flex gap-2 md:col-span-2 lg:col-span-4">
          <button className="field-button">{editing ? "Update craft" : "Create craft"}</button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel edit
            </button>
          ) : null}
        </div>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Browse the craft vocabulary below. Ask the master admin for craft creation access to add or edit crafts.
        </div>
      )}
      <form
        className="mb-4 flex flex-col gap-2 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(1);
          load();
        }}
      >
        <input className="field-input" placeholder="Search crafts, categories or descriptions" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="field-button-secondary">Search</button>
      </form>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No crafts found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Name</th>
                  <th className="px-4 py-3">Local name</th>
                  <th className="px-4 py-3">Category</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Description</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((craft) => (
                  <tr key={craft.id}>
                    <td className="px-4 py-3 font-medium text-neutral-900">{craft.name}</td>
                    <td className="px-4 py-3 text-neutral-600">{craft.localName ?? "-"}</td>
                    <td className="px-4 py-3 text-neutral-600">{craft.category ?? "-"}</td>
                    <td className="px-4 py-3 text-neutral-600">{craft.place ?? "-"}</td>
                    <td className="max-w-md px-4 py-3 text-neutral-600">{craft.description ?? "-"}</td>
                    <td className="px-4 py-3 text-right">
                      {allowManage ? (
                        <button className="mr-2 text-sm font-semibold text-field-700" onClick={() => setEditing(craft)}>
                          Edit
                        </button>
                      ) : null}
                      {adminMode ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(craft.id)}>
                          Delete
                        </button>
                      ) : (
                        <span className="text-xs text-ink-soft">View only</span>
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
    </>
  );
}
