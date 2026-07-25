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

/**
 * The backend keeps ONE feedback row per user (PUT /feedback/me upserts it), with an
 * overall 1-5 rating, per-aspect sub-ratings and several free-text prompts. GET /feedback/me
 * returns the row (or {} when the user hasn't given feedback yet).
 */
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

/** The six 1-5 aspects, in the Android screen's order and with its exact wording. */
const RATING_FIELDS = [
  { key: "easeOfUse", label: "Ease of use" },
  { key: "reliability", label: "Reliability / stability" },
  { key: "performance", label: "Speed / performance" },
  { key: "design", label: "Design / look & feel" },
  { key: "features", label: "Features / completeness" },
  { key: "recommend", label: "How likely you'd recommend it" }
] as const;

/**
 * The six free-text prompts, again worded exactly as Android words them. The placeholders are web-only
 * — a phone keyboard covers half the screen, so Android leaves the box bare, but on a laptop an empty
 * textarea with a one-line label gets a one-line answer. Naming an example of a useful answer is what
 * turns "it's fine" into something the team can act on.
 */
const TEXT_FIELDS = [
  {
    key: "likeMost",
    label: "What do you like most?",
    placeholder: "The part of the app you would not want taken away.",
    rows: 2
  },
  {
    key: "improve",
    label: "What should we improve?",
    placeholder: "The step that takes longest, or the screen you avoid.",
    rows: 2
  },
  {
    key: "bugs",
    label: "Any bugs or issues you hit?",
    placeholder: "What you were doing, what you expected, and what happened instead.",
    rows: 2
  },
  {
    key: "featureRequests",
    label: "Features you'd like to see",
    placeholder: "Something you currently do on paper, or in another app.",
    rows: 2
  },
  {
    key: "comment",
    label: "Anything else (general comments)",
    placeholder: "Anything the questions above did not cover.",
    rows: 3
  }
] as const;

/** Android parity (FeedbackScreen): the word under the overall stars. */
const OVERALL_WORDS = ["Tap a star to rate", "Poor", "Fair", "Good", "Very good", "Excellent"];

type RatingKey = (typeof RATING_FIELDS)[number]["key"] | "rating";
type TextKey = (typeof TEXT_FIELDS)[number]["key"] | "role";

/**
 * A 1-5 star row.
 *
 * Stars rather than a number box for the reason Android uses them: a rating is a judgement, not a
 * measurement, and a five-target row is one tap where a number field is a tap, a keyboard and a
 * decision about whether 4.5 is allowed. Clicking the current value clears it, because "I would
 * rather not answer this one" has to stay reachable once a star has been pressed by accident.
 */
function StarRating({
  value,
  onChange,
  label,
  size = "md"
}: {
  value: number | null;
  onChange: (value: number | null) => void;
  /** Used for the per-star accessible name, so a screen reader hears which aspect is being rated. */
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
          className="rounded-md p-1 transition hover:bg-purple-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-600"
          key={n}
          onClick={() => onChange(value === n ? null : n)}
          type="button"
        >
          <Star className={`${star} ${value && n <= value ? "fill-purple-700 text-purple-700" : "text-line-200"}`} aria-hidden />
        </button>
      ))}
      <span className="ml-2 text-xs text-ink-500">{value ? `${value}/5` : "Not rated"}</span>
    </div>
  );
}

/** One labelled aspect row: label on the left, stars on the right on anything wider than a phone. */
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
  // Every editable answer in one object, so the upsert (which overwrites the whole row) can send the
  // complete picture without the old "carry the fields this form doesn't show" dance.
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

  // Android parity: the save is refused only when the whole form is empty — every single answer is
  // optional, and nobody should be made to invent a rating to send a bug report.
  const anyProvided =
    Object.values(ratings).some((value) => Boolean(value)) || Object.values(texts).some((value) => value.trim().length > 0);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!anyProvided) {
      setSuccess(null);
      setError("Add at least one rating or a written answer first.");
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
          // A cleared box means "no answer", which the column stores as NULL, not as "".
          ...Object.fromEntries(Object.entries(texts).map(([key, value]) => [key, value.trim() || null]))
        })
      });
      setSaved(result);
      setSuccess("Thank you — your feedback has been saved. You can revisit and change it any time.");
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
        description="Tell us how the app is working for you — rate it on a few aspects and add anything you'd like in your own words. Everything is optional; fill in what's relevant. You can come back and update this at any time."
        icon={<MessageSquare className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
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

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <button className="field-button" disabled={busy || !anyProvided} type="submit">
              {busy ? "Saving..." : saved ? "Update feedback" : "Submit feedback"}
            </button>
            {saved?.updatedAt ? <span className="text-xs text-ink-500">Last updated {formatDateTime(saved.updatedAt)}</span> : null}
          </div>
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
            <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{allError}</div>
          ) : null}
          {!allFeedback ? (
            <div className="panel p-4 text-sm text-ink-500">Loading...</div>
          ) : allFeedback.length === 0 ? (
            <div className="panel p-4">
              <EmptyState title="No feedback yet" body="Feedback users send from the web or Android apps will appear here." />
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

/**
 * Read-only rendering of one feedback row, used both for "your saved feedback" and for the master
 * admin's list. Labels match the Android read-only view (shorter than the question wording — a
 * summary reads as a table of aspects, not as a re-run of the interview).
 */
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
