export function RequiredMark({ when }: { when?: boolean }) {
  if (when !== true) return null;
  return <span className="text-error-600 dark:text-red-400"> *</span>;
}
