"use client";

import { useParams, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { ReportShareView } from "@/components/dataExplorer/ReportShareView";
import { ApiError } from "@/lib/api";
import { langOf, openShare, shareLabels, type ShareLabels, type ShareSnapshot } from "@/lib/dataExplorer/reportShare";

type Refusal = "expired" | "failed";

function refusal(error: unknown): Refusal {
  return error instanceof ApiError && error.status === 404 ? "expired" : "failed";
}

export default function ReportSharePage() {
  return (
    <Suspense fallback={null}>
      <ReportShare />
    </Suspense>
  );
}

function ReportShare() {
  const params = useParams<{ token: string }>();
  const lang = langOf(useSearchParams()?.get("lang"));
  const labels: ShareLabels = shareLabels(null, lang);
  const token = typeof params?.token === "string" ? params.token : "";
  const [snapshot, setSnapshot] = useState<ShareSnapshot | null>(null);
  const [error, setError] = useState<Refusal | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!token) return;
    let live = true;
    openShare(token)
      .then((answer) => {
        if (live) {
          setSnapshot(answer);
          setError(null);
        }
      })
      .catch((err) => {
        if (live) setError(refusal(err));
      });
    return () => {
      live = false;
    };
  }, [token, attempt]);

  const gone = error === "expired";

  return (
    <main className="min-h-dvh bg-bg-0 px-4 py-6 md:px-8 md:py-10" lang={snapshot?.locale ?? lang}>
      {snapshot ? (
        <ReportShareView snapshot={snapshot} />
      ) : (
        <div className="mx-auto mt-16 grid max-w-md gap-4 text-center">
          {error || !token ? (
            <div role="alert" className="panel grid gap-3 p-6">
              <p className="text-sm text-ink-700">{token && error ? labels[error] : labels.incomplete}</p>
              {token && !gone ? (
                <button type="button" className="field-button mx-auto" onClick={() => setAttempt((n) => n + 1)}>
                  {labels.tryAgain}
                </button>
              ) : null}
            </div>
          ) : (
            <p className="text-sm text-ink-500" role="status">{labels.opening}</p>
          )}
        </div>
      )}
    </main>
  );
}
