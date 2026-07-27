"use client";

/**
 * The month grid — and the ONLY one in the app. It is react-day-picker with every visible part
 * re-skinned onto the design tokens. It owns no positioning, no popover and no text input, so it can
 * be dropped inline or inside the floating picker (`components/forms/DateTimeField`) without either
 * caring how the other is laid out.
 *
 * Theming happens in two layers, and both are needed:
 *
 * 1. `ROOT_VARS` — react-day-picker resolves its own internals (cell metrics, and any part we do not
 *    hand a class to) through CSS custom properties. This is the FALLBACK layer, not the mechanism.
 * 2. `classNames` — a Tailwind class per part. This is what actually paints the weekday header,
 *    navigation, today, selection, hover, disabled and out-of-month days, in both themes.
 *
 * Worth knowing before editing layer 1: `app/globals.css` also declares four of these variables on
 * `.rdp-root`, and it does so after `@tailwind utilities`, so at equal specificity ITS light values
 * win over the ones here (they agree, so nothing moves). The `dark:` variants below do win, because
 * `[data-theme="dark"] .cls` out-specifies a bare `.rdp-root`. Do not rely on that asymmetry: every
 * visible part is painted by its own class in layer 2 precisely so the variables never have to be
 * load-bearing.
 *
 * Both live here rather than in `app/globals.css`: the calendar is then self-sufficient, so deleting
 * this file takes its styling with it and no page inherits a rule for a component it does not render.
 *
 * One rule about the two escape hatches, because they are not interchangeable. The library composes
 * the root element's class as `[classNames.root, props.className]` but builds `classNames` as
 * `{...defaults, ...yours}` — so overriding `classNames.root` DROPS the library's own `rdp-root`
 * class, and with it `.rdp-root { position: relative }`, which is the containing block the absolutely
 * positioned month navigation is placed against. The variables therefore ride on `className`.
 */

import { ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, ChevronUpIcon } from "lucide-react";
import type * as React from "react";
import { DayPicker } from "react-day-picker";
import "react-day-picker/style.css";

import { cn } from "@/lib/utils";

const ROOT_VARS = [
  // Brand purple, identical in both themes — purple-700 is the one action colour.
  "[--rdp-accent-color:oklch(0.47_0.198_305)]",
  "[--rdp-selected-border:0]",
  // The in-range wash: purple-50 in light, purple-950 in dark.
  "[--rdp-accent-background-color:oklch(0.977_0.013_305)]",
  "dark:[--rdp-accent-background-color:oklch(0.255_0.108_305)]",
  "[--rdp-range_middle-color:rgb(var(--ink-900))]",
  // Today's tint has to lighten in dark mode: purple-700 text on a near-black card is unreadable.
  "[--rdp-today-color:oklch(0.47_0.198_305)]",
  "dark:[--rdp-today-color:oklch(0.828_0.1_305)]",
  // Opacity is a blunt instrument for "muted" — every muted state below is a real token colour
  // instead, so the library's opacities are switched off rather than compounded with ours.
  "[--rdp-disabled-opacity:1]",
  "[--rdp-outside-opacity:1]",
  "[--rdp-weekday-opacity:1]",
  "[--rdp-week_number-opacity:1]",
  // Metrics: 36px cells and the 12px (radius md) day pill.
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

/** Shared by the two month-navigation buttons. */
const NAV_BUTTON =
  "relative inline-flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-ink-700 transition " +
  "hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200 " +
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-2 focus-visible:ring-offset-card " +
  "disabled:pointer-events-none disabled:text-ink-300 " +
  "[&_svg]:pointer-events-none [&_svg]:h-4 [&_svg]:w-4 [&_svg]:shrink-0";

/**
 * The per-day button. `focus-visible` rather than `focus`: react-day-picker moves focus with the
 * arrow keys, and a ring that also fired on pointer-driven focus would flash on every click.
 */
const DAY_BUTTON =
  "relative mx-auto flex h-9 w-9 cursor-pointer items-center justify-center rounded-md text-sm tabular-nums transition " +
  "hover:bg-purple-50 dark:hover:bg-purple-950 " +
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card";

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
    // The real <select> sits transparent on top of the visible label, so it keeps native keyboard and
    // screen-reader behaviour while the tinted shell underneath does the theming.
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
    // Selection paints the BUTTON, not the cell, so the pill stays 36px inside a range band that runs
    // edge to edge. `:hover` is restated or the neutral hover wash from DAY_BUTTON greys out the very
    // day the pointer is on.
    selected:
      "[&>button]:bg-purple-700 [&>button]:font-semibold [&>button]:text-white " +
      "[&>button]:hover:bg-purple-700 dark:[&>button]:hover:bg-purple-700",
    range_start: "rounded-l-md bg-purple-50 dark:bg-purple-950",
    range_end: "rounded-r-md bg-purple-50 dark:bg-purple-950",
    // A middle day is also `selected`, so its overrides have to be forced past the pill above.
    range_middle:
      "bg-purple-50 dark:bg-purple-950 " +
      "[&>button]:!rounded-none [&>button]:!bg-transparent [&>button]:!font-normal [&>button]:!text-ink-900",
    // Today carries a ring as well as the tint, so it is still findable without colour vision.
    today:
      "[&:not([data-selected])>button]:font-semibold " +
      "[&:not([data-selected])>button]:text-purple-700 dark:[&:not([data-selected])>button]:text-purple-300 " +
      "[&:not([data-selected])>button]:ring-1 [&:not([data-selected])>button]:ring-inset " +
      "[&:not([data-selected])>button]:ring-purple-200 dark:[&:not([data-selected])>button]:ring-purple-800",
    // Guarded the same way as `today`: an out-of-month day can also be a range endpoint, and an
    // unguarded rule would tie with `selected` on specificity and be settled by stylesheet order.
    outside: "[&:not([data-selected])>button]:font-normal [&:not([data-selected])>button]:text-ink-300",
    disabled: "[&>button]:cursor-not-allowed [&>button]:text-ink-300 [&>button]:hover:bg-transparent",
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
