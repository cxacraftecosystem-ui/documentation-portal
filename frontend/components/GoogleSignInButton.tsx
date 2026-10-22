"use client";

/**
 * GOOGLE'S OWN BUTTON, DRAWN VISIBLY — the one way "Continue with Google" is put on a screen.
 *
 * Used by `/login` and by the Settings card that confirms a first password. Identical in both
 * portals; keep it that way.
 *
 * WHY NOT OUR CHROME WITH GOOGLE'S BUTTON LAID OVER IT, TRANSPARENT. That is what both sign-in
 * pages did until 2026-10-22: a styled row of ours, and Google's real button stretched over it at
 * `opacity: 0` with a `scaleY` transform. It was reported from a tablet as "the button highlights
 * and nothing opens" — our row took the touch highlight while the tap was meant for a cross-origin
 * frame the person could not see. An invisible, transformed third-party frame is exactly the shape
 * browsers and Google treat as clickjacking, and nothing about it can be tested on every tablet
 * browser there is. Google's branding rules ask for their button anyway. So the button the person
 * sees is the button they tap: Google's, at full row width (Google caps it at 400 px), centred.
 *
 * `disabled` greys it and takes it out of reach (`inert`, no pointer events). It is a courtesy: the
 * load-bearing guard stays in the caller's callback, the only one that holds whatever Google renders.
 */

import { useEffect, useRef, useState } from "react";

import { loadGoogleIdentity } from "@/lib/googleIdentity";
import { cn } from "@/lib/utils";

/** Google's limits for a rendered button's width, in CSS pixels. */
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
  /** Google's ID token. Called only after Google has signed the person in. */
  onCredential: (credential: string) => void;
  /** The script could not be loaded (offline, blocked). */
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

    // Google takes a pixel width, so the button is redrawn when the row changes width — and only
    // then: a redraw replaces the frame, and doing it on every resize tick flickers.
    const draw = () => {
      const outer = row.current;
      const inner = host.current;
      if (!live || !outer || !inner || !window.google) return;
      const available = outer.clientWidth;
      if (available === 0) return; // not laid out yet; the observer calls again when it is
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
        // `initialize` replaces Google's whole client configuration, so whichever screen called it
        // last owns the callback; the screens that draw this button are never mounted together.
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
      {/* Holds the row's height while Google's script arrives, so nothing below jumps. */}
      {drawn ? null : <span aria-hidden className="absolute inset-x-0 top-0.5 mx-auto h-10 max-w-[400px] motion-safe:animate-pulse rounded-[4px] bg-line-200/60" />}
      <div ref={host} className="flex min-h-10 justify-center [color-scheme:light]" />
    </div>
  );
}
