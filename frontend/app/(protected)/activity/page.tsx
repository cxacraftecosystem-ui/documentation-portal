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

/**
 * One person's fetched lists, STAMPED WITH WHOSE THEY ARE.
 *
 * The stamp is load-bearing now that the API does the owner filtering. Switching person used to be a
 * pure client-side re-filter of one unfiltered corpus; it is a re-fetch, and until the new request
 * lands the state still holds the PREVIOUS person's rows — every one of which the server already
 * narrowed to that person, so re-filtering them against the newly selected id matches nothing. The
 * page would show "no activity" for somebody with plenty of it, then correct itself a moment later.
 *
 * Comparing the stamp against the current selection is what turns that window into an honest
 * "loading" instead of a wrong answer, and doing it in the memo rather than by clearing state in the
 * effect means there is not even a one-frame flash — passive effects run after paint.
 */
type RawLists = {
  /** Whose rows these are. A mismatch with `activeUserId` means the switch is still in flight. */
  ownerId: string;
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

  /**
   * WHOSE activity is being read. Declared before the fetch because the fetch now depends on it: the
   * API is asked for one person's records, so switching person is a re-fetch rather than a re-filter.
   */
  const activeUserId = selectedUserId ?? user?.id ?? "";

  /**
   * ONE PERSON'S RECORDS, ASKED FOR BY NAME.
   *
   * This used to fetch page one of every list — a hundred rows apiece — and sift it client-side for
   * `createdById === activeUserId`. That worked only by accident: reading the repository was itself
   * owner-scoped, so "the first hundred artisans you can see" and "the first hundred artisans you
   * recorded" were the same hundred rows. Reading is open now, so page one is a hundred rows of the
   * WHOLE repository and a person's own records fall off the end of it — silently, with no empty
   * state and no truncation notice, which reads as "I did not record that" rather than as a paging
   * artefact.
   *
   * So the ownership filter moved to the server (`createdBy` / `uploadedBy` on every list route). The
   * client-side filter below is KEPT as well, deliberately: it costs nothing, and against an older
   * deployment that ignores the new parameter it is the difference between an over-long list and a
   * wrong one.
   */
  useEffect(() => {
    if (!user || !activeUserId) return;
    let cancelled = false;
    const pageSize = 100;
    // The record lists own their rows through `createdById`; MediaFile owns its through
    // `uploadedById`, and the query key follows the column on both sides of the wire.
    const mine = { pageSize, createdBy: activeUserId };

    async function load() {
      const [artisans, products, tools, workshops, crafts, interviews, media, directoryResult] =
        await Promise.allSettled([
          listResource<Artisan & WithCreator>("/artisans", mine),
          listResource<ProductDocumentation & WithCreator>("/products", mine),
          listResource<ToolDocumentation & WithCreator>("/tools", mine),
          listResource<Workshop & WithCreator>("/workshops", mine),
          listResource<Craft & WithCreator>("/crafts", mine),
          listResource<QuestionnaireInterview>("/questionnaire/interviews", mine),
          listResource<MediaWithUploader>("/media", { pageSize, uploadedBy: activeUserId }),
          apiFetch<DirectoryUser[]>("/users/directory")
        ]);
      if (cancelled) return;

      const items = <T,>(result: PromiseSettledResult<{ items: T[] }>): T[] =>
        result.status === "fulfilled" ? result.value.items : [];

      setRaw({
        ownerId: activeUserId,
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
    return () => {
      cancelled = true;
    };
  }, [user, activeUserId]);

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

  const viewingSelf = activeUserId === user?.id;
  const selectedEntry = directory.find((entry) => entry.id === activeUserId);
  const selectedName = viewingSelf ? user?.name : selectedEntry?.name;
  const selectedRole = viewingSelf ? user?.role : selectedEntry?.role;

  const groups = useMemo<ActivityGroup[] | null>(() => {
    // `null` is the page's "still loading" signal, and rows belonging to somebody else are exactly
    // that — not an empty result for the person now selected.
    if (!raw || !activeUserId || raw.ownerId !== activeUserId) return null;
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
