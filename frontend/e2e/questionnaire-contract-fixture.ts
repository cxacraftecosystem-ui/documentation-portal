import { readFileSync } from "node:fs";
import { join } from "node:path";

type Contract = {
  contractVersion: number;
  errorCodes: string[];
  errorExample: unknown;
  routes: { method: string; path: string }[];
  examples: Record<string, unknown>;
};

export const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "questionnaire-contract.json"), "utf8")) as Contract;
