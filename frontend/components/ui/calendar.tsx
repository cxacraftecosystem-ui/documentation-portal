"use client";

import { ChevronLeftIcon, ChevronRightIcon, ChevronsUpDownIcon } from "lucide-react";
import type * as React from "react";
import { DayPicker } from "react-day-picker";
import "react-day-picker/style.css";

import { cn } from "@/lib/utils";

// Buttons inherit color (Tailwind preflight) so the selected day's white text on the purple
// cell flows into the day button; hover wash on selected cells is neutralized in globals.css.
const buttonClassNames =
  "relative flex h-9 w-9 items-center justify-center rounded-lg text-sm transition hover:bg-purple-50 disabled:pointer-events-none disabled:opacity-60 [&_svg]:pointer-events-none [&_svg]:shrink-0";

export function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  components: userComponents,
  mode = "single",
  ...props
}: React.ComponentProps<typeof DayPicker>): React.ReactElement {
  const defaultClassNames = {
    button_next: buttonClassNames,
    button_previous: buttonClassNames,
    caption_label: "flex h-full items-center gap-2 text-sm font-medium text-ink-900",
    day: "h-9 w-9 p-0 text-center text-sm text-ink-900",
    day_button: cn(buttonClassNames, "mx-auto"),
    dropdown: "absolute inset-0 bg-card opacity-0",
    dropdown_root:
      "relative h-8 rounded-lg border border-line-200 px-2 text-sm shadow-sm focus-within:border-purple-600 focus-within:ring-2 focus-within:ring-purple-600/20",
    month: "space-y-3",
    month_caption: "flex h-9 items-center justify-center",
    months: "relative flex flex-col gap-4 sm:flex-row",
    nav: "absolute inset-x-0 top-0 flex items-center justify-between text-ink-700",
    outside: "text-ink-300",
    range_end: "cal-edge rounded-l-none rounded-r-lg",
    range_middle: "cal-middle !rounded-none !bg-purple-50 !text-ink-900",
    range_start: "cal-edge rounded-l-lg rounded-r-none",
    selected: "rounded-lg bg-purple-700 font-medium text-white",
    today: "font-semibold [&:not([data-selected])]:text-purple-700",
    week_number: "h-9 w-9 p-0 text-xs font-medium text-ink-500",
    weekday: "h-9 w-9 p-0 text-xs font-medium text-ink-500",
    weeks: "w-full border-collapse",
    hidden: "invisible"
  };

  const mergedClassNames = Object.keys(defaultClassNames).reduce(
    (acc, key) => {
      const userClass = classNames?.[key as keyof typeof classNames];
      const baseClass = defaultClassNames[key as keyof typeof defaultClassNames];
      acc[key as keyof typeof defaultClassNames] = userClass ? cn(baseClass, userClass) : baseClass;
      return acc;
    },
    { ...defaultClassNames }
  );

  const defaultComponents = {
    Chevron: ({ className: iconClassName, orientation, ...iconProps }: { className?: string; orientation?: "left" | "right" | "up" | "down" }) => {
      if (orientation === "left") return <ChevronLeftIcon className={cn(iconClassName, "rtl:rotate-180")} {...iconProps} aria-hidden="true" />;
      if (orientation === "right") return <ChevronRightIcon className={cn(iconClassName, "rtl:rotate-180")} {...iconProps} aria-hidden="true" />;
      return <ChevronsUpDownIcon className={iconClassName} {...iconProps} aria-hidden="true" />;
    }
  };

  return (
    <DayPicker
      className={cn("w-fit", className)}
      classNames={mergedClassNames}
      components={{ ...defaultComponents, ...userComponents }}
      formatters={{ formatMonthDropdown: (date: Date) => date.toLocaleString("default", { month: "short" }) }}
      mode={mode}
      showOutsideDays={showOutsideDays}
      {...(props as React.ComponentProps<typeof DayPicker>)}
    />
  );
}
