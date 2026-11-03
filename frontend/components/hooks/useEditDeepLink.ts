"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { apiFetch } from "@/lib/api";

export const EDIT_PARAM = "edit";
export const NEW_PARAM = "new";

type EditDeepLinkOptions<T> = {
  endpoint: string;
  basePath: string;
  targetRef?: RefObject<HTMLElement | null>;
  onEdit: (record: T) => void;
  onNew?: () => void;
  onError: (message: string) => void;
  allowed: boolean;
  errorMessage: string;
};

export function useEditDeepLink<T>({
  endpoint,
  basePath,
  targetRef,
  onEdit,
  onNew,
  onError,
  allowed,
  errorMessage
}: EditDeepLinkOptions<T>): { loading: boolean } {
  const router = useRouter();
  const searchParams = useSearchParams();
  const reduce = useAppReducedMotion();
  const [loading, setLoading] = useState(false);

  const handled = useRef<string | null>(null);

  const callbacks = useRef({ onEdit, onNew, onError });
  useEffect(() => {
    callbacks.current = { onEdit, onNew, onError };
  });

  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  const handlesNew = onNew !== undefined;
  const editId = searchParams.get(EDIT_PARAM);
  const wantsNew = handlesNew && searchParams.get(NEW_PARAM) === "1";
  const searchString = searchParams.toString();

  useEffect(() => {
    const token = editId ? `edit:${editId}` : wantsNew ? "new" : null;
    if (!token) {
      handled.current = null;
      return;
    }
    if (handled.current === token) return;
    handled.current = token;

    const scrollToForm = () => {
      requestAnimationFrame(() => {
        const behavior: ScrollBehavior = reduceRef.current ? "auto" : "smooth";
        if (targetRef?.current) targetRef.current.scrollIntoView({ behavior, block: "start" });
        else window.scrollTo({ top: 0, behavior });
      });
    };

    const spend = () => {
      const next = new URLSearchParams(searchString);
      next.delete(EDIT_PARAM);
      if (handlesNew) next.delete(NEW_PARAM);
      const query = next.toString();
      router.replace(query ? `${basePath}?${query}` : basePath, { scroll: false });
    };

    if (!allowed) {
      spend();
      return;
    }

    if (wantsNew && !editId) {
      callbacks.current.onNew?.();
      spend();
      scrollToForm();
      return;
    }

    let cancelled = false;
    setLoading(true);
    apiFetch<T>(`${endpoint}/${editId}`)
      .then((record) => {
        if (cancelled) return;
        callbacks.current.onEdit(record);
        scrollToForm();
      })
      .catch((err) => {
        if (cancelled) return;
        callbacks.current.onError(err instanceof Error ? err.message : errorMessage);
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        spend();
      });

    return () => {
      cancelled = true;
      if (handled.current === token) handled.current = null;
    };
  }, [
    editId,
    wantsNew,
    handlesNew,
    allowed,
    basePath,
    endpoint,
    errorMessage,
    router,
    searchString,
    targetRef
  ]);

  return { loading };
}
