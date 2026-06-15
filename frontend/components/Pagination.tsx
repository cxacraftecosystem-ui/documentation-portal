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
  return (
    <div className="flex flex-col gap-2 border-t border-neutral-200 px-4 py-3 text-sm text-neutral-600 sm:flex-row sm:items-center sm:justify-between">
      <span>
        Page {pages ? page : 0} of {pages} · {total} records
      </span>
      <div className="flex gap-2">
        <button className="field-button-secondary" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="field-button-secondary" disabled={page >= pages} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
