import { useId, type ReactNode } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import type { ErrorScreen } from "@/lib/errorKind";

export type ErrorTone = "alert" | "notice" | "quiet";

const TONE: Record<ErrorTone, string> = {
  alert: "bg-error-100 text-red-800 dark:bg-red-950/50 dark:text-red-300",
  notice: "bg-purple-50 text-purple-700 dark:bg-purple-950/50 dark:text-purple-300",
  quiet: "bg-surface-50 text-ink-700 ring-1 ring-line-200"
};

export function ErrorPanel({
  screen,
  icon,
  tone,
  title,
  line,
  status,
  reference,
  children
}: {
  screen: ErrorScreen;
  icon: ReactNode;
  tone: ErrorTone;
  title: string;
  line: string;
  status?: ReactNode;
  reference?: string | null;
  children?: ReactNode;
}) {
  const heading = useId();
  return (
    <section
      aria-labelledby={heading}
      data-testid={`error-${screen}`}
      className="panel mx-auto w-full max-w-xl px-6 py-12 text-center sm:px-10 sm:py-14"
    >
      <div className={`mx-auto mb-5 grid h-12 w-12 place-items-center rounded-full ${TONE[tone]}`}>{icon}</div>
      <h1 id={heading} className="font-display text-xl font-bold tracking-tight text-ink-900 sm:text-2xl">
        {title}
      </h1>
      <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-ink-500">{line}</p>
      {status ? <div className="mt-2 text-sm leading-6 text-ink-700">{status}</div> : null}
      {children ? <ActionGroup className="mt-7">{children}</ActionGroup> : null}
      {reference ? (
        <p className="mt-6 text-[11px] leading-4 text-ink-500" data-testid="error-reference">
          Ref <code className="break-all font-mono text-ink-700">{reference}</code>
        </p>
      ) : null}
    </section>
  );
}

export function ErrorBrand() {
  return (
    <div className="mb-8 flex items-center justify-center gap-2 text-ink-900" data-testid="error-brand">
      <FieldRepoLogo className="h-8 w-8 rounded-lg" />
      <span className="font-display text-base font-bold tracking-tight">Field Repository</span>
    </div>
  );
}
