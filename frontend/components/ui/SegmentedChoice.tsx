"use client";

export function SegmentedChoice({
  label,
  value,
  options,
  onChange,
  disabled = false
}: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <div
      role="group"
      aria-label={label}
      className="inline-flex w-fit max-w-full overflow-hidden rounded-md border border-line-200 bg-card"
    >
      {options.map((option) => {
        const on = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={on}
            disabled={disabled}
            onClick={() => {
              if (!on) onChange(option.value);
            }}
            className={`min-h-10 px-3 text-sm font-medium transition-colors pointer-coarse:min-h-11 disabled:opacity-60 ${
              on ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-purple-50 hover:text-purple-700"
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
