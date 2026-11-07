"use client";

import { useCallback, useEffect, useState } from "react";

import {
  attemptKey,
  hasAttempted,
  isOnline,
  MEDIA_REFRESH_BUDGET,
  presignedUrlExpiresSoon,
  refreshesSpent,
  refreshMediaUrl,
  resolveFailedMediaUrl,
  type RefreshableMedia
} from "@/lib/mediaUrlRefresh";

export function useMediaUrl(media: RefreshableMedia | null | undefined): {
  src: string | null;
  onError: () => void;
  failed: boolean;
} {
  const stored = media?.url ?? null;
  const mediaId = media?.id ?? null;
  const [fresh, setFresh] = useState<string | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);

  useEffect(() => {
    setFresh(null);
  }, [stored, mediaId]);

  useEffect(() => {
    if (!mediaId || !stored) return;
    if (!presignedUrlExpiresSoon(stored, Date.now())) return;
    if (hasAttempted(attemptKey(mediaId, stored))) return;
    if (refreshesSpent(mediaId) >= MEDIA_REFRESH_BUDGET) return;
    if (!isOnline()) return;

    let cancelled = false;
    void refreshMediaUrl(mediaId, stored).then((next) => {
      if (!cancelled && next && next !== stored) setFresh(next);
    });
    return () => {
      cancelled = true;
    };
  }, [mediaId, stored]);

  const src = fresh ?? stored;

  const onError = useCallback(() => {
    if (!media || !src) return;
    void resolveFailedMediaUrl({ id: mediaId, url: src }).then((next) => {
      if (next) setFresh(next);
      else setFailedUrl(src);
    });
  }, [media, mediaId, src]);

  return { src, onError, failed: src !== null && failedUrl === src };
}
