"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { ReportShareView } from "@/components/dataExplorer/ReportShareView";
import { ApiError } from "@/lib/api";
import { openShare, type ShareSnapshot } from "@/lib/dataExplorer/reportShare";

function refusal(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) return "This link has expired or was turned off.";
  return "Couldn't open this report. Check your connection and try again.";
}

export default function ReportSharePage() {
  const params = useParams<{ token: string }>();
  const token = typeof params?.token === "string" ? params.token : "";
  const [snapshot, setSnapshot] = useState<ShareSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
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

  const gone = error !== null && error.startsWith("This link");

  return (
    <main className="min-h-dvh bg-bg-0 px-4 py-6 md:px-8 md:py-10">
      {snapshot ? (
        <ReportShareView snapshot={snapshot} />
      ) : (
        <div className="mx-auto mt-16 grid max-w-md gap-4 text-center">
          {error || !token ? (
            <div role="alert" className="panel grid gap-3 p-6">
              <p className="text-sm text-ink-700">{token ? error : "This link is incomplete. Open the whole link."}</p>
              {token && !gone ? (
                <button type="button" className="field-button mx-auto" onClick={() => setAttempt((n) => n + 1)}>
                  Try again
                </button>
              ) : null}
            </div>
          ) : (
            <p className="text-sm text-ink-500" role="status">Opening the report…</p>
          )}
        </div>
      )}
    </main>
  );
}
