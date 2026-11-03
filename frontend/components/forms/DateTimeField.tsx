"use client";

import { CalendarDays, Clock } from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject
} from "react";
import type { DateRange } from "react-day-picker";

import { Calendar } from "@/components/ui/calendar";
import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { cn } from "@/lib/utils";

export function formatDisplayDate(date: Date) {
  const day = String(date.getDate()).padStart(2, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${day}/${month}/${date.getFullYear()}`;
}

export function parseTypedDate(text: string): Date | undefined {
  const trimmed = text.trim();
  let year: number;
  let month: number;
  let day: number;
  let match = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$/.exec(trimmed);
  if (match) {
    day = Number(match[1]);
    month = Number(match[2]);
    year = Number(match[3]);
  } else {
    match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(trimmed);
    if (!match) return undefined;
    year = Number(match[1]);
    month = Number(match[2]);
    day = Number(match[3]);
  }
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : undefined;
}

export function toIsoDate(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

export function fromIsoDate(iso: string | null | undefined): Date | undefined {
  if (!iso) return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return undefined;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? undefined : date;
}

export function parseTypedTime(text: string): string | undefined {
  const trimmed = text.trim().toLowerCase();
  const match = /^(\d{1,2})(?:[:. ]?(\d{2}))?\s*(am|pm)?$/.exec(trimmed);
  if (!match) return undefined;
  let hours = Number(match[1]);
  const minutes = match[2] ? Number(match[2]) : 0;
  const meridiem = match[3];
  if (minutes > 59) return undefined;
  if (meridiem) {
    if (hours < 1 || hours > 12) return undefined;
    if (meridiem === "pm" && hours !== 12) hours += 12;
    if (meridiem === "am" && hours === 12) hours = 0;
  } else if (hours > 23) {
    if (trimmed.length === 4 && !match[2]) return undefined;
    return undefined;
  }
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

function meridiemLabel(hhmm: string) {
  const [hourText, minuteText] = hhmm.split(":");
  const hours = Number(hourText);
  const suffix = hours < 12 ? "am" : "pm";
  const twelve = hours % 12 === 0 ? 12 : hours % 12;
  return `${twelve}:${minuteText} ${suffix}`;
}

const INPUT_WITH_ICON = "field-input pr-10";

const ICON_BUTTON =
  "absolute right-1 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-md text-ink-500 transition " +
  "hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200 " +
  "focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card " +
  "disabled:pointer-events-none disabled:text-ink-300";

type TriggerInputProps = {
  inputRef: RefObject<HTMLInputElement | null>;
  panelId: string;
  open: boolean;
  value: string;
  placeholder: string;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  ariaLabel?: string;
  icon: ReactNode;
  iconLabel: string;
  inputMode?: "numeric" | "text";
  onText: (text: string) => void;
  onRevert: () => void;
  onOpen: (fromKeyboard: boolean) => void;
  onToggle: () => void;
  className?: string;
};

function TriggerInput({
  inputRef,
  panelId,
  open,
  value,
  placeholder,
  disabled,
  required,
  id,
  ariaLabel,
  icon,
  iconLabel,
  inputMode = "numeric",
  onText,
  onRevert,
  onOpen,
  onToggle,
  className
}: TriggerInputProps) {
  return (
    <div className="relative">
      <input
        ref={inputRef}
        id={id}
        type="text"
        inputMode={inputMode}
        autoComplete="off"
        spellCheck={false}
        aria-label={ariaLabel}
        disabled={disabled}
        required={required}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onText(event.target.value)}
        onPointerDown={() => {
          if (!disabled && !open) onOpen(false);
        }}
        onBlur={onRevert}
        onKeyDown={(event) => {
          if (event.key !== "ArrowDown") return;
          event.preventDefault();
          onOpen(true);
        }}
        className={cn(INPUT_WITH_ICON, className)}
      />
      <button
        type="button"
        disabled={disabled}
        aria-label={iconLabel}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={onToggle}
        title={iconLabel}
        className={ICON_BUTTON}
      >
        {icon}
      </button>
    </div>
  );
}

export type DateFieldProps = {
  name?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (iso: string) => void;
  min?: string;
  max?: string;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  ariaLabel?: string;
  className?: string;
};

export function DateField({
  name,
  value,
  defaultValue,
  onChange,
  min,
  max,
  disabled,
  required,
  id,
  ariaLabel,
  className
}: DateFieldProps) {
  const panelId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const controlled = value !== undefined;
  const [innerIso, setInnerIso] = useState(defaultValue ?? "");
  const iso = controlled ? value : innerIso;
  const selected = fromIsoDate(iso);

  const [text, setText] = useState(() => (selected ? formatDisplayDate(selected) : ""));
  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);
  const [month, setMonth] = useState<Date>(() => selected ?? new Date());

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === inputRef.current) return;
    const next = fromIsoDate(iso);
    setText(next ? formatDisplayDate(next) : "");
    if (next) setMonth(next);
  }, [iso]);

  const lowerBound = fromIsoDate(min);
  const upperBound = fromIsoDate(max);

  const commit = useCallback(
    (date: Date | undefined) => {
      const nextIso = date ? toIsoDate(date) : "";
      if (!controlled) setInnerIso(nextIso);
      onChange?.(nextIso);
    },
    [controlled, onChange]
  );

  function handleText(next: string) {
    setText(next);
    if (next.trim() === "") {
      commit(undefined);
      return;
    }
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    if (lowerBound && parsed < lowerBound) return;
    if (upperBound && parsed > upperBound) return;
    setMonth(parsed);
    commit(parsed);
  }

  function revert() {
    const current = fromIsoDate(iso);
    setText(current ? formatDisplayDate(current) : "");
  }

  function openPanel(fromKeyboard: boolean) {
    setFocusPanel(fromKeyboard);
    setMonth(fromIsoDate(iso) ?? new Date());
    setOpen(true);
  }

  const close = useCallback(() => setOpen(false), []);

  return (
    <div ref={anchorRef} className="relative">
      {name ? <input type="hidden" name={name} value={iso} /> : null}
      <TriggerInput
        inputRef={inputRef}
        panelId={panelId}
        open={open}
        value={text}
        placeholder="dd/mm/yyyy"
        disabled={disabled}
        required={required}
        id={id}
        ariaLabel={ariaLabel}
        icon={<CalendarDays className="h-4 w-4" aria-hidden />}
        iconLabel="Open calendar"
        onText={handleText}
        onRevert={revert}
        onOpen={openPanel}
        onToggle={() => (open ? close() : openPanel(true))}
        className={className}
      />
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={inputRef}
        label="Choose a date"
        className="w-auto"
      >
        <div id={panelId}>
          <Calendar
            mode="single"
            autoFocus={focusPanel}
            month={month}
            onMonthChange={setMonth}
            selected={selected}
            disabled={[
              ...(lowerBound ? [{ before: lowerBound }] : []),
              ...(upperBound ? [{ after: upperBound }] : [])
            ]}
            onSelect={(date) => {
              if (!date) return;
              setText(formatDisplayDate(date));
              commit(date);
              setOpen(false);
              inputRef.current?.focus({ preventScroll: true });
            }}
          />
          <SelectionSummary text={selected ? formatDisplayDate(selected) : "No date chosen"} />
        </div>
      </AnchoredPopover>
    </div>
  );
}

export type DateRangePickerProps = {
  from?: Date;
  to?: Date;
  onChange: (range: { from?: Date; to?: Date }) => void;
  startLabel?: string;
  endLabel?: string;
  disabled?: boolean;
  startFieldKey?: string;
  endFieldKey?: string;
};

export function DateRangePicker({
  from,
  to,
  onChange,
  startLabel = "Start date",
  endLabel = "End date",
  disabled,
  startFieldKey,
  endFieldKey
}: DateRangePickerProps) {
  const panelId = useId();
  const startId = useId();
  const endId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const startRef = useRef<HTMLInputElement | null>(null);
  const endRef = useRef<HTMLInputElement | null>(null);
  const lastTriggerRef = useRef<HTMLInputElement | null>(null);

  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);
  const [month, setMonth] = useState<Date>(() => from ?? new Date());
  const [startText, setStartText] = useState(() => (from ? formatDisplayDate(from) : ""));
  const [endText, setEndText] = useState(() => (to ? formatDisplayDate(to) : ""));

  const fromTime = from?.getTime();
  const toTime = to?.getTime();

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === startRef.current) return;
    setStartText(fromTime === undefined ? "" : formatDisplayDate(new Date(fromTime)));
  }, [fromTime]);

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === endRef.current) return;
    setEndText(toTime === undefined ? "" : formatDisplayDate(new Date(toTime)));
  }, [toTime]);

  function handleStartText(next: string) {
    setStartText(next);
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    setMonth(parsed);
    onChange({ from: parsed, to: to && to.getTime() >= parsed.getTime() ? to : parsed });
  }

  function handleEndText(next: string) {
    setEndText(next);
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    setMonth(parsed);
    onChange({ from: from && from.getTime() <= parsed.getTime() ? from : parsed, to: parsed });
  }

  function openPanel(fromKeyboard: boolean, trigger: RefObject<HTMLInputElement | null>, anchorMonth?: Date) {
    lastTriggerRef.current = trigger.current;
    setFocusPanel(fromKeyboard);
    if (anchorMonth) setMonth(anchorMonth);
    setOpen(true);
  }

  const close = useCallback(() => setOpen(false), []);
  const summary =
    from && to ? `${formatDisplayDate(from)} to ${formatDisplayDate(to)}` : from ? `${formatDisplayDate(from)} — pick an end date` : "No dates chosen";

  return (
    <div ref={anchorRef} className="relative grid gap-3 sm:grid-cols-2">
      <div className="grid gap-1" data-field={startFieldKey}>
        <label className="field-label" htmlFor={startId}>
          {startLabel}
        </label>
        <TriggerInput
          inputRef={startRef}
          id={startId}
          panelId={panelId}
          open={open}
          value={startText}
          placeholder="dd/mm/yyyy"
          disabled={disabled}
          icon={<CalendarDays className="h-4 w-4" aria-hidden />}
          iconLabel={`Open calendar for ${startLabel.toLowerCase()}`}
          onText={handleStartText}
          onRevert={() => setStartText(from ? formatDisplayDate(from) : "")}
          onOpen={(fromKeyboard) => openPanel(fromKeyboard, startRef, from)}
          onToggle={() => (open ? close() : openPanel(true, startRef, from))}
        />
      </div>
      <div className="grid gap-1" data-field={endFieldKey}>
        <label className="field-label" htmlFor={endId}>
          {endLabel}
        </label>
        <TriggerInput
          inputRef={endRef}
          id={endId}
          panelId={panelId}
          open={open}
          value={endText}
          placeholder="dd/mm/yyyy"
          disabled={disabled}
          icon={<CalendarDays className="h-4 w-4" aria-hidden />}
          iconLabel={`Open calendar for ${endLabel.toLowerCase()}`}
          onText={handleEndText}
          onRevert={() => setEndText(to ? formatDisplayDate(to) : "")}
          onOpen={(fromKeyboard) => openPanel(fromKeyboard, endRef, to)}
          onToggle={() => (open ? close() : openPanel(true, endRef, to))}
        />
      </div>
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={lastTriggerRef}
        label="Choose a date range"
        className="w-auto"
      >
        <div id={panelId}>
          <Calendar
            mode="range"
            autoFocus={focusPanel}
            month={month}
            onMonthChange={setMonth}
            selected={from ? ({ from, to } as DateRange) : undefined}
            onSelect={(range: DateRange | undefined) => onChange({ from: range?.from, to: range?.to })}
          />
          <SelectionSummary
            text={summary}
            action={
              <button
                type="button"
                className="rounded-md px-2 py-1 font-medium text-purple-700 transition hover:bg-purple-50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 dark:text-purple-300 dark:hover:bg-purple-950"
                onClick={() => {
                  setOpen(false);
                  (lastTriggerRef.current ?? startRef.current)?.focus({ preventScroll: true });
                }}
              >
                Done
              </button>
            }
          />
        </div>
      </AnchoredPopover>
    </div>
  );
}

const TIME_SLOTS = Array.from({ length: 24 * 4 }, (_, index) => {
  const hours = Math.floor(index / 4);
  const minutes = (index % 4) * 15;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
});

export type TimeFieldProps = {
  name?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (hhmm: string) => void;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  ariaLabel?: string;
  className?: string;
};

export function TimeField({
  name,
  value,
  defaultValue,
  onChange,
  disabled,
  required,
  id,
  ariaLabel,
  className
}: TimeFieldProps) {
  const panelId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const controlled = value !== undefined;
  const [innerValue, setInnerValue] = useState(defaultValue ?? "");
  const hhmm = controlled ? value : innerValue;

  const [text, setText] = useState(hhmm);
  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === inputRef.current) return;
    setText(hhmm);
  }, [hhmm]);

  const commit = useCallback(
    (next: string) => {
      if (!controlled) setInnerValue(next);
      onChange?.(next);
    },
    [controlled, onChange]
  );

  function handleText(next: string) {
    setText(next);
    if (next.trim() === "") {
      commit("");
      return;
    }
    const parsed = parseTypedTime(next);
    if (parsed) commit(parsed);
  }

  const close = useCallback(() => setOpen(false), []);

  const activeSlot = useMemo(() => {
    if (!hhmm) return "09:00";
    const exact = TIME_SLOTS.indexOf(hhmm);
    if (exact >= 0) return hhmm;
    const [hours, minutes] = hhmm.split(":").map(Number);
    const index = Math.min(TIME_SLOTS.length - 1, Math.max(0, hours * 4 + Math.floor((minutes || 0) / 15)));
    return TIME_SLOTS[index];
  }, [hhmm]);

  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => {
      const option = listRef.current?.querySelector<HTMLElement>('[data-active="true"]');
      option?.scrollIntoView({ block: "center" });
      if (focusPanel) option?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [open, focusPanel, activeSlot]);

  function onListKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    const keys = ["ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) return;
    event.preventDefault();
    const options = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
    if (options.length === 0) return;
    const current = options.indexOf(document.activeElement as HTMLElement);
    const next =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? options.length - 1
          : Math.min(options.length - 1, Math.max(0, current + (event.key === "ArrowDown" ? 1 : -1)));
    options[next]?.focus({ preventScroll: true });
    options[next]?.scrollIntoView({ block: "nearest" });
  }

  return (
    <div ref={anchorRef} className="relative">
      {name ? <input type="hidden" name={name} value={hhmm} /> : null}
      <TriggerInput
        inputRef={inputRef}
        panelId={panelId}
        open={open}
        value={text}
        placeholder="hh:mm"
        disabled={disabled}
        required={required}
        id={id}
        ariaLabel={ariaLabel}
        icon={<Clock className="h-4 w-4" aria-hidden />}
        iconLabel="Open time list"
        inputMode="text"
        onText={handleText}
        onRevert={() => setText(hhmm)}
        onOpen={(fromKeyboard) => {
          setFocusPanel(fromKeyboard);
          setOpen(true);
        }}
        onToggle={() => {
          if (open) {
            close();
            return;
          }
          setFocusPanel(true);
          setOpen(true);
        }}
        className={className}
      />
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={inputRef}
        label="Choose a time"
        matchAnchorWidth
        maxWidth={288}
        className="p-1"
      >
        <div
          id={panelId}
          ref={listRef}
          role="listbox"
          aria-label="Times, in quarter-hour steps"
          onKeyDown={onListKeyDown}
          className="grid gap-0.5"
        >
          {TIME_SLOTS.map((slot) => {
            const active = slot === activeSlot;
            const chosen = slot === hhmm;
            return (
              <button
                key={slot}
                type="button"
                role="option"
                aria-selected={chosen}
                data-active={active || undefined}
                tabIndex={active ? 0 : -1}
                onClick={() => {
                  setText(slot);
                  commit(slot);
                  setOpen(false);
                  inputRef.current?.focus({ preventScroll: true });
                }}
                className={cn(
                  "flex items-center justify-between gap-3 rounded-md px-3 py-1.5 text-left text-sm tabular-nums transition",
                  "focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card",
                  chosen
                    ? "bg-purple-700 font-semibold text-white"
                    : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950"
                )}
              >
                <span>{slot}</span>
                <span className={cn("text-xs", chosen ? "text-white/75" : "text-ink-500")}>{meridiemLabel(slot)}</span>
              </button>
            );
          })}
        </div>
      </AnchoredPopover>
    </div>
  );
}

function SelectionSummary({ text, action }: { text: string; action?: ReactNode }) {
  return (
    <div className="mt-2 flex items-center justify-between gap-3 rounded-md bg-surface-50 px-3 py-2 text-sm text-ink-700">
      <span aria-live="polite">{text}</span>
      {action}
    </div>
  );
}
