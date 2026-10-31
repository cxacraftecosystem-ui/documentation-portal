"use client";

import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

import { DISALLOWED_ELEMENTS, safeUrl } from "@/lib/transcript/sanitize";

/**
 * THE SANITISED RENDERER every transcript, translation and answer is drawn through (design §2.4).
 *
 * - Raw HTML is DROPPED (`skipHtml`), not escaped into view and never parsed: a `<script>` or an
 *   `<img onerror>` in a turn renders as nothing.
 * - Images are not drawn at all (`disallowedElements`): a markdown image would fetch a URL of the
 *   writer's choosing the moment the transcript opens.
 * - A link keeps only http(s) and mailto targets; `javascript:`, `data:` and the rest lose the href
 *   and stay as text. Links open in a new tab with no opener and no referrer.
 *
 * `e2e/transcript-web.spec.ts` feeds it hostile markdown and checks the DOM it produced.
 */
const components: Components = {
  p: ({ children }) => <p className="mb-2 leading-6 last:mb-0">{children}</p>,
  strong: ({ children }) => <strong className="font-semibold text-ink-900">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  hr: () => <hr className="my-3 border-line-200" />,
  ul: ({ children }) => <ul className="mb-2 list-disc space-y-1 pl-5 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="mb-2 list-decimal space-y-1 pl-5 last:mb-0">{children}</ol>,
  li: ({ children }) => <li className="leading-6">{children}</li>,
  h1: ({ children }) => <p className="mb-2 font-display text-base font-bold text-ink-900">{children}</p>,
  h2: ({ children }) => <p className="mb-2 font-display text-sm font-bold text-ink-900">{children}</p>,
  h3: ({ children }) => <p className="mb-1.5 text-sm font-semibold text-ink-900">{children}</p>,
  h4: ({ children }) => <p className="mb-1.5 text-sm font-semibold text-ink-700">{children}</p>,
  h5: ({ children }) => <p className="mb-1.5 text-sm font-semibold text-ink-700">{children}</p>,
  h6: ({ children }) => <p className="mb-1.5 text-sm font-semibold text-ink-700">{children}</p>,
  a: ({ href, children }) =>
    href ? (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer nofollow"
        className="font-medium text-purple-700 underline decoration-purple-300 underline-offset-2 hover:text-purple-800 dark:text-purple-300"
      >
        {children}
      </a>
    ) : (
      <span>{children}</span>
    ),
  code: ({ children }) => (
    <code className="rounded border border-line-200 bg-surface-50 px-1 py-0.5 font-mono text-[0.85em] text-ink-700">{children}</code>
  ),
  pre: ({ children }) => (
    <pre className="mb-2 overflow-x-auto rounded-md border border-line-200 bg-surface-50 p-3 text-xs leading-5 last:mb-0">{children}</pre>
  ),
  blockquote: ({ children }) => (
    <blockquote className="mb-2 border-l-2 border-purple-200 pl-3 text-ink-700 last:mb-0">{children}</blockquote>
  ),
  table: ({ children }) => (
    <div className="mb-2 overflow-x-auto last:mb-0">
      <table className="w-full border-collapse text-left">{children}</table>
    </div>
  ),
  th: ({ children }) => <th className="border-b border-line-200 py-1.5 pr-4 text-xs font-semibold text-ink-500">{children}</th>,
  td: ({ children }) => <td className="border-b border-line-200 py-1.5 pr-4 align-top">{children}</td>
};

export function TranscriptMarkdown({ text, className }: { text: string; className?: string }) {
  return (
    <div className={`min-w-0 break-words text-sm leading-6 text-ink-900 ${className ?? ""}`.trim()} data-transcript-markdown>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={components}
        skipHtml
        disallowedElements={DISALLOWED_ELEMENTS}
        unwrapDisallowed={false}
        urlTransform={safeUrl}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
