"use client";

import { ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, ChevronUpIcon } from "lucide-react";
import type * as React from "react";
import { DayPicker } from "react-day-picker";
import "react-day-picker/style.css";

import { cn } from "@/lib/utils";

const ROOT_VARS = [
  "[--rdp-accent-color:oklch(0.47_0.198_305)]",
  "[--rdp-selected-border:0]",
  "[--rdp-accent-background-color:oklch(0.977_0.013_305)]",
  "dark:[--rdp-accent-background-color:oklch(0.255_0.108_305)]",
  "[--rdp-range_middle-color:rgb(var(--ink-900))]",
  "[--rdp-today-color:oklch(0.47_0.198_305)]",
  "dark:[--rdp-today-color:oklch(0.828_0.1_305)]",
  "[--rdp-disabled-opacity:1]",
  "[--rdp-outside-opacity:1]",
  "[--rdp-weekday-opacity:1]",
  "[--rdp-week_number-opacity:1]",
  "[--rdp-day-height:2.25rem]",
  "[--rdp-day-width:2.25rem]",
  "[--rdp-day_button-height:2.25rem]",
  "[--rdp-day_button-width:2.25rem]",
  "[--rdp-day_button-border:0]",
  "[--rdp-day_button-border-radius:12px]",
  "[--rdp-nav_button-height:2rem]",
  "[--rdp-nav_button-width:2rem]",
  "[--rdp-nav-height:2.25rem]",
  "[--rdp-months-gap:1.5rem]"
].join(" ");

const NAV_BUTTON =
  "relative inline-flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-ink-700 transition " +
  "hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200 " +
  "focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-2 focus-visible:ring-offset-card " +
  "disabled:pointer-events-none disabled:text-ink-300 " +
  "[&_svg]:pointer-events-none [&_svg]:h-4 [&_svg]:w-4 [&_svg]:shrink-0";

const DAY_BUTTON =
  "relative mx-auto flex h-9 w-9 cursor-pointer items-center justify-center rounded-md text-sm tabular-nums transition " +
  "hover:bg-purple-50 dark:hover:bg-purple-950 " +
  "focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card";

export function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  components: userComponents,
  mode = "single",
  ...props
}: React.ComponentProps<typeof DayPicker>): React.ReactElement {
  const defaultClassNames = {
    months: "relative flex flex-col gap-4 sm:flex-row",
    month: "space-y-2",
    month_caption: "flex h-9 items-center justify-center",
    caption_label: "flex h-full items-center gap-2 font-display text-sm font-semibold text-ink-900",
    nav: "absolute inset-x-0 top-0 flex h-9 items-center justify-between",
    button_previous: NAV_BUTTON,
    button_next: NAV_BUTTON,
    dropdowns: "flex items-center gap-2",
    dropdown_root:
      "relative flex h-8 items-center rounded-md border border-line-200 bg-card px-2 text-sm text-ink-900 shadow-sm transition " +
      "focus-within:border-purple-700 focus-within:ring-2 focus-within:ring-purple-700/25",
    dropdown: "absolute inset-0 cursor-pointer opacity-0",
    month_grid: "w-full border-collapse",
    weekday: "h-8 w-9 p-0 text-[0.7rem] font-semibold uppercase tracking-wide text-ink-500",
    week_number_header: "h-8 w-9 p-0 text-[0.7rem] font-semibold uppercase tracking-wide text-ink-500",
    week_number: "h-9 w-9 p-0 text-center text-xs font-medium text-ink-500",
    day: "h-9 w-9 p-0 text-center align-middle text-sm text-ink-900",
    day_button: DAY_BUTTON,
    selected:
      "[&>button]:bg-purple-700 [&>button]:font-semibold [&>button]:text-white " +
      "hover:[&>button]:bg-purple-700 dark:hover:[&>button]:bg-purple-700",
    range_start: "rounded-l-md bg-purple-50 dark:bg-purple-950",
    range_end: "rounded-r-md bg-purple-50 dark:bg-purple-950",
    range_middle:
      "bg-purple-50 dark:bg-purple-950 " +
      "[&>button]:rounded-none! [&>button]:bg-transparent! [&>button]:font-normal! [&>button]:text-ink-900!",
    today:
      "[&:not([data-selected])>button]:font-semibold " +
      "[&:not([data-selected])>button]:text-purple-700 dark:[&:not([data-selected])>button]:text-purple-300 " +
      "[&:not([data-selected])>button]:ring-1 [&:not([data-selected])>button]:ring-inset " +
      "[&:not([data-selected])>button]:ring-purple-200 dark:[&:not([data-selected])>button]:ring-purple-800",
    outside: "[&:not([data-selected])>button]:font-normal [&:not([data-selected])>button]:text-ink-300",
    disabled: "[&>button]:cursor-not-allowed [&>button]:text-ink-300 hover:[&>button]:bg-transparent",
    hidden: "invisible",
    footer: "pt-2 text-xs text-ink-500"
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
    Chevron: ({
      className: iconClassName,
      orientation,
      ...iconProps
    }: {
      className?: string;
      orientation?: "left" | "right" | "up" | "down";
    }) => {
      if (orientation === "left") return <ChevronLeftIcon className={cn(iconClassName, "rtl:rotate-180")} {...iconProps} aria-hidden="true" />;
      if (orientation === "right") return <ChevronRightIcon className={cn(iconClassName, "rtl:rotate-180")} {...iconProps} aria-hidden="true" />;
      if (orientation === "up") return <ChevronUpIcon className={iconClassName} {...iconProps} aria-hidden="true" />;
      return <ChevronDownIcon className={iconClassName} {...iconProps} aria-hidden="true" />;
    }
  };

  return (
    <DayPicker
      className={cn("w-fit font-sans text-ink-900", ROOT_VARS, className)}
      classNames={mergedClassNames}
      components={{ ...defaultComponents, ...userComponents }}
      formatters={{ formatMonthDropdown: (date: Date) => date.toLocaleString("default", { month: "short" }) }}
      mode={mode}
      showOutsideDays={showOutsideDays}
      {...(props as React.ComponentProps<typeof DayPicker>)}
    />
  );
}
