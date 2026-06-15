import { Archive } from "lucide-react";

export function EmptyState({ title, body }: { title: string; body?: string }) {
  return (
    <div className="rounded-xl border border-dashed border-[#e6dfd8] bg-field-100 px-6 py-10 text-center">
      <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-field-200 text-field-600">
        <Archive className="h-5 w-5" aria-hidden />
      </div>
      <h2 className="text-base font-medium text-ink">{title}</h2>
      {body ? <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">{body}</p> : null}
    </div>
  );
}
