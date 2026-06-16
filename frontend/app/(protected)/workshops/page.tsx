"use client";

import { useEffect, useState } from "react";
import { MapPinned } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { DateRangeField } from "@/components/forms/DateRangeField";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue } from "@/lib/forms";
import { uploadMediaBatch } from "@/lib/media";
import { canManageWorkshops } from "@/lib/permissions";
import type { Artisan, PageResult, Workshop } from "@/lib/types";

export default function WorkshopsPage() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const allowManage = canManageWorkshops(user);
  const [data, setData] = useState<PageResult<Workshop> | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Workshop | null>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      const [workshopResult, artisanResult] = await Promise.all([
        listResource<Workshop>("/workshops", { search, page, pageSize: 20 }),
        listResource<Artisan>("/artisans", { pageSize: 100 })
      ]);
      setData(workshopResult);
      setArtisans(artisanResult.items);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load workshops");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const artisanIds = form.getAll("artisanIds").map(String);
    try {
      const payload = {
        title: requiredText(form, "title"),
        date: requiredText(form, "date"),
        startDate: requiredText(form, "startDate"),
        endDate: requiredText(form, "endDate"),
        place: requiredText(form, "place"),
        description: textValue(form, "description"),
        notes: textValue(form, "notes"),
        status: requiredText(form, "status") || "PENDING",
        artisanIds,
        recordedAt: recordedAtFromForm(form),
        recordedTimezone: recordedTimezoneFromForm(form),
        location: locationFromForm(form)
      };
      const saved = await apiFetch<Workshop>(editing ? `/workshops/${editing.id}` : "/workshops", {
        method: editing ? "PATCH" : "POST",
        body: JSON.stringify(payload)
      });
      if (mediaFiles.length) {
        await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "workshop",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.title}`,
          recordedAt: payload.recordedAt,
          recordedTimezone: payload.recordedTimezone,
          location: payload.location
        });
      }
      setEditing(null);
      setMediaFiles([]);
      event.currentTarget.reset();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save workshop");
    }
  }

  async function remove(id: string) {
    if (!window.confirm("Delete this workshop?")) return;
    await apiFetch(`/workshops/${id}`, { method: "DELETE" });
    load();
  }

  const editingArtisanIds = new Set(editing?.artisans?.map((item) => item.artisan.id) ?? []);

  return (
    <>
      <PageHeader
        title="Workshops"
        description="Create field workshop records, link artisans and store date, place, notes and GPS context."
        icon={<MapPinned className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {allowManage ? (
      <form key={editing?.id ?? "new"} onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Workshop title" required>
            <TextInput name="title" required defaultValue={editing?.title ?? ""} />
          </Field>
          <div className="md:col-span-2">
            <DateRangeField start={editing?.startDate ?? editing?.date} end={editing?.endDate ?? editing?.date} />
          </div>
          <Field label="Place" required>
            <TextInput name="place" required defaultValue={editing?.place ?? ""} />
          </Field>
          <Field label="Status">
            <Select name="status" defaultValue={editing?.status ?? "PENDING"}>
              {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                <option key={status}>{status}</option>
              ))}
            </Select>
          </Field>
          <Field label="Linked artisans">
            <select name="artisanIds" multiple className="field-input min-h-32" defaultValue={Array.from(editingArtisanIds)}>
              {artisans.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisan.name} · {artisan.place}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Description">
            <TextArea name="description" defaultValue={editing?.description ?? ""} />
          </Field>
          <Field label="Notes">
            <TextArea name="notes" defaultValue={editing?.notes ?? ""} />
          </Field>
        </div>
        <MediaCaptureField files={mediaFiles} onFilesChange={setMediaFiles} title="Workshop media" description="Attach workshop images, videos, audio notes, attendance references, and documents." />
        <RecordedAtField value={editing?.recordedAt} timezone={editing?.recordedTimezone} />
        <LocationFields />
        <div className="flex gap-2">
          <button className="field-button">{editing ? "Update workshop" : "Create workshop"}</button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel edit
            </button>
          ) : null}
        </div>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Browse workshops below. Ask the master admin for workshop creation access to add or edit workshops.
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
        <input className="field-input" placeholder="Search workshops by title, place or description" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="field-button-secondary">Search</button>
      </form>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No workshops found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Workshop</th>
                  <th className="px-4 py-3">Date</th>
                  <th className="px-4 py-3">Place</th>
                  <th className="px-4 py-3">Artisans</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((workshop) => (
                  <tr key={workshop.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{workshop.title}</div>
                      <div className="text-xs text-neutral-500">{workshop.description ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">
                      {formatDateTime(workshop.startDate ?? workshop.date)}
                      {workshop.endDate ? <span className="block text-xs text-neutral-500">to {formatDateTime(workshop.endDate)}</span> : null}
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{workshop.place}</td>
                    <td className="px-4 py-3 text-neutral-600">{workshop.artisans?.map((item) => item.artisan.name).join(", ") || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={workshop.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDate(workshop.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {allowManage ? (
                        <button className="mr-2 text-sm font-semibold text-field-700" onClick={() => setEditing(workshop)}>
                          Edit
                        </button>
                      ) : null}
                      {adminMode ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(workshop.id)}>
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
