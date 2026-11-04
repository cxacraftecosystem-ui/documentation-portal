"use client";

import { useEffect, useState } from "react";
import { MessageSquare, Star } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { handleFormEnter } from "@/lib/formNav";
import { isMasterAdmin } from "@/lib/permissions";
import { ButtonRow } from "@/components/ui/ButtonRow";

type Feedback = {
  id?: string;
  userId?: string;
  rating?: number | null;
  easeOfUse?: number | null;
  reliability?: number | null;
  performance?: number | null;
  design?: number | null;
  features?: number | null;
  recommend?: number | null;
  comment?: string | null;
  likeMost?: string | null;
  improve?: string | null;
  bugs?: string | null;
  featureRequests?: string | null;
  role?: string | null;
  createdAt?: string;
  updatedAt?: string;
  user?: { id: string; name: string; email: string; role: string } | null;
};

const RATING_FIELDS = [
  { key: "easeOfUse", label: "Ease of use" },
  { key: "reliability", label: "Reliability / stability" },
  { key: "performance", label: "Speed / performance" },
  { key: "design", label: "Design / look & feel" },
  { key: "features", label: "Features / completeness" },
  { key: "recommend", label: "How likely you'd recommend it" }
] as const;

const TEXT_FIELDS = [
  {
    key: "likeMost",
    label: "What do you like most?",
    placeholder: "What works well",
    rows: 2
  },
  {
    key: "improve",
    label: "What should we improve?",
    placeholder: "What slows you down",
    rows: 2
  },
  {
    key: "bugs",
    label: "Any bugs or issues you hit?",
    placeholder: "What happened, and what you expected",
    rows: 2
  },
  {
    key: "featureRequests",
    label: "Features you'd like to see",
    placeholder: "What you’d like to do here",
    rows: 2
  },
  {
    key: "comment",
    label: "Anything else (general comments)",
    placeholder: "Anything the questions above did not cover.",
    rows: 3
  }
] as const;

const OVERALL_WORDS = ["Tap a star to rate", "Poor", "Fair", "Good", "Very good", "Excellent"];

type RatingKey = (typeof RATING_FIELDS)[number]["key"] | "rating";
type TextKey = (typeof TEXT_FIELDS)[number]["key"] | "role";

function StarRating({
  value,
  onChange,
  label,
  size = "md"
}: {
  value: number | null;
  onChange: (value: number | null) => void;
  label: string;
  size?: "sm" | "md";
}) {
  const star = size === "sm" ? "h-5 w-5" : "h-6 w-6";
  return (
    <div className="flex items-center gap-1" role="group" aria-label={label}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          aria-label={`${label}: ${n} star${n > 1 ? "s" : ""}`}
          aria-pressed={value === n}
          className="rounded-md p-1 transition hover:bg-purple-50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600"
          key={n}
          onClick={() => onChange(value === n ? null : n)}
          type="button"
        >
          <Star className={`${star} ${value && n <= value ? "fill-purple-700 text-purple-700 dark:text-purple-300" : "text-line-200"}`} aria-hidden />
        </button>
      ))}
      <span className="ml-2 text-xs text-ink-500">{value ? `${value}/5` : "Not rated"}</span>
    </div>
  );
}

function AspectRating({
  label,
  value,
  onChange
}: {
  label: string;
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-t border-line-200 py-2 first:border-t-0">
      <span className="text-sm text-ink-900">{label}</span>
      <StarRating label={label} value={value} onChange={onChange} size="sm" />
    </div>
  );
}

