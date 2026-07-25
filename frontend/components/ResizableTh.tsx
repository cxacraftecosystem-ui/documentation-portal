import type { ThHTMLAttributes } from "react";

/**
 * Table header cell with browser-native column resizing: drag the grip in the cell's bottom-right
 * corner to widen or narrow the column (CSS `resize: horizontal` needs a non-visible overflow).
 * Shared by every record list table so all columns resize the same way.
 */
export function ResizableTh({ className = "", children, ...rest }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th {...rest} className={`resize-x cursor-col-resize overflow-hidden px-4 py-3 ${className}`}>
      {children}
    </th>
  );
}
