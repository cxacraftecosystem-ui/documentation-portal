import type { ReportSheetList, ReportStatusFilter } from "@/lib/dataExplorer/contract";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type ReportFilterChip = { label: string; value: string };

export function reportFilterChips(statusFilter: ReportStatusFilter | undefined, allStatuses: boolean, search: string): ReportFilterChip[] {
  const chips: ReportFilterChip[] = [];
  if (statusFilter?.active && !allStatuses) chips.push({ label: "Review status", value: statusFilter.label });
  if (search.trim()) chips.push({ label: "Search", value: search.trim() });
  return chips;
}

export function withoutStatus(extra: QueryParams): QueryParams {
  return Object.fromEntries(Object.entries(extra).filter(([key]) => key !== "status"));
}

export function recordLine(list: ReportSheetList | null): string {
  return (list?.sheets ?? [])
    .filter((sheet) => sheet.record && typeof sheet.count === "number")
    .map((sheet) => `${sheet.name} ${(sheet.count ?? 0).toLocaleString("en-IN")}`)
    .join(" · ");
}

export function filtersLeaveNothing(list: ReportSheetList | null, filtered: boolean): boolean {
  return filtered && list !== null && list.records === 0;
}
