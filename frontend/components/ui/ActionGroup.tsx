import { Children, Fragment, isValidElement, type ReactNode } from "react";

import { actionGroupClass, type ActionGroupAlign } from "@/components/ui/actionGroupLayout";

export function ActionGroup({
  children,
  align = "center",
  fill = false,
  inline = false,
  className,
  ref,
  ...rest
}: {
  children: ReactNode;
  align?: ActionGroupAlign;
  fill?: boolean;
  inline?: boolean;
  className?: string;
  ref?: React.Ref<HTMLDivElement>;
} & Omit<React.HTMLAttributes<HTMLDivElement>, "children" | "className">) {
  const count = countActions(children);
  if (count === 0) return null;
  const layout = actionGroupClass(count, align, fill, inline);
  return (
    <div ref={ref} className={className ? `${layout} ${className}` : layout} {...rest}>
      {children}
    </div>
  );
}

export function countActions(children: ReactNode): number {
  let count = 0;
  Children.forEach(children, (child) => {
    if (child === null || child === undefined || typeof child === "boolean" || child === "") return;
    if (isValidElement<{ children?: ReactNode }>(child) && child.type === Fragment) {
      count += countActions(child.props.children);
      return;
    }
    count += 1;
  });
  return count;
}
