import type { ThHTMLAttributes } from "react";

export function ResizableTh({ className = "", children, ...rest }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th {...rest} className={`resize-x cursor-col-resize overflow-hidden px-4 py-3 pointer-coarse:cursor-auto pointer-coarse:resize-none ${className}`}>
      {children}
    </th>
  );
}
