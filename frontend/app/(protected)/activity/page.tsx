"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Activity, ChevronRight } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { ROLE_RANK, isMasterAdmin, roleLabel, roleRank } from "@/lib/permissions";
import type {
  Artisan,
  Craft,
  MediaFile,
  ProductDocumentation,
  QuestionnaireInterview,
  ToolDocumentation,
  UserRole,
  Workshop
} from "@/lib/types";

/** One row in a group: enough to name the record and say when it was created. */
type ActivityRow = { id: string; name: string; createdAt?: string | null };

type ActivityGroup = { title: string; href: string; rows: ActivityRow[] };

type WithCreator = { createdById?: string | null };
type MediaWithUploader = MediaFile & { uploadedById?: string | null };

/** /users/directory row — any authenticated user may list this. */
type DirectoryUser = { id: string; name: string; email: string; role: string };

/** The raw fetched lists, kept unfiltered so switching users only re-filters client-side. */
type RawLists = {
  artisans: Array<Artisan & WithCreator>;
  products: Array<ProductDocumentation & WithCreator>;
  tools: Array<ToolDocumentation & WithCreator>;
  workshops: Array<Workshop & WithCreator>;
  crafts: Array<Craft & WithCreator>;
  interviews: QuestionnaireInterview[];
  media: MediaWithUploader[];
};

