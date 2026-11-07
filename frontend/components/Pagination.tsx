import { ButtonRow } from "@/components/ui/ButtonRow";
export function Pagination({
  page,
  pages,
  total,
  onPage
}: {
  page: number;
  pages: number;
  total: number;
  onPage: (page: number) => void;
}) {
  if (total <= 0) return null;
  const countLabel = `${total.toLocaleString("en-IN")} ${total === 1 ? "record" : "records"}`;
  return (
    <div className="flex flex-col gap-2 border-t border-line-200 px-4 py-3 text-sm text-ink-700 sm:flex-row sm:items-center sm:justify-between">
      <span>{pages > 1 ? `Page ${page} of ${pages} · ${countLabel}` : countLabel}</span>
      {pages > 1 ? (
        <ButtonRow>
          <button className="field-button-secondary" disabled={page <= 1} onClick={() => onPage(page - 1)}>
            Previous
          </button>
          <button className="field-button-secondary" disabled={page >= pages} onClick={() => onPage(page + 1)}>
            Next
          </button>
        </ButtonRow>
      ) : null}
    </div>
  );
}
