export const REVEAL_PEEK_HOLD_MS = 220;

export const REVEAL_DRAG_SLOP_PX = 3;

export interface RevealTransform {
  readonly zoom: number;
  readonly panX: number;
  readonly panY: number;
}

export const REVEAL_AT_FIT: RevealTransform = { zoom: 1, panX: 0, panY: 0 };

export function isAtFit(transform: RevealTransform): boolean {
  return transform.zoom <= 1.001 && transform.panX === 0 && transform.panY === 0;
}

const finite = (value: number, fallback: number) => (Number.isFinite(value) ? value : fallback);

export function clampZoom(zoom: number, maxZoom: number): number {
  const ceiling = Math.max(1, Number.isNaN(maxZoom) ? 1 : maxZoom);
  return Math.min(ceiling, Math.max(1, finite(zoom, 1)));
}

export function clampPan(transform: RevealTransform, frameWidth: number, frameHeight: number): RevealTransform {
  const zoom = clampZoom(transform.zoom, Number.POSITIVE_INFINITY);
  const slackX = Math.max(0, (finite(frameWidth, 0) * (zoom - 1)) / 2);
  const slackY = Math.max(0, (finite(frameHeight, 0) * (zoom - 1)) / 2);
  return {
    zoom,
    panX: Math.max(-slackX, Math.min(slackX, finite(transform.panX, 0))),
    panY: Math.max(-slackY, Math.min(slackY, finite(transform.panY, 0)))
  };
}

export function panBy(
  transform: RevealTransform,
  dx: number,
  dy: number,
  frameWidth: number,
  frameHeight: number
): RevealTransform {
  return clampPan(
    { zoom: transform.zoom, panX: transform.panX + finite(dx, 0), panY: transform.panY + finite(dy, 0) },
    frameWidth,
    frameHeight
  );
}

export function zoomAbout(
  transform: RevealTransform,
  factor: number,
  pointerX: number,
  pointerY: number,
  frameWidth: number,
  frameHeight: number,
  maxZoom: number
): RevealTransform {
  const from = clampZoom(transform.zoom, maxZoom);
  const to = clampZoom(from * finite(factor, 1), maxZoom);
  const width = finite(frameWidth, 0);
  const height = finite(frameHeight, 0);
  const dx = finite(pointerX, width / 2) - width / 2;
  const dy = finite(pointerY, height / 2) - height / 2;
  const ratio = from === 0 ? 1 : to / from;
  return clampPan(
    { zoom: to, panX: dx - (dx - transform.panX) * ratio, panY: dy - (dy - transform.panY) * ratio },
    width,
    height
  );
}

export function wrapperPercent(seamPercent: number, zoom: number, pan: number, frameLength: number): number {
  const length = finite(frameLength, 0);
  const magnification = clampZoom(zoom, Number.POSITIVE_INFINITY);
  if (length <= 0) return finite(seamPercent, 0);
  const panPercent = (finite(pan, 0) / length) * 100;
  return (finite(seamPercent, 0) - 50 - panPercent) / magnification + 50;
}

export function zoomLabel(zoom: number): string {
  return `${Math.round(clampZoom(zoom, Number.POSITIVE_INFINITY) * 10) / 10}×`;
}
