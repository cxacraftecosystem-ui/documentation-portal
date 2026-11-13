import { apiErrorMessage } from "@/lib/api";

export function readableError(error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback);
}
