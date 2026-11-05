import { ApiError } from "@/lib/api";

export type GoMedia = { url: string; fileName?: string | null; mediaId?: string | null };
export type GoOpen = { kind: string; id: string; href: string };
export type GoTranscript = { mediaId: string; format: string; apiPath: string; openHref?: string | null };

export const GO_UNAVAILABLE = "This isn't here any more. It may have been removed.";
export const GO_NO_ACCESS = "You need access to this. Ask its owner or an admin.";
export const GO_RETRY = "That link didn't answer. Try again in a moment.";

export type GoProblem = { title: string; message: string; retry: boolean };

export function goMediaPath(id: string): string {
  return `/data/go/media/${encodeURIComponent(id)}/download?as=json`;
}

export function goOpenPath(kind: string, id: string): string {
  return `/data/go/open/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`;
}

export function goTranscriptPath(id: string, format: string): string {
  return `/data/go/transcript/${encodeURIComponent(id)}/${encodeURIComponent(format)}`;
}

export function safeInternalHref(href: string | null | undefined): string | null {
  if (!href || !href.startsWith("/") || href.startsWith("//")) return null;
  return href;
}

export function goFailure(error: unknown): GoProblem {
  if (error instanceof ApiError && error.status === 403) return { title: "Access required", message: GO_NO_ACCESS, retry: false };
  if (error instanceof ApiError && error.status === 404) return { title: "Not available", message: GO_UNAVAILABLE, retry: false };
  return { title: "Couldn't open the link", message: GO_RETRY, retry: true };
}

export const GO_MISSING: GoProblem = { title: "Not available", message: GO_UNAVAILABLE, retry: false };
