import { readFileSync } from "node:fs";
import { join } from "node:path";

import type { Page, Route } from "@playwright/test";

/**
 * AN IN-MEMORY TRANSCRIPT API FOR THE BROWSER SPEC (`e2e/transcript-web.spec.ts`), shared by both
 * portals (`shared/transcript-web.sha256`).
 *
 * The page runs for real over a production build; every `/api/...` call is answered here. The
 * transcript is the contract's own example (`shared/transcript-contract.json`), with a playable
 * audio url and, where a test asks for it, hostile markdown where the server's markdown goes. What
 * each request asked is kept in `calls`.
 */

export type Call = { method: string; path: string; query: URLSearchParams; body: unknown };
type Json = Record<string, unknown>;

const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "..", "shared", "transcript-contract.json"), "utf8"));
export const FIXTURES = CONTRACT.fixtures as { copy: Record<string, string> };
export const MEDIA_ID = "cm_media_1";
export const AUDIO_PATH = "/e2e-audio/interview.wav";

/** Thirty seconds of silence, 8 kHz mono 8-bit PCM: enough for the browser to seek and play. */
export function silentWav(seconds = 30): Buffer {
  const rate = 8000;
  const samples = rate * seconds;
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + samples, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate, 28);
  header.writeUInt16LE(1, 32);
  header.writeUInt16LE(8, 34);
  header.write("data", 36);
  header.writeUInt32LE(samples, 40);
  return Buffer.concat([header, Buffer.alloc(samples, 128)]);
}

export const HOSTILE_MARKDOWN = [
  "Safe **bold** line.",
  "<script>window.__pwned = 'script'</script>",
  "<img src=x onerror=\"window.__pwned='img'\">",
  "![tracker](https://tracker.example/pixel.png)",
  "[click me](javascript:window.__pwned='link')",
  "<iframe src=\"https://evil.example\"></iframe>",
  "[ok link](https://example.org/)"
].join("\n\n");

export class TranscriptFake {
  calls: Call[] = [];
  view: Json;
  role = "ADMIN";
  hostile = false;
  /** Pipeline off: every transcript route answers 404 NOT_FOUND and the legacy text shows. */
  off = false;

  constructor() {
    this.view = JSON.parse(JSON.stringify(CONTRACT.examples.TranscriptView));
    this.view.audio = { url: AUDIO_PATH, durationMs: 30000 };
    this.view.durationMs = 1524000;
    this.view.permissions = { edit: true, retry: true, transcribeNow: true, ask: true, autodraft: true, quoteCard: true, mapSpeakers: true };
  }

  calledWith(method: string, path: string): Call[] {
    return this.calls.filter((call) => call.method === method && call.path === path);
  }

  media(): Json {
    return {
      id: MEDIA_ID,
      mediaType: "AUDIO",
      mimeType: "audio/wav",
      originalFilename: "interview.wav",
      caption: "Interview with Giriraj",
      url: AUDIO_PATH,
      sizeBytes: 240044,
      transcriptStatus: "COMPLETED",
      transcriptText: "Legacy transcript text.",
      transcriptError: null,
      createdAt: "2026-06-24T05:00:00Z",
      recordedAt: "2026-06-24T05:00:00Z",
      linkedRecordType: null,
      linkedRecordId: null,
      uploadedBy: { id: "u-lead", name: "Nandini Rao" }
    };
  }

  private turns(): Json[] {
    return this.view.turns as Json[];
  }

