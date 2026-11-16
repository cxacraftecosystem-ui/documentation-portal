"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";

import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { ProgressBar } from "@/components/ui/ProgressBar";
import type { Session } from "@/lib/questionnaire/api";
import { languageLabel } from "@/lib/questionnaire/languages";
import { shortDate } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import { todayLocal } from "@/lib/questionnaire/sessionsList";

export function headerNames(session: Pick<Session, "participants" | "respondents" | "title">): string {
  const names = [...session.participants.map((p) => p.name), ...(session.respondents ?? []).filter((r) => !r.artisanId).map((r) => r.name)].filter(Boolean);
  if (!names.length) return session.title;
  if (names.length === 1) return names[0];
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return qs("andOthers", { name: names[0], count: names.length - 1 });
}

function dayLabel(sessionDate: string | null, today: string = todayLocal()): string {
  if (!sessionDate) return "";
  return sessionDate.slice(0, 10) === today ? qs("today") : shortDate(sessionDate);
}

export function headerLine(session: Pick<Session, "crafts" | "languageCode" | "languageOther" | "sessionDate">): string {
  const language = session.languageCode ? languageLabel(session.languageCode) || session.languageOther || "" : (session.languageOther ?? "");
  return [session.crafts?.[0] ?? "", language, dayLabel(session.sessionDate)].filter(Boolean).join(" · ");
}

export function SessionHeader({
  session,
  answered,
  total,
  sectionsDone,
  sectionsTotal,
  savePill,
  menu,
  onReview
}: {
  session: Session;
  answered: number;
  total: number;
  sectionsDone: number;
  sectionsTotal: number;
  savePill: ReactNode;
  menu: ReactNode;
  onReview?: () => void;
}) {
  const ref = useRef<HTMLElement | null>(null);
  const router = useRouter();
  const interceptLeave: (leave: () => void) => boolean = useLeaveInterceptor();
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const root = document.documentElement;
    const set = () => root.style.setProperty("--session-header-h", `${Math.ceil(node.getBoundingClientRect().height)}px`);
    set();
    const observer = new ResizeObserver(set);
    observer.observe(node);
    return () => {
      observer.disconnect();
      root.style.removeProperty("--session-header-h");
    };
  }, []);
  const percent = total > 0 ? Math.round((answered / total) * 100) : 0;
  return (
    <header ref={ref} className="sticky top-0 z-10 -mx-4 grid gap-3 border-b border-line-200 bg-bg-0 px-4 py-3 lg:top-17 lg:before:pointer-events-none lg:before:absolute lg:before:inset-x-0 lg:before:bottom-full lg:before:h-17 lg:before:bg-bg-0 lg:before:content-['']" data-session-header>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
        <Link
          href="/questionnaire"
          onClick={(event) => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            if (interceptLeave(() => router.push("/questionnaire"))) event.preventDefault();
          }}
          aria-label={qs("backToSessions")}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:border-purple-300"
        >
          <ChevronLeft className="h-5 w-5" aria-hidden />
        </Link>
        <div className="grid min-w-0 flex-[1_1_0%] gap-0.5 md:flex-[1_1_14rem]">
          <h1 className="display-title truncate text-xl leading-7 md:text-[2rem] md:leading-10" data-session-title title={session.title}>
            {headerNames(session)}
          </h1>
          <span className="truncate text-sm text-ink-500">{headerLine(session)}</span>
        </div>
        <div className="flex shrink-0 items-center gap-1 sm:gap-2.5 md:order-last">
          {onReview ? (
            <button
              type="button"
              className="field-button min-h-11 max-sm:bg-transparent max-sm:px-2 max-sm:text-base max-sm:font-semibold max-sm:text-purple-700 max-sm:hover:bg-purple-50 max-sm:hover:shadow-none sm:px-5 sm:font-semibold dark:max-sm:text-purple-300 dark:max-sm:hover:bg-purple-700/20"
              onClick={onReview}
              data-review-link
            >
              <span className="sm:hidden">{qs("review")}</span>
              <span className="hidden sm:inline">{qs("reviewAndFinish")}</span>
            </button>
          ) : null}
          {menu}
        </div>
        <div className="grid min-w-0 flex-[1_1_100%] gap-2 md:flex-[1_1_18rem]">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm text-ink-900">
              <span className="font-semibold" data-question-progress>
                {qs("questionsOfTotal", { answered, total })}
              </span>
              {sectionsTotal > 0 ? <span className="text-ink-500"> · {qs("sectionsOfTotal", { done: sectionsDone, total: sectionsTotal })}</span> : null}
            </span>
            {savePill}
          </div>
          <ProgressBar percent={percent} role="progressbar" aria-label={qs("questionsAnswered", { answered, total })} aria-valuemin={0} aria-valuemax={total} aria-valuenow={answered} />
        </div>
      </div>
    </header>
  );
}
