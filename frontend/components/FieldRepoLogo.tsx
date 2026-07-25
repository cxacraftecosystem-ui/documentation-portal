/**
 * THE Field Repository logo — the Android launcher icon, verbatim
 * (android/app/src/main/res/drawable/ic_launcher.xml): an 8-point terracotta
 * star with a near-black center disc on cream. Its colors are brand-native and
 * are never re-themed; on dark surfaces keep the cream tile.
 */
export function FieldRepoLogo({
  className,
  tile = true
}: {
  className?: string;
  /** Render the cream background tile (matches the app icon). */
  tile?: boolean;
}) {
  return (
    <svg viewBox="0 0 108 108" className={className} aria-hidden focusable="false">
      {tile ? <rect width="108" height="108" rx="24" fill="#FAF9F5" /> : null}
      <path
        d="M54 14l7 27 27-7-20 20 20 20-27-7-7 27-7-27-27 7 20-20-20-20 27 7z"
        fill="#CC785C"
      />
      <circle cx="54" cy="54" r="15" fill="#181715" />
    </svg>
  );
}
