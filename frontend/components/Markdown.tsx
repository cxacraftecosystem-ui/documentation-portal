"use client";

import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * Markdown renderer for transcripts and AI-generated text (design-system rule: these are
 * Markdown — bold speaker labels, `---` rules — never raw text in a <pre>). GitHub-flavoured
 * markdown via remark-gfm; raw HTML stays escaped (no rehype-raw on purpose).
 */
const components: Components = {
  p: ({ children }) => <p className="mb-2 leading-6 last:mb-0">{children}</p>,
  strong: ({ children }) => <strong className="font-semibold text-ink-900">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  hr: () => <hr className="my-3 border-line-200" />,
  ul: ({ children }) => <ul className="mb-2 list-disc space-y-1 pl-5 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="mb-2 list-decimal space-y-1 pl-5 last:mb-0">{children}</ol>,
  li: ({ children }) => <li className="leading-6">{children}</li>,
  h1: ({ children }) => <h1 className="mb-2 mt-4 font-display text-lg font-bold text-ink-900 first:mt-0">{children}</h1>,
  h2: ({ children }) => <h2 className="mb-2 mt-4 font-display text-base font-bold text-ink-900 first:mt-0">{children}</h2>,
  h3: ({ children }) => <h3 className="mb-1.5 mt-3 font-display text-sm font-bold text-ink-900 first:mt-0">{children}</h3>,
  h4: ({ children }) => <h4 className="mb-1.5 mt-3 font-display text-sm font-semibold text-ink-700 first:mt-0">{children}</h4>,
  a: ({ href, children }) => (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="font-medium text-purple-700 underline decoration-purple-300 underline-offset-2 hover:text-purple-800"
    >
      {children}
    </a>
  ),
  code: ({ children }) => (
    <code className="rounded border border-line-200 bg-surface-50 px-1 py-0.5 font-mono text-[0.85em] text-ink-700">{children}</code>
  ),
  pre: ({ children }) => (
    <pre className="mb-2 overflow-x-auto rounded-md border border-line-200 bg-surface-50 p-3 text-xs leading-5 last:mb-0">{children}</pre>
  ),
  blockquote: ({ children }) => (
    <blockquote className="mb-2 border-l-2 border-line-200 pl-3 italic text-ink-500 last:mb-0">{children}</blockquote>
  ),
  table: ({ children }) => (
    <div className="mb-2 overflow-x-auto last:mb-0">
      <table className="w-full border-collapse text-left">{children}</table>
    </div>
  ),
  th: ({ children }) => (
    <th className="border-b border-line-200 py-1.5 pr-4 text-xs font-semibold uppercase tracking-wide text-ink-500">{children}</th>
  ),
  td: ({ children }) => <td className="border-b border-line-200 py-1.5 pr-4 align-top">{children}</td>
};

/** Render markdown `text` with the app's tokens: ink-900 body, purple-700 links, line-200 rules, 14px base. */
export function Markdown({ text, className }: { text: string; className?: string }) {
  return (
    <div className={`text-sm leading-6 text-ink-900 ${className ?? ""}`.trim()}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {text}
      </ReactMarkdown>
    </div>
  );
}
