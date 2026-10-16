import { Children, Fragment, isValidElement, type ReactElement, type ReactNode } from "react";

/**
 * The one layout for buttons that sit together. Every button row in the app goes through this, so
 * the rule is decided here and not re-styled per screen:
 *
 * - ONE button on its line is centred.
 * - TWO share one line at equal widths and never overflow; below `sm` (640px) a long label wraps
 *   inside its own button instead of pushing the other off the edge.
 * - THREE OR MORE stack full width below `sm`, and from `sm` up become one row of pills
 *   (`rounded-full`, one height), wrapping only when the row genuinely runs out of room.
 * - `destructive` is always drawn last, and from `sm` up is set apart from the rest by a rule.
 *
 * `align` places a row of two or more on wide screens (a form footer sits `end`, beside nothing);
 * a single button is always centred, whatever `align` says, because a lone button off to one side
 * reads as something missing beside it.
 * `size="sm"` is the compact density of a list row's actions; the default is the form height.
 */

type Align = "center" | "end" | "start";

const ALIGN_MANY: Record<Align, string> = {
  center: "sm:justify-center",
  end: "sm:justify-end",
  start: "sm:justify-start"
};

/** Fragments are unwrapped so `<>{a}{b}</>` counts as two buttons, not one. */
function flatten(children: ReactNode): ReactNode[] {
  const out: ReactNode[] = [];
  for (const child of Children.toArray(children)) {
    if (isValidElement(child) && child.type === Fragment) {
      out.push(...flatten((child as ReactElement<{ children?: ReactNode }>).props.children));
    } else {
      out.push(child);
    }
  }
  return out;
}

export function buttonCount(children: ReactNode, destructive?: ReactNode): number {
  return flatten(children).length + flatten(destructive).length;
}

export function ButtonRow({
  children,
  destructive,
  align = "center",
  size = "md",
  className,
  label
}: {
  children?: ReactNode;
  /** Delete / remove / reject — always last, and set apart on wide screens. */
  destructive?: ReactNode;
  align?: Align;
  size?: "sm" | "md";
  className?: string;
  /** Names the group for assistive technology when the buttons need context. */
  label?: string;
}) {
  const items = flatten(children);
  const danger = flatten(destructive);
  const count = items.length + danger.length;
  if (count === 0) return null;
  const height = size === "sm" ? "[&>*]:min-h-9" : "[&>*]:min-h-10";
  const extra = className ? ` ${className}` : "";
  const group = label ? { role: "group" as const, "aria-label": label } : {};

  if (count === 1) {
    return (
      <div data-button-row="1" className={`flex justify-center${extra}`} {...group}>
        {items}
        {danger}
      </div>
    );
  }

  if (count === 2) {
    return (
      <div
        data-button-row="2"
        className={`grid grid-cols-2 gap-2 ${height} [&>*]:w-full [&>*]:min-w-0 max-sm:[&>*]:whitespace-normal max-sm:[&>*]:text-center sm:flex sm:flex-wrap sm:[&>*]:w-auto ${ALIGN_MANY[align]}${extra}`}
        {...group}
      >
        {items}
        {danger}
      </div>
    );
  }

  return (
    <div
      data-button-row="many"
      className={`flex flex-col gap-2 ${height} [&>*]:w-full sm:flex-row sm:flex-wrap sm:items-center sm:[&>*]:w-auto sm:[&>*]:rounded-full ${ALIGN_MANY[align]}${extra}`}
      {...group}
    >
      {items}
      {danger.length > 0 ? (
        <>
          <span aria-hidden className="hidden h-6 w-px self-center bg-line-200 sm:block sm:min-h-0! sm:w-px!" />
          {danger}
        </>
      ) : null}
    </div>
  );
}
