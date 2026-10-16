import { BackButton } from "@/components/BackButton";

export function PageHeader({
  title,
  description,
  actions,
  icon,
  back = true
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
  icon?: React.ReactNode;
  /** Round back control before the title; the dashboard opts out with back={false}. */
  back?: boolean;
}) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex items-start gap-3">
        {back ? (
          <div className="mt-1">
            <BackButton />
          </div>
        ) : null}
        {/* UX audit DC-14: the chip had no `shrink-0` and squashed beside a long title on a phone.
            It is hidden below `sm`, where the title needs the width more, and cannot shrink above. */}
        {icon ? (
          <div className="mt-1 hidden h-10 w-10 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300 sm:grid">
            {icon}
          </div>
        ) : null}
        <div className="min-w-0">
          <h1 className="display-title text-2xl sm:text-3xl md:text-4xl">{title}</h1>
          {description ? <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">{description}</p> : null}
        </div>
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}
