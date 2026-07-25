"use client";

/**
 * The pill switch used across the Settings surfaces (accessibility switches, the off-peak window).
 *
 * Lifted out of the settings page so the personal cards and the master-admin panel — which now live
 * in separate components and on separate routes — cannot drift into two slightly different switches.
 */
export function Toggle({
  checked,
  label,
  onChange,
  disabled
}: {
  checked: boolean;
  label: string;
  onChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      aria-label={label}
      aria-pressed={checked}
      className={`relative h-6 w-11 shrink-0 rounded-full transition disabled:cursor-not-allowed disabled:opacity-60 ${
        checked ? "bg-purple-700" : "bg-line-200"
      }`}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      type="button"
    >
      {/* Deliberately bg-white rather than the themed bg-card: the knob sits on a filled track, so
          it has to stay light in both themes to read as a knob at all. */}
      <span
        className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-all ${
          checked ? "left-[22px]" : "left-0.5"
        }`}
      />
    </button>
  );
}
