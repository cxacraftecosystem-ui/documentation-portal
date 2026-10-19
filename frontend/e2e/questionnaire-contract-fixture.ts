import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * The questionnaire contract the web unit specs mock from: `shared/questionnaire-contract.json`,
 * copied verbatim from `feature/q-backend` (BUILD_PLAN §0.1). Never edited here.
 */
type Contract = {
  contractVersion: number;
  errorCodes: string[];
  errorExample: unknown;
  routes: { method: string; path: string }[];
  examples: Record<string, unknown>;
};

export const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "questionnaire-contract.json"), "utf8")) as Contract;