export default function MyActivityPage() {
  const { user } = useAuth();
  const [raw, setRaw] = useState<RawLists | null>(null);
  const [directory, setDirectory] = useState<DirectoryUser[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!user) return;
    const pageSize = 100;

    async function load() {
      const [artisans, products, tools, workshops, crafts, interviews, media, directoryResult] =
        await Promise.allSettled([
          listResource<Artisan & WithCreator>("/artisans", { pageSize }),
          listResource<ProductDocumentation & WithCreator>("/products", { pageSize }),
          listResource<ToolDocumentation & WithCreator>("/tools", { pageSize }),
          listResource<Workshop & WithCreator>("/workshops", { pageSize }),
          listResource<Craft & WithCreator>("/crafts", { pageSize }),
          listResource<QuestionnaireInterview>("/questionnaire/interviews", { pageSize }),
          listResource<MediaWithUploader>("/media", { pageSize }),
          apiFetch<DirectoryUser[]>("/users/directory")
        ]);

      const items = <T,>(result: PromiseSettledResult<{ items: T[] }>): T[] =>
        result.status === "fulfilled" ? result.value.items : [];

      setRaw({
        artisans: items(artisans),
        products: items(products),
        tools: items(tools),
        workshops: items(workshops),
        crafts: items(crafts),
        interviews: items(interviews),
        media: items(media)
      });
      setDirectory(directoryResult.status === "fulfilled" ? directoryResult.value : []);

      const failures = [artisans, products, tools, workshops, crafts, interviews, media].filter(
        (result) => result.status === "rejected"
      );
      setError(failures.length ? "Some records could not be loaded — the lists below may be incomplete." : null);
    }

    load();
  }, [user]);

  // Hierarchy visibility: master admin sees everyone; admin sees the same rank and below (other
  // admins included, master admins excluded); everyone else sees only ranks STRICTLY below their
  // own — which leaves volunteers with nobody but themselves.
  const visibleOthers = useMemo(() => {
    if (!user) return [];
    const myRank = roleRank(user);
    return directory.filter((entry) => {
      if (entry.id === user.id) return false;
      if (isMasterAdmin(user)) return true;
      const rank = roleRank(entry.role as UserRole);
      if (user.role === "ADMIN") return rank <= ROLE_RANK.ADMIN;
      return rank < myRank;
    });
  }, [directory, user]);

  const activeUserId = selectedUserId ?? user?.id ?? "";
  const viewingSelf = activeUserId === user?.id;
  const selectedEntry = directory.find((entry) => entry.id === activeUserId);
  const selectedName = viewingSelf ? user?.name : selectedEntry?.name;
  const selectedRole = viewingSelf ? user?.role : selectedEntry?.role;

  const groups = useMemo<ActivityGroup[] | null>(() => {
    if (!raw || !activeUserId) return null;
    const by = <T extends WithCreator>(list: T[]): T[] => list.filter((item) => item.createdById === activeUserId);
    return [
      {
        title: "Artisans",
        href: "/artisans",
        rows: by(raw.artisans).map((a) => ({ id: a.id, name: a.name, createdAt: a.createdAt }))
      },
      {
        title: "Products",
        href: "/products",
        rows: by(raw.products).map((p) => ({ id: p.id, name: p.productName, createdAt: p.createdAt }))
      },
      {
        title: "Tools",
        href: "/tools",
        rows: by(raw.tools).map((t) => ({ id: t.id, name: t.toolkitName, createdAt: t.createdAt }))
      },
      {
        title: "Workshops",
        href: "/workshops",
        rows: by(raw.workshops).map((w) => ({ id: w.id, name: w.title, createdAt: w.createdAt }))
      },
      {
        title: "Crafts",
        href: "/crafts",
        rows: by(raw.crafts).map((c) => ({ id: c.id, name: c.name, createdAt: c.createdAt }))
      },
      {
        title: "Questionnaire interviews",
        href: "/questionnaire",
        rows: raw.interviews
          .filter((i) => i.createdById === activeUserId)
          .map((i) => ({ id: i.id, name: i.title, createdAt: i.createdAt }))
      },
      {
        title: "Miscellaneous media",
        href: "/media",
        rows: raw.media
          .filter((m) => (m.uploadedById ?? m.uploadedBy?.id) === activeUserId)
          .map((m) => ({ id: m.id, name: m.originalFilename, createdAt: m.createdAt }))
      }
    ].filter((group) => group.rows.length > 0);
  }, [raw, activeUserId]);

  return (
    <>
      <PageHeader
        title="My Activity"
        description="Everything documented — artisans, products, tools, workshops, crafts, interviews and media. Reviewers can also inspect the activity of contributors they oversee."
        icon={<Activity className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {user && visibleOthers.length > 0 ? (
        <div className="panel mb-4 max-w-md p-4">
          <Field label="Whose activity?">
            <Select value={activeUserId} onChange={(event) => setSelectedUserId(event.target.value)}>
              <option value={user.id}>Me — {user.name}</option>
              {visibleOthers.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name} — {roleLabel(entry.role)}
                </option>
              ))}
            </Select>
          </Field>
        </div>
      ) : null}
      {selectedName ? (
        <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
          <span className="text-ink-500">Showing activity of</span>
          <span className="font-display font-bold text-ink-900">{selectedName}</span>
          <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
            {roleLabel(selectedRole)}
          </span>
        </div>
      ) : null}
      {!groups ? (
        <div className="panel p-4 text-sm text-ink-500">Loading activity...</div>
      ) : groups.length === 0 ? (
        <EmptyState
          title={viewingSelf ? "Nothing here yet" : `No records from ${selectedName ?? "this user"} yet`}
          body={
            viewingSelf
              ? "Records you create — artisans, products, tools, interviews and media uploads — will show up here."
              : "Records this contributor creates — artisans, products, tools, interviews and media uploads — will show up here."
          }
        />
      ) : (
        <div className="grid gap-4">
          {groups.map((group) => (
            <section className="panel overflow-hidden" key={group.title}>
              <div className="flex items-center justify-between border-b border-line-200 px-4 py-3">
                <h2 className="font-display font-bold text-ink-900">{group.title}</h2>
                <Link className="inline-flex items-center gap-1 text-sm font-medium text-purple-700 hover:text-purple-800" href={group.href}>
                  Open
                  <ChevronRight className="h-4 w-4" aria-hidden />
                </Link>
              </div>
              <ul className="divide-y divide-line-200">
                {group.rows.map((row) => (
                  <li className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm" key={row.id}>
                    <span className="min-w-0 truncate font-medium text-ink-900">{row.name}</span>
                    <span className="shrink-0 text-xs text-ink-500">{formatDate(row.createdAt)}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </>
  );
}