export default function FeedbackPage() {
  const { user } = useAuth();
  const [saved, setSaved] = useState<Feedback | null>(null);
  const [ratings, setRatings] = useState<Record<RatingKey, number | null>>({
    rating: null,
    easeOfUse: null,
    reliability: null,
    performance: null,
    design: null,
    features: null,
    recommend: null
  });
  const [texts, setTexts] = useState<Record<TextKey, string>>({
    role: "",
    likeMost: "",
    improve: "",
    bugs: "",
    featureRequests: "",
    comment: ""
  });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [allFeedback, setAllFeedback] = useState<Feedback[] | null>(null);
  const [allError, setAllError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<Feedback>("/feedback/me")
      .then((mine) => {
        if (!mine?.id) return;
        setSaved(mine);
        setRatings({
          rating: mine.rating ?? null,
          easeOfUse: mine.easeOfUse ?? null,
          reliability: mine.reliability ?? null,
          performance: mine.performance ?? null,
          design: mine.design ?? null,
          features: mine.features ?? null,
          recommend: mine.recommend ?? null
        });
        setTexts({
          role: mine.role ?? "",
          likeMost: mine.likeMost ?? "",
          improve: mine.improve ?? "",
          bugs: mine.bugs ?? "",
          featureRequests: mine.featureRequests ?? "",
          comment: mine.comment ?? ""
        });
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load your feedback"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!isMasterAdmin(user)) return;
    apiFetch<Feedback[]>("/feedback")
      .then(setAllFeedback)
      .catch((err) => setAllError(err instanceof Error ? err.message : "Unable to load all feedback"));
  }, [user]);

  function setRating(key: RatingKey, value: number | null) {
    setRatings((current) => ({ ...current, [key]: value }));
  }

  function setText(key: TextKey, value: string) {
    setTexts((current) => ({ ...current, [key]: value }));
  }

  const anyProvided =
    Object.values(ratings).some((value) => Boolean(value)) || Object.values(texts).some((value) => value.trim().length > 0);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!anyProvided) {
      setSuccess(null);
      setError("Add a rating or an answer.");
      return;
    }
    setBusy(true);
    setSuccess(null);
    setError(null);
    try {
      const result = await apiFetch<Feedback>("/feedback/me", {
        method: "PUT",
        body: JSON.stringify({
          ...ratings,
          ...Object.fromEntries(Object.entries(texts).map(([key, value]) => [key, value.trim() || null]))
        })
      });
      setSaved(result);
      setSuccess("Thanks — feedback saved.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save feedback");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Give app feedback"
        description="Everything is optional. Update it any time."
        icon={<MessageSquare className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {success ? (
        <div className="mb-4 rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">{success}</div>
      ) : null}

      {loading ? (
        <div className="panel max-w-2xl p-5 text-sm text-ink-500">Loading your feedback...</div>
      ) : (
        <form className="panel max-w-2xl p-5" onSubmit={submit} onKeyDown={handleFormEnter}>
          <h2 className="font-display text-lg font-bold text-ink-900">Ratings</h2>

          <label className="field-label mt-3 block">Overall rating</label>
          <div className="mt-1.5">
            <StarRating label="Overall rating" value={ratings.rating} onChange={(value) => setRating("rating", value)} />
          </div>
          <p className="mt-1 text-xs text-ink-500">{OVERALL_WORDS[ratings.rating ?? 0]}</p>

          <div className="mt-4 border-t border-line-200 pt-1">
            {RATING_FIELDS.map((aspect) => (
              <AspectRating
                key={aspect.key}
                label={aspect.label}
                value={ratings[aspect.key]}
                onChange={(value) => setRating(aspect.key, value)}
              />
            ))}
          </div>

          <h2 className="mt-6 border-t border-line-200 pt-5 font-display text-lg font-bold text-ink-900">In your words</h2>

          <label className="field-label mt-3 block" htmlFor="feedback-role">
            Your role (e.g. researcher, field documenter)
          </label>
          <input
            className="field-input mt-1.5"
            id="feedback-role"
            placeholder="Researcher, field documenter, reviewer..."
            value={texts.role}
            onChange={(event) => setText("role", event.target.value)}
          />

          {TEXT_FIELDS.map((prompt) => (
            <div key={prompt.key}>
              <label className="field-label mt-4 block" htmlFor={`feedback-${prompt.key}`}>
                {prompt.label}
              </label>
              <textarea
                className="field-input mt-1.5"
                id={`feedback-${prompt.key}`}
                rows={prompt.rows}
                placeholder={prompt.placeholder}
                value={texts[prompt.key]}
                onChange={(event) => setText(prompt.key, event.target.value)}
              />
            </div>
          ))}

          <ButtonRow className="mt-5">
            <button className="field-button" disabled={busy || !anyProvided} type="submit">
              {busy ? "Saving..." : saved ? "Update feedback" : "Submit feedback"}
            </button>
          </ButtonRow>
          {saved?.updatedAt ? (
            <p className="mt-2 text-center text-xs text-ink-500">Last updated {formatDateTime(saved.updatedAt)}</p>
          ) : null}
        </form>
      )}

      {saved ? (
        <section className="panel mt-4 max-w-2xl p-5">
          <h2 className="font-display font-bold text-ink-900">Your saved feedback</h2>
          <SavedFeedbackDetail feedback={saved} />
        </section>
      ) : null}

      {isMasterAdmin(user) ? (
        <section className="mt-8">
          <h2 className="mb-3 font-display text-lg font-bold text-ink-900">All feedback</h2>
          {allError ? (
            <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{allError}</div>
          ) : null}
          {!allFeedback ? (
            <div className="panel p-4 text-sm text-ink-500">Loading...</div>
          ) : allFeedback.length === 0 ? (
            <div className="panel p-4">
              <EmptyState title="No feedback yet" body="Feedback will appear here." />
            </div>
          ) : (
            <div className="grid gap-3">
              {allFeedback.map((item) => (
                <article className="panel p-4" key={item.id}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="text-sm font-medium text-ink-900">
                      {item.user?.name ?? "Unknown user"}
                      <span className="ml-2 text-xs font-normal text-ink-500">{item.user?.email}</span>
                    </div>
                    <div className="text-xs text-ink-500">{formatDateTime(item.updatedAt)}</div>
                  </div>
                  <SavedFeedbackDetail feedback={item} />
                </article>
              ))}
            </div>
          )}
        </section>
      ) : null}
    </>
  );
}

function SavedFeedbackDetail({ feedback }: { feedback: Feedback }) {
  const scores = [
    { label: "Overall", value: feedback.rating },
    { label: "Ease of use", value: feedback.easeOfUse },
    { label: "Reliability", value: feedback.reliability },
    { label: "Performance", value: feedback.performance },
    { label: "Design", value: feedback.design },
    { label: "Features", value: feedback.features },
    { label: "Would recommend", value: feedback.recommend }
  ].filter((entry) => Boolean(entry.value));

  const answers = [
    { label: "Likes most", value: feedback.likeMost },
    { label: "To improve", value: feedback.improve },
    { label: "Bugs / issues", value: feedback.bugs },
    { label: "Feature requests", value: feedback.featureRequests },
    { label: "General comments", value: feedback.comment }
  ].filter((entry) => Boolean(entry.value));

  if (!scores.length && !answers.length && !feedback.role) {
    return <p className="mt-2 text-sm text-ink-500">No details provided.</p>;
  }

  return (
    <div className="mt-2 grid gap-2">
      {feedback.role ? (
        <p className="text-xs text-ink-500">
          <span className="font-medium text-ink-900">Role:</span> {feedback.role}
        </p>
      ) : null}
      {scores.length ? (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-500">
          {scores.map((entry) => (
            <span key={entry.label}>
              {entry.label}: {entry.value}/5
            </span>
          ))}
        </div>
      ) : null}
      {answers.map((entry) => (
        <p className="text-sm leading-6 text-ink-700" key={entry.label}>
          <span className="font-medium text-ink-900">{entry.label}:</span>{" "}
          <span className="whitespace-pre-wrap">{entry.value}</span>
        </p>
      ))}
    </div>
  );
}
