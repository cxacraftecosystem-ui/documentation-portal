import { Children, Fragment, isValidElement, type ReactElement, type ReactNode } from "react";

type Align = "center" | "end" | "start";

const ALIGN_MANY: Record<Align, string> = {
  center: "sm:justify-center",
  end: "sm:justify-end",
  start: "sm:justify-start"
};

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
  label,
  fill = false
}: {
  children?: ReactNode;
  destructive?: ReactNode;
  align?: Align;
  size?: "sm" | "md";
  className?: string;
  label?: string;
  fill?: boolean;
}) {
  const items = flatten(children);
  const danger = flatten(destructive);
  const count = items.length + danger.length;
  if (count === 0) return null;
  const height = size === "sm" ? "[&>*]:min-h-9" : "[&>*]:min-h-10";
  const extra = className ? ` ${className}` : "";
  const group = label ? { role: "group" as const, "aria-label": label } : {};

  if (count === 1 && fill) {
    return (
      <div data-button-row="1" data-fill="" className={`flex [&>*]:w-full${extra}`} {...group}>
        {items}
        {danger}
      </div>
    );
  }

  if (count === 1) {
    return (
      <div data-button-row="1" className={`flex justify-center${extra}`} {...group}>
        {items}
        {danger}
      </div>
    );
  }

  if (count === 2 && fill) {
    return (
      <div
        data-button-row="2"
        data-fill=""
        className={`grid grid-cols-2 gap-2 ${height} [&>*]:w-full [&>*]:min-w-0 max-sm:[&>*]:whitespace-normal max-sm:[&>*]:text-center${extra}`}
        {...group}
      >
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
