"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { MouseEvent, ReactNode } from "react";

import { rowClickOpensRecord } from "@/lib/recordView";

export function OpenableRow({
  href,
  onOpen,
  navigate,
  className,
  children
}: {
  href: string;
  onOpen?: () => void;
  navigate?: (href: string) => void;
  className?: string;
  children: ReactNode;
}) {
  const open = useOpenOnClick(href, onOpen, false, navigate);
  return (
    <tr className={`cursor-pointer transition hover:bg-field-100 ${className ?? ""}`} onClick={open}>
      {children}
    </tr>
  );
}

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

function useOpenOnClick(href: string, onOpen?: () => void, external = false, navigate?: (href: string) => void) {
  const router = useRouter();
  return (event: MouseEvent<HTMLElement>) => {
    if (!rowClickOpensRecord(event.target as Element, event.currentTarget)) return;
    const selection = typeof window !== "undefined" ? window.getSelection()?.toString() : "";
    if (selection) return;
    if (external || event.metaKey || event.ctrlKey) {
      window.open(href, "_blank", "noopener");
      return;
    }
    onOpen?.();
    if (navigate) navigate(href);
    else router.push(href);
  };
}

export function RecordTitleLink({
  href,
  onOpen,
  navigate,
  children
}: {
  href: string;
  onOpen?: () => void;
  navigate?: (href: string) => void;
  children: ReactNode;
}) {
  const click = (event: MouseEvent<HTMLAnchorElement>) => {
    if (navigate && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
      event.preventDefault();
      onOpen?.();
      navigate(href);
      return;
    }
    onOpen?.();
  };
  return (
    <Link href={href} onClick={click} className="font-medium text-ink-900 hover:text-purple-700 hover:underline dark:hover:text-purple-300">
      {children}
    </Link>
  );
}
