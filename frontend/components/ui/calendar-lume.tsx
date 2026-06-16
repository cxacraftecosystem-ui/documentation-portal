"use client";

import { AnimatePresence, motion } from "framer-motion";
import {
  eachMonthOfInterval,
  eachYearOfInterval,
  endOfYear,
  format,
  startOfYear
} from "date-fns";
import { Clock } from "lucide-react";
import { useMemo, useState } from "react";

import { Calendar as BaseCalendar } from "@/components/ui/calendar";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";

type Step = "year" | "month" | "day" | "time";

function fromUtcParts(date: Date) {
  return {
    year: date.getUTCFullYear(),
    month: date.getUTCMonth(),
    day: date.getUTCDate(),
    hour: date.getUTCHours(),
    minute: date.getUTCMinutes()
  };
}

function utcDate(year: number, month: number, day: number, hour: number, minute: number) {
  return new Date(Date.UTC(year, month, day, hour, minute, 0, 0));
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function CalendarLume({
  value,
  name,
  onChange
}: {
  value?: Date | string | null;
  name?: string;
  onChange?: (date: Date) => void;
}) {
  const initial = useMemo(() => (value ? new Date(value) : new Date()), [value]);
  const [step, setStep] = useState<Step>("year");
  const [selectedDate, setSelectedDate] = useState<Date>(Number.isNaN(initial.getTime()) ? new Date() : initial);
  const parts = fromUtcParts(selectedDate);

  const yearRange = eachYearOfInterval({
    start: startOfYear(new Date(1900, 0, 1)),
    end: endOfYear(new Date(2100, 11, 31))
  });

  function commit(next: Date) {
    setSelectedDate(next);
    onChange?.(next);
  }

  return (
    <div className="w-full rounded-lg border border-border bg-background/90 p-3">
      {name ? <input type="hidden" name={name} value={selectedDate.toISOString()} /> : null}
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-serif text-lg text-ink">
          {step === "year" && "Select a Year"}
          {step === "month" && `Year ${parts.year}`}
          {step === "day" && format(new Date(parts.year, parts.month, 1), "MMMM yyyy")}
          {step === "time" && "Set UTC Time"}
        </h2>
        <div className="flex gap-2">
          {(["year", "month", "day", "time"] as Step[]).map((item) => (
            <Button key={item} type="button" variant={step === item ? "default" : "outline"} size="sm" onClick={() => setStep(item)}>
              {item === "time" ? <Clock className="h-3.5 w-3.5" aria-hidden /> : null}
              {item[0].toUpperCase() + item.slice(1)}
            </Button>
          ))}
        </div>
      </div>

      <AnimatePresence mode="wait">
        {step === "year" && (
          <motion.div key="year" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -12 }} className="h-64">
            <ScrollArea className="h-full pr-3">
              <div className="grid grid-cols-3 gap-2">
                {yearRange.map((year) => (
                  <Button
                    key={year.getFullYear()}
                    type="button"
                    variant={year.getFullYear() === parts.year ? "default" : "outline"}
                    size="sm"
                    className="h-10"
                    onClick={() => {
                      commit(utcDate(year.getFullYear(), parts.month, parts.day, parts.hour, parts.minute));
                      setStep("month");
                    }}
                  >
                    {year.getFullYear()}
                  </Button>
                ))}
              </div>
            </ScrollArea>
          </motion.div>
        )}

        {step === "month" && (
          <motion.div key="month" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -12 }} className="grid grid-cols-3 gap-2">
            {eachMonthOfInterval({
              start: startOfYear(new Date(parts.year, 0, 1)),
              end: endOfYear(new Date(parts.year, 11, 31))
            }).map((month) => (
              <Button
                key={month.toISOString()}
                type="button"
                variant={month.getMonth() === parts.month ? "default" : "outline"}
                size="sm"
                className="h-12 flex-col"
                onClick={() => {
                  commit(utcDate(parts.year, month.getMonth(), 1, parts.hour, parts.minute));
                  setStep("day");
                }}
              >
                <span className="text-sm font-medium">{format(month, "MMM")}</span>
                <span className="text-xs opacity-70">{parts.year}</span>
              </Button>
            ))}
          </motion.div>
        )}

        {step === "day" && (
          <motion.div key="day" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -12 }}>
            <BaseCalendar
              mode="single"
              month={new Date(parts.year, parts.month, 1)}
              selected={new Date(parts.year, parts.month, parts.day)}
              onSelect={(date) => {
                if (!date) return;
                commit(utcDate(parts.year, parts.month, date.getDate(), parts.hour, parts.minute));
                setStep("time");
              }}
              onMonthChange={(date) => commit(utcDate(date.getFullYear(), date.getMonth(), parts.day, parts.hour, parts.minute))}
              className="mx-auto rounded-lg border border-border bg-card p-2"
            />
          </motion.div>
        )}

        {step === "time" && (
          <motion.div key="time" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -12 }} className="grid gap-3 sm:grid-cols-2">
            <label className="grid gap-1 text-sm">
              <span className="field-label">Hour UTC</span>
              <input
                className="field-input"
                type="number"
                min={0}
                max={23}
                value={parts.hour}
                onChange={(event) => commit(utcDate(parts.year, parts.month, parts.day, Number(event.target.value), parts.minute))}
              />
            </label>
            <label className="grid gap-1 text-sm">
              <span className="field-label">Minute UTC</span>
              <input
                className="field-input"
                type="number"
                min={0}
                max={59}
                value={parts.minute}
                onChange={(event) => commit(utcDate(parts.year, parts.month, parts.day, parts.hour, Number(event.target.value)))}
              />
            </label>
            <div className="rounded-md bg-field-100 px-3 py-2 text-sm text-ink-muted sm:col-span-2">
              Saved as UTC: {parts.year}-{pad(parts.month + 1)}-{pad(parts.day)} {pad(parts.hour)}:{pad(parts.minute)}. Displayed dates use IST by default.
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

export { CalendarLume };
