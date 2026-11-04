"use client";

import { useEffect, useRef, useState } from "react";

import { loadGoogleIdentity } from "@/lib/googleIdentity";
import { cn } from "@/lib/utils";

const MIN_WIDTH = 200;
const MAX_WIDTH = 400;

export function GoogleSignInButton({
  clientId,
  onCredential,
  onLoadError,
  disabled = false,
  text = "continue_with",
  className
}: {
  clientId: string;
  onCredential: (credential: string) => void;
  onLoadError?: () => void;
  disabled?: boolean;
  text?: "continue_with" | "signin_with" | "signup_with" | "signin";
  className?: string;
}) {
  const row = useRef<HTMLDivElement | null>(null);
  const host = useRef<HTMLDivElement | null>(null);
  const answer = useRef(onCredential);
  const failed = useRef(onLoadError);
  const [drawn, setDrawn] = useState(false);
  useEffect(() => {
    answer.current = onCredential;
    failed.current = onLoadError;
  });

  useEffect(() => {
    let live = true;
    let width = 0;
    let observer: ResizeObserver | null = null;

    const draw = () => {
      const outer = row.current;
      const inner = host.current;
      if (!live || !outer || !inner || !window.google) return;
      const available = outer.clientWidth;
      if (available === 0) return;
      const next = Math.round(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, available)));
      if (next === width) return;
      width = next;
      inner.replaceChildren();
      window.google.accounts.id.renderButton(inner, {
        type: "standard",
        theme: "outline",
        size: "large",
        shape: "rectangular",
        text,
        logo_alignment: "center",
        width: next
      });
      setDrawn(true);
    };

    loadGoogleIdentity()
      .then(() => {
        if (!live || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => answer.current(response.credential)
        });
        draw();
        if (row.current) {
          observer = new ResizeObserver(draw);
          observer.observe(row.current);
        }
      })
      .catch(() => {
        if (live) failed.current?.();
      });

    return () => {
      live = false;
      observer?.disconnect();
    };
  }, [clientId, text]);

  return (
    <div
      ref={row}
      data-google-button
      aria-disabled={disabled || undefined}
      inert={disabled || undefined}
      className={cn(
        "relative flex min-h-11 w-full min-w-0 items-center justify-center transition-opacity",
        disabled && "pointer-events-none cursor-not-allowed opacity-50",
        className
      )}
    >
      {drawn ? null : <span aria-hidden className="absolute inset-x-0 top-0.5 mx-auto h-10 max-w-[400px] motion-safe:animate-pulse rounded-[4px] bg-line-200/60" />}
      <div ref={host} className="flex min-h-10 justify-center [color-scheme:light]" />
    </div>
  );
}