  answer(method: string, path: string, query: URLSearchParams, body: Json | null): { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> } | null {
    if (path === "/me")
      return { status: 200, json: { id: "u-lead", name: "Nandini Rao", email: "nandini@example.org", role: this.role, isActive: true, mustChangePassword: false, twoFactorEnabled: false, usageConsentAt: "2026-01-01T00:00:00Z" } };
    if (path === "/preferences/me") return { status: 200, json: {} };
    if (path === `/media/${MEDIA_ID}` && method === "GET") return { status: 200, json: this.media() };
    if (path === "/media" && method === "GET") return { status: 200, json: { items: [this.media()], total: 1, page: 1, pageSize: 20, pages: 1 } };
    if (!path.startsWith("/transcripts")) return null;
    if (this.off) return { status: 404, json: { detail: { code: "NOT_FOUND", message: "Not found." } } };

    const base = `/transcripts/${MEDIA_ID}`;
    if (path === base && method === "GET") {
      const view = { ...this.view };
      if (this.hostile) view.summary = [HOSTILE_MARKDOWN];
      return { status: 200, json: view };
    }
    if (path === `${base}/speakers/suggestions`)
      return {
        status: 200,
        json: {
          items: [
            { speakerKey: "speaker_0", candidates: [{ name: "Nandini", role: "RESEARCHER", personId: "u_1", reason: "researcher" }] },
            {
              speakerKey: "speaker_1",
              candidates: [
                { name: "Giriraj", role: "ARTISAN", personId: "a_1", reason: "roster" },
                { name: "Meena Devi", role: "ARTISAN", personId: "a_2", reason: "roster" }
              ]
            }
          ]
        }
      };
    if (path === `${base}/speakers` && method === "PUT") {
      const mapping = ((body?.mappings as Json[]) ?? [])[0] ?? {};
      const label = String(mapping.name ?? "");
      this.view.speakers = (this.view.speakers as Json[]).map((speaker) => (speaker.speakerKey === mapping.speakerKey ? { ...speaker, name: label, label, personId: mapping.personId, suggested: false } : speaker));
      this.view.turns = this.turns().map((turn) => (turn.speakerKey === mapping.speakerKey ? { ...turn, speakerName: label } : turn));
      this.view.revision = Number(this.view.revision) + 1;
      return { status: 200, json: this.view };
    }
    let m = new RegExp(`^${base}/turns/([^/]+)$`).exec(path);
    if (m && method === "PATCH") {
      const id = decodeURIComponent(m[1]);
      let updated: Json | null = null;
      this.view.turns = this.turns().map((turn) => {
        if (turn.id !== id) return turn;
        const translation = { ...(turn.translation as Json) };
        const next: Json = { ...turn, revision: Number(turn.revision) + 1 };
        if (typeof body?.text === "string") {
          next.text = body.text;
          next.textSource = "EDITED";
          next.words = [];
          next.lowConfidence = [];
          translation.status = "STALE";
        }
        if (typeof body?.english === "string") {
          translation.text = body.english;
          translation.source = "EDITED";
          translation.status = "TRANSLATED";
        }
        next.translation = translation;
        updated = next;
        return next;
      });
      return updated ? { status: 200, json: updated } : { status: 404, json: { detail: { code: "TURN_UNKNOWN", message: "No such turn." } } };
    }
    m = new RegExp(`^${base}/turns/([^/]+)/retranslate$`).exec(path);
    if (m && method === "POST") {
      const id = decodeURIComponent(m[1]);
      let updated: Json | null = null;
      this.view.turns = this.turns().map((turn) => {
        if (turn.id !== id) return turn;
        updated = { ...turn, revision: Number(turn.revision) + 1, translation: { text: `Re-translated: ${turn.text}`, status: "TRANSLATED", source: "MACHINE", model: "gemini-3.8-flash" } };
        return updated;
      });
      return { status: 200, json: updated };
    }
    if (path === `${base}/download`) {
      const format = query.get("format") ?? "md";
      const track = query.get("track") ?? "original";
      const name = `DesignWorkshop_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Transcript.${format}`;
      return {
        status: 200,
        raw: Buffer.from(
          format === "md"
            ? (query.get("timestamps") === "false" ? FIXTURES.copy[`all_${track}_no_timestamps`] : FIXTURES.copy[`all_${track}`]) ?? ""
            : `${format}:${track}`
        ),
        contentType: format === "md" ? "text/markdown" : "application/octet-stream",
        headers: { "content-disposition": `attachment; filename="${name}"` }
      };
    }
    if (path === `${base}/ask` && method === "POST") {
      if (this.hostile) return { status: 200, json: { covered: true, answer: HOSTILE_MARKDOWN, citations: [{ turnId: "turn_b", startMs: 9000, quote: null }], model: "gemini-3.8-flash" } };
      const question = String(body?.question ?? "");
      return { status: 200, json: question.includes("weather") ? CONTRACT.examples.AskAnswerNotCovered : CONTRACT.examples.AskAnswer };
    }
    if (path === `${base}/quote-card` && method === "POST")
      return {
        status: 200,
        json: {
          turnIds: body?.turnIds ?? [],
          original: "हम dabu के लिए मिट्टी और गोंद मिलाते हैं",
          english: "We mix clay and gum for dabu (mud-resist printing).",
          speakerName: "Giriraj",
          artisanName: "Giriraj Prasad Chhipa",
          craft: "Hand Block Printing",
          date: "24 Jun 2026",
          language: { code: "hi", label: "Hindi" },
          clip: { startMs: 8500, endMs: 15700, url: AUDIO_PATH },
          markdown: FIXTURES.copy.selected_both_turn_b
        }
      };
    if (path === "/transcripts/budget")
      return { status: 200, json: CONTRACT.examples.BudgetMeter };
    if (path === "/transcripts/glossary")
      return {
        status: 200,
        json: {
          items: [{ id: "g1", term: "dabu", gloss: "mud-resist printing", craftId: query.get("craftId"), craftName: "Hand Block Printing", occurrences: 12, sightings: [{ mediaId: MEDIA_ID, turnId: "turn_b", startMs: 9000 }] }],
          nextCursor: null
        }
      };
    return { status: 404, json: { detail: { code: "NOT_FOUND", message: "Not found." } } };
  }

  async install(page: Page): Promise<void> {
    await page.addInitScript(() => {
      window.localStorage.setItem("field_repo_token", "e2e-transcript-fixture");
      window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: "light", reducedMotion: true, largerText: false, highContrast: false }));
    });
    const wav = silentWav();
    await page.route(
      (url) => url.pathname === AUDIO_PATH,
      (route: Route) => {
        // Byte ranges, so the browser can seek: a media element seeks a stream without them badly.
        const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers()["range"] ?? "");
        if (!range) return route.fulfill({ status: 200, contentType: "audio/wav", headers: { "accept-ranges": "bytes" }, body: wav });
        const start = Number(range[1]);
        const end = range[2] ? Math.min(Number(range[2]), wav.length - 1) : wav.length - 1;
        return route.fulfill({
          status: 206,
          contentType: "audio/wav",
          headers: { "accept-ranges": "bytes", "content-range": `bytes ${start}-${end}/${wav.length}` },
          body: wav.subarray(start, end + 1)
        });
      }
    );
    await page.route(
      (url) => url.pathname.startsWith("/api/") && !url.pathname.startsWith("/api/session/"),
      async (route: Route) => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname.replace(/^\/api/, "");
        let body: Json | null = null;
        try {
          body = request.postDataJSON() as Json | null;
        } catch {
          body = null;
        }
        this.calls.push({ method: request.method(), path, query: url.searchParams, body });
        const out = this.answer(request.method(), path, url.searchParams, body);
        if (!out) return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not Found" }) });
        if (out.raw) return route.fulfill({ status: out.status, contentType: out.contentType, headers: out.headers, body: out.raw });
        return route.fulfill({ status: out.status, contentType: "application/json", headers: { date: new Date().toUTCString() }, body: JSON.stringify(out.json ?? null) });
      }
    );
  }
}
