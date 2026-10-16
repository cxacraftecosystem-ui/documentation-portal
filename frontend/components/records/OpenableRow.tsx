"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { MouseEvent, ReactNode } from "react";

import { rowClickOpensRecord } from "@/lib/recordView";

/**
 * A list row whose whole surface opens the record's read-only view.
 *
 * ── TWO WAYS IN, ONE DESTINATION ─────────────────────────────────────────────────────────────────
 *
 * The keyboard and assistive route is a real `<Link>` — `RecordTitleLink`, in the row's first cell —
 * so the row is announced as a link, Tab reaches it, Enter opens it and the global purple focus ring
 * draws on it. The pointer route is this `<tr>`'s click, which goes to the same href. A `<tr>`
 * cannot itself be a link (wrapping cells in `<a>` is invalid table markup, and `role="link"` on a
 * row nests every button in the Actions column inside a link — the nested-interactive violation),
 * so the row is a click target for the mouse and a finger, and the title is the link for everybody.
 *
 * ── THE ROW'S OWN CONTROLS STAY THEIR OWN ────────────────────────────────────────────────────────
 *
 * Update, Discuss, Delete, a media preview: a click on any of them does that thing and never also
 * opens the record. `rowClickOpensRecord` checks where the click landed rather than relying on every
 * inner button remembering to stop propagation, so a control added to a row later cannot forget.
 * A click that ends a text selection is not an open either — somebody copying a name out of a row
 * should not be taken off the page.
 */
export function OpenableRow({
  href,
  onOpen,
  className,
  children
}: {
  href: string;
  /** Runs just before navigating — the list remembers its place here (`useListReturn`). */
  onOpen?: () => void;
  className?: string;
  children: ReactNode;
}) {
  const open = useOpenOnClick(href, onOpen);
  return (
    <tr className={`cursor-pointer transition hover:bg-field-100 ${className ?? ""}`} onClick={open}>
      {children}
    </tr>
  );
}

/**
 * The same whole-surface click for a list item drawn as a card or a `<div>` row rather than a table
 * row (search results). Same rules: the title inside is the real link, inner controls keep their own
 * clicks. `external` opens a stored file in a new tab, as its title link does.
 */
export function OpenableBlock({
  href,
  external = false,
  className,
  children
}: {
  href: string;
  external?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const open = useOpenOnClick(href, undefined, external);
  return (
    <div className={`cursor-pointer transition hover:bg-field-100 ${className ?? ""}`} onClick={open}>
      {children}
    </div>
  );
}

function useOpenOnClick(href: string, onOpen?: () => void, external = false) {
  const router = useRouter();
  return (event: MouseEvent<HTMLElement>) => {
    if (!rowClickOpensRecord(event.target as Element, event.currentTarget)) return;
    const selection = typeof window !== "undefined" ? window.getSelection()?.toString() : "";
    if (selection) return;
    // Ctrl/Cmd-click opens a new tab exactly as it would on the title link.
    if (external || event.metaKey || event.ctrlKey) {
      window.open(href, "_blank", "noopener");
      return;
    }
    onOpen?.();
    router.push(href);
  };
}

/** The row's title, as the real link the keyboard and screen readers use (see `OpenableRow`). */
export function RecordTitleLink({ href, onOpen, children }: { href: string; onOpen?: () => void; children: ReactNode }) {
  return (
    <Link href={href} onClick={onOpen} className="font-medium text-ink-900 hover:text-purple-700 hover:underline dark:hover:text-purple-300">
      {children}
    </Link>
  );
}
