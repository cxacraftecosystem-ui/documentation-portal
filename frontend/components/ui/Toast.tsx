"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { CheckCircle2, Info, TriangleAlert, X, type LucideIcon } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { cn } from "@/lib/utils";

export type ToastTone = "info" | "success" | "error";

export type ToastInput = {
  title: string;
  description?: string;
  tone?: ToastTone;
  duration?: number;
  id?: string;
};

type ToastRecord = {
  id: string;
  title: string;
  description?: string;
  tone: ToastTone;
  duration: number;
};

type ToastContextValue = {
  toast: (input: ToastInput) => string;
  dismiss: (id: string) => void;
};

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

const DEFAULT_DURATION = 5000;
const MAX_VISIBLE = 3;

const TONES: Record<ToastTone, { icon: LucideIcon; chip: string }> = {
  info: { icon: Info, chip: "bg-purple-50 text-purple-700" },
  success: { icon: CheckCircle2, chip: "bg-success-100 text-success-600" },
  error: { icon: TriangleAlert, chip: "bg-error-100 text-red-800" }
};

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastRecord[]>([]);
  const counter = useRef(0);

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((item) => item.id !== id));
  }, []);

  const toast = useCallback((input: ToastInput) => {
    counter.current += 1;
    const record: ToastRecord = {
      id: input.id ?? `toast-${counter.current}`,
      title: input.title,
      description: input.description,
      tone: input.tone ?? "info",
      duration: input.duration ?? DEFAULT_DURATION
    };
    setToasts((current) => [...current.filter((item) => item.id !== record.id), record].slice(-MAX_VISIBLE));
    return record.id;
  }, []);

  const value = useMemo(() => ({ toast, dismiss }), [toast, dismiss]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error("useToast must be used inside ToastProvider");
  return context;
}

function ToastViewport({ toasts, onDismiss }: { toasts: ToastRecord[]; onDismiss: (id: string) => void }) {
  const reduce = useReducedMotion() ?? false;

  return (
    <div
      role="status"
      aria-live="polite"
      aria-atomic="false"
      className="pointer-events-none fixed bottom-4 right-4 z-110 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2"
      style={{ bottom: "calc(var(--bottom-nav-h, 0px) + 0.75rem)" }}
    >
      <AnimatePresence initial={false}>
        {toasts.map((item) => (
          <ToastCard key={item.id} toast={item} reduce={reduce} onDismiss={onDismiss} />
        ))}
      </AnimatePresence>
    </div>
  );
}

function ToastCard({
  toast,
  reduce,
  onDismiss
}: {
  toast: ToastRecord;
  reduce: boolean;
  onDismiss: (id: string) => void;
}) {
  const [paused, setPaused] = useState(false);
  const remaining = useRef(toast.duration);
  const startedAt = useRef(0);
  const dismiss = useCallback(() => onDismiss(toast.id), [onDismiss, toast.id]);

  useEffect(() => {
    if (paused || remaining.current <= 0) return;
    startedAt.current = Date.now();
    const timer = setTimeout(dismiss, remaining.current);
    return () => {
      clearTimeout(timer);
      remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt.current));
    };
  }, [dismiss, paused]);

  const { icon: Icon, chip } = TONES[toast.tone];

  return (
    <motion.div
      layout={reduce ? false : "position"}
      initial={reduce ? { opacity: 0 } : { opacity: 0, y: 12, scale: 0.97 }}
      animate={reduce ? { opacity: 1 } : { opacity: 1, y: 0, scale: 1 }}
      exit={reduce ? { opacity: 0 } : { opacity: 0, y: 6, scale: 0.97 }}
      transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 420, damping: 34, mass: 0.7 }}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      className="pointer-events-auto flex items-start gap-3 rounded-md border border-line-200 bg-card p-3 shadow-lg"
    >
      <span aria-hidden className={cn("mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md", chip)}>
        <Icon className="h-4 w-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-display text-sm font-bold leading-snug text-ink-900">{toast.title}</p>
        {toast.description ? <p className="mt-1 text-sm leading-5 text-ink-700">{toast.description}</p> : null}
      </div>
      <button
        type="button"
        onClick={dismiss}
        aria-label={`Dismiss: ${toast.title}`}
        className="-m-1 shrink-0 rounded-md p-1 text-ink-500 transition hover:text-ink-700"
      >
        <X className="h-4 w-4" aria-hidden />
      </button>
    </motion.div>
  );
}
