import type { Page, Route } from "@playwright/test";

/**
 * AN IN-MEMORY QUESTIONNAIRE API FOR THE BROWSER SPECS (`e2e/questionnaire-fr-web.spec.ts`).
 *
 * Field Repository's copy of designer-portal's fake (U1): the same session, take and answer shapes
 * (they are the shared contract's), with this portal's workshops (`/workshops`, `workshopId`) and its
 * forms library (`GET /questionnaires` answers an array, `scope`, `can`).
 *
 * The pages run for real in a real browser over a production build; every `/api/...` call they make
 * is answered here from a small state that the routes below change the way the server would. The
 * shapes are the contract's (`lib/questionnaire/api.ts`), so a screen that reads a field the server
 * does not send fails here. What each request asked is kept in `calls`, so a spec can assert the
 * body a screen sent. The real server's rules are held by the backend suites and by the real-stack
 * specs (`e2e/questionnaire-v2-stack.spec.ts`, `e2e/questionnaire-library-stack.spec.ts`).
 */

export type Call = { method: string; path: string; query: URLSearchParams; body: unknown };

type Json = Record<string, unknown>;

export const ME = { id: "u-lead", name: "Nandini Rao", email: "nandini@example.org", role: "ADMIN" };
export const OTHER = { id: "u-other", name: "Priya Shah" };

export const FORM = {
  questionnaireId: "f1",
  title: "2nd Craft Toolkit",
  kind: "WORKSHOP_INTERVIEW",
  isActive: true,
  version: 1,
  sections: [
    {
      id: "s1",
      code: "A",
      title: "Tools",
      sortOrder: 1,
      questions: [
        { id: "q1", prompt: "Which tools do you use?", helpText: null, sortOrder: 1, isActive: true, isRequired: true, lineageId: "l1", displayCode: "A1", artisanField: null },
        { id: "q2", prompt: "Who made them?", helpText: null, sortOrder: 2, isActive: true, isRequired: false, lineageId: "l2", displayCode: "A2", artisanField: null }
      ]
    },
    {
      id: "s2",
      code: "B",
      title: "Dyes",
      sortOrder: 2,
      questions: [{ id: "q3", prompt: "Which dyes?", helpText: null, sortOrder: 1, isActive: true, isRequired: true, lineageId: "l3", displayCode: "B1", artisanField: null }]
    }
  ]
};

export const WORKSHOP = {
  id: "w1",
  title: "Bagru block printing 2026",
  status: "APPROVED",
  workshopType: "DESIGN_PROTOTYPE",
  date: "2026-10-01T00:00:00Z",
  startDate: "2026-10-01T00:00:00Z",
  endDate: null,
  createdAt: "2026-10-01T00:00:00Z",
  place: "Bagru",
  canRecord: true,
  questionnaireId: null,
  location: { village: "Bagru", district: "Jaipur", state: "Rajasthan" },
  crafts: [{ craft: { name: "Hand Block Printing" } }]
};

const ARTISANS = [
  { id: "a1", name: "Giriraj Prasad Chhipa", localName: null, place: "Bagru", craft: { name: "Hand Block Printing" } },
  { id: "a2", name: "Meena Devi", localName: null, place: "Bagru", craft: { name: "Hand Block Printing" } }
];

function session(id: string, over: Json = {}): Json {
  return {
    id,
    clientKey: `ck-${id}`,
    version: 2,
    title: `Interview_2026-10-22_Bagru-Jaipur_Hand-Block-Printing_${id}`,
    note: null,
    legacyTitle: null,
    questionnaire: { id: FORM.questionnaireId, title: FORM.title },
    workshop: { id: WORKSHOP.id, name: WORKSHOP.title },
    designWorkshopId: null,
    sessionDate: "2026-10-22",
    sessionDateEstimated: false,
    startedAt: "2026-10-22T05:00:00Z",
    endedAt: null,
    closedAt: null,
    state: "OPEN",
    purpose: "FIRST_SITTING",
    origin: "NATIVE",
    mode: "GROUP",
    participantCount: 1,
    place: "Bagru",
    languageCode: "hi",
    languageOther: null,
    joinCode: "K7M2QP",
    joinQr: `{"v":1,"k":"ck-${id}"}`,
    participants: [{ artisanId: "a1", name: "Giriraj Prasad Chhipa", joinedAt: null, leftAt: null, consent: { state: "NOT_RECORDED", source: null } }],
    researchers: [
      { userId: ME.id, name: ME.name, role: "LEAD" },
      { userId: OTHER.id, name: OTHER.name, role: "CONTRIBUTOR" }
    ],
    status: "PENDING",
    submittedAt: null,
    reviewReasons: [],
    counts: { sections: 1, takes: 1, needsDecision: 0 },
    respondents: [{ id: "r1", sessionId: id, name: "Shopper at Johari Bazaar", kind: "GUEST", notes: null, userId: null, artisanId: null }],
    source: "APP",
    deletedAt: null,
    ...over
  };
}

function recording(id: string, over: Json = {}): Json {
  return {
    id,
    mediaFileId: `m-${id}`,
    clientClipKey: `clip-${id}`,
    sessionId: "s-1",
    version: 1,
    capturedAt: "2026-10-22T05:10:00Z",
    capturedAtSource: "DEVICE",
    estimated: false,
    durationMs: 92000,
    rosterCount: 1,
    recordedBy: { id: ME.id, name: ME.name },
    targets: [{ targetKey: "S:s1", sectionId: "s1", questionLineageId: null, askedQuestionId: null, displayCode: null, label: "Section A" }],
    subjects: [{ artisanId: "a1", role: "ANSWERED", named: false, repeatReason: null, flag: null, position: 1 }],
    respondents: [],
    continuationOfId: null,
    filing: "FILED",
    decisionKind: null,
    provenance: "NATIVE",
    languageCode: "hi",
    removedAt: null,
    downloadName: "FieldRepository_Interview_2026-10-22_Bagru_Recording_A.m4a",
    transcript: { status: "COMPLETED", paused: false, text: "We use wooden blocks carved in Pethapur.", language: "hi" },
    consent: { verdict: "ALLOW", source: null },
    ...over
  };
}

/** A form as Field Repository's `GET /questionnaires` answers it, with what an admin may do. */
function libraryForm(over: Json & { deleted?: boolean }): Json {
  const deleted = Boolean(over.deleted);
  const { deleted: _drop, ...rest } = over;
  void _drop;
  return {
    description: null, isActive: true, isDefault: false, sortOrder: 1, version: 1, sourceFilename: null, sectionCount: 0, questionCount: 0,
    workshopCount: 0, createdAt: "2026-10-01T00:00:00Z", isShared: false, kind: null, kindLabel: "Kind not stated", deletedAt: null, deletedById: null,
    can: { edit: !deleted, delete: !deleted, restore: deleted, share: !deleted, bind: !deleted, setDefault: !deleted },
    ...rest
  };
}

export class QuestionnaireFake {
  calls: Call[] = [];
  v2 = true;
  role = ME.role;
  sessions: Json[] = [session("s-1")];
  deleted: Json[] = [session("s-gone", { title: "Interview_2026-10-20_Bagru_Deleted", deletedAt: "2026-10-21T10:00:00Z" })];
  answers: Json[] = [
    // Priya's typed answer for Giriraj: locked to the lead? No: the lead may change it. Locked to others.
    { id: "ans-1", version: 1, text: "Wooden blocks", origin: "TYPED", attribution: "INDIVIDUAL", pinned: false, sourceRecordingId: null, artisanId: "a1", respondentId: null, questionLineageId: "l1", actorId: OTHER.id, note: null, status: "PENDING" }
  ];
  recordings: Json[] = [recording("rec-1"), recording("rec-gone", { removedAt: "2026-10-22T06:00:00Z", subjects: [] })];
  overrides: Record<string, string> = { "a1|s2": "NEEDS_REVIEW" };
  bindings: Json[] = [{ workshopId: WORKSHOP.id, designWorkshopId: null, isPrimary: true }];
  forms: Json[] = [
    libraryForm({ id: "f1", title: FORM.title, ownerId: ME.id, createdById: ME.id, kind: "WORKSHOP_INTERVIEW", kindLabel: "Workshop interview", sectionCount: 2, questionCount: 3, workshopCount: 1 }),
    libraryForm({ id: "f2", title: "Market survey 2026", ownerId: "u-x", createdById: "u-x", isShared: true, kind: "MARKET_SURVEY", kindLabel: "Market survey" })
  ];
  createdForms: Json[] = [];
  deletedForms: Json[] = [
    libraryForm({ id: "fold", title: "Old baseline", ownerId: ME.id, createdById: ME.id, isActive: false, deletedAt: "2026-10-20T00:00:00Z", deleted: true })
  ];
  typeDefaults: Json[] = [];

  /** The grid the workspace draws: Giriraj recorded on A, typed-only on B (✎), the guest typed on A. */
  grid(): Json {
    const typedB = this.answers.some((a) => a.artisanId === "a1" && a.questionLineageId === "l3" && String(a.text).trim());
    return {
      questionnaireId: "f1",
      artisans: [{ artisanId: "a1", name: "Giriraj Prasad Chhipa" }],
      respondents: [{ respondentId: "r1", name: "Shopper at Johari Bazaar", subjectKey: "R:r1" }],
      sections: [
        {
          sectionId: "s1",
          code: "A",
          title: "Tools",
          cells: [{ artisanId: "a1", subjectKey: "A:a1", state: "RECORDED", takes: 1, covered: 2, total: 2, needsDecision: false, override: this.overrides["a1|s1"] ?? null, source: "RECORDED", addRecording: false }],
          guestCells: [{ respondentId: "r1", subjectKey: "R:r1", state: "PARTIAL", takes: 0, covered: 1, total: 2, needsDecision: false, override: null, source: "TYPED", addRecording: true }]
        },
        {
          sectionId: "s2",
          code: "B",
          title: "Dyes",
          cells: [
            {
              artisanId: "a1",
              subjectKey: "A:a1",
              state: typedB ? "PARTIAL" : "NOT_RECORDED",
              takes: 0,
              covered: typedB ? 1 : 0,
              total: 1,
              needsDecision: false,
              override: this.overrides["a1|s2"] ?? null,
              source: typedB ? "TYPED" : null,
              addRecording: typedB
            }
          ],
          guestCells: [{ respondentId: "r1", subjectKey: "R:r1", state: "NOT_RECORDED", takes: 0, covered: 0, total: 1, needsDecision: false, override: null, source: null, addRecording: false }]
        }
      ]
    };
  }

  calledWith(method: string, path: string | RegExp): Call[] {
    return this.calls.filter((c) => c.method === method && (typeof path === "string" ? c.path === path : path.test(c.path)));
  }

  private answer(method: string, path: string, query: URLSearchParams, body: Json | null): { status: number; json?: unknown } | null {
    const one = (re: RegExp) => re.exec(path);
    let m0: RegExpExecArray | null = null;
    if (path === "/me") return { status: 200, json: { ...ME, role: this.role, isActive: true, mustChangePassword: false, twoFactorEnabled: false, usageConsentAt: "2026-01-01T00:00:00Z" } };
    if (path === "/preferences/me") return { status: 200, json: {} };
    if (path === "/questionnaire/config")
      return {
        status: 200,
        json: {
          contractVersion: 1, v2Enabled: this.v2, transcriptionPaused: false, noteMaxChars: 80, multipartThresholdBytes: 5242880, partSizeBytes: 8388608,
          snapshotPollSeconds: 600, joinCodeLength: 6, minAppVersionForCreate: null, portal: "documentation-portal", minVersionCode: { android: null, web: null }
        }
      };
    if (!this.v2 && path.startsWith("/questionnaire/")) return { status: 404, json: { detail: { code: "NOT_FOUND", message: "Not found." } } };
    if (path === "/workshops" && method === "GET") return { status: 200, json: { items: [WORKSHOP], total: 1, page: 1, pageSize: 100, pages: 1 } };
    if (path === "/questionnaires" && method === "GET") {
      const scope = query.get("scope");
      const all = [...this.forms, ...this.createdForms];
      const items =
        scope === "deleted" ? this.deletedForms
        : scope === "mine" ? all.filter((f) => f.ownerId === ME.id)
        : scope === "shared" ? all.filter((f) => f.isShared)
        : scope === "workshop" ? all.filter((f) => this.bindings.length && f.id === "f1")
        : all;
      return { status: 200, json: items };
    }
    if (path === "/questionnaires/bundles/import" && method === "POST") {
      const b = body as { form?: { title?: string; kind?: string } };
      const id = `fnew${this.createdForms.length + 1}`;
      const form = libraryForm({ id, title: b.form?.title ?? "", kind: b.form?.kind ?? null, ownerId: ME.id, createdById: ME.id, sectionCount: 1, questionCount: 1 });
      this.createdForms.push(form);
      return { status: 201, json: { questionnaire: form, created: true } };
    }
    if (path === "/questionnaire/type-defaults" && method === "GET") return { status: 200, json: { workshopTypes: ["DESIGN_PROTOTYPE", "OTHER"], defaults: this.typeDefaults } };
    if (path === "/questionnaire/sections" && method === "GET")
      return {
        status: 200,
        json: FORM.sections.map((sec) => ({
          id: sec.id, questionnaireId: "f1", code: sec.code, title: sec.title, sortOrder: sec.sortOrder, isActive: true,
          questions: sec.questions.map((q) => ({ id: q.id, questionnaireId: "f1", sectionId: sec.id, sectionCode: sec.code, sectionTitle: sec.title, prompt: q.prompt, helpText: null, isRequired: q.isRequired, sortOrder: q.sortOrder, isActive: true }))
        }))
      };
    m0 = /^\/questionnaires\/([^/]+)\/restore$/.exec(path);
    if (m0 && method === "POST") {
      const at = this.deletedForms.findIndex((f) => f.id === m0![1]);
      const form = at >= 0 ? this.deletedForms.splice(at, 1)[0] : null;
      if (form) this.forms.push({ ...form, deletedAt: null });
      return { status: 200, json: { id: m0[1], title: form?.title ?? "" } };
    }
    if (path === "/questionnaires" && method === "POST") {
      const id = `fnew${this.createdForms.length + 1}`;
      const form = libraryForm({ id, ...(body ?? {}), ownerId: ME.id, createdById: ME.id, sections: [] });
      this.createdForms.push(form);
      return { status: 201, json: form };
    }
    let m = one(/^\/questionnaires\/([^/]+)\/sections$/);
    if (m && method === "POST") {
      const form = this.createdForms.find((f) => f.id === m![1]) as Json & { sections: Json[] };
      form.sections.push({ id: `${form.id}-s${form.sections.length + 1}`, code: (body as Json).code, title: (body as Json).title, sortOrder: form.sections.length + 1, isActive: true, questions: [] });
      return { status: 201, json: form };
    }
    m = one(/^\/questionnaires\/([^/]+)\/sections\/([^/]+)\/questions$/);
    if (m && method === "POST") {
      const form = this.createdForms.find((f) => f.id === m![1]) as Json & { sections: (Json & { questions: Json[] })[] };
      form.sections.find((s) => s.id === m![2])!.questions.push({ id: `q${Date.now()}`, ...(body as Json), isActive: true });
      return { status: 201, json: form };
    }
    if (path === "/artisans/a1" && method === "GET")
      return { status: 200, json: { id: "a1", name: "Giriraj Prasad Chhipa", place: "Bagru", status: "APPROVED", createdById: ME.id, craft: { name: "Hand Block Printing" }, workshopId: WORKSHOP.id } };
    if (path === "/questionnaires/f1" && method === "GET")
      return {
        status: 200,
        json: {
          ...this.forms[0],
          sections: FORM.sections.map((sec) => ({
            id: sec.id, code: sec.code, title: sec.title, sortOrder: sec.sortOrder, isActive: true,
            questions: sec.questions.map((q) => ({ id: q.id, prompt: q.prompt, helpText: null, isRequired: q.isRequired, sortOrder: q.sortOrder, isActive: true }))
          }))
        }
      };
    if (path === "/artisans" && method === "GET") return { status: 200, json: { items: ARTISANS, total: ARTISANS.length, page: 1, pageSize: 80, pages: 1 } };
    m = one(/^\/artisans\/([^/]+)\/consent$/);
    if (m) return { status: 200, json: { artisanId: m[1], purpose: "EXTERNAL_PROCESSING", state: "NOT_RECORDED", basis: null, source: null, recordedAt: null } };
    if (path === "/users/directory") return { status: 200, json: [{ id: "u-third", name: "Ravi Kumar", email: "ravi@example.org", role: "DESIGNER" }] };

    // ── sessions ───────────────────────────────────────────────────────────────────────────────
    if (path === "/questionnaire/sessions" && method === "GET") {
      const items = query.get("deleted") === "true" ? this.deleted : this.sessions;
      const search = (query.get("search") ?? "").toLowerCase();
      return { status: 200, json: { items: items.filter((s) => !search || String(s.title).toLowerCase().includes(search)), nextCursor: null, capped: false } };
    }
    if (path === "/questionnaire/sessions" && method === "POST") {
      const id = `s-new${this.sessions.length + 1}`;
      const b = body as Json & { participants: { artisanId: string }[]; respondents?: { name: string; kind: string }[] };
      const created = session(id, {
        clientKey: b.clientKey,
        version: 1,
        workshop: b.workshopId ? { id: String(b.workshopId), name: WORKSHOP.title } : null,
        participants: b.participants.map((p) => ({ artisanId: p.artisanId, name: ARTISANS.find((a) => a.id === p.artisanId)?.name ?? "", joinedAt: null, leftAt: null, consent: null })),
        participantCount: b.participants.length,
        respondents: (b.respondents ?? []).map((r, i) => ({ id: `rn${i}`, sessionId: id, name: r.name, kind: r.kind, notes: null, userId: null, artisanId: null }))
      });
      this.sessions.unshift(created);
      return { status: 201, json: created };
    }
    if (path === "/questionnaire/form-for") {
      return {
        status: 200,
        json: {
          questionnaireId: query.get("questionnaireId") ?? (query.get("workshopId") ? "f1" : null),
          reason: query.get("questionnaireId") ? "EXPLICIT" : "PRIMARY_BINDING",
          bound: query.get("workshopId") ? [{ questionnaireId: "f1", isPrimary: true, kind: "WORKSHOP_INTERVIEW" }] : [],
          boundByKind: []
        }
      };
    }
    m = one(/^\/questionnaire\/forms\/([^/]+)\/structure$/);
    if (m) return { status: 200, json: FORM };
    m = one(/^\/questionnaire\/forms\/([^/]+)\/bindings$/);
    if (m && method === "GET") return { status: 200, json: { questionnaireId: m[1], bindings: this.bindings } };
    if (m && method === "PUT") {
      this.bindings = ((body as Json).bindings as Json[]) ?? [];
      return { status: 200, json: { questionnaireId: m[1], bindings: this.bindings } };
    }
    if (path === "/questionnaire/type-defaults") {
      const b = body as Json;
      this.typeDefaults = this.typeDefaults.filter((d) => d.workshopType !== b.workshopType);
      if (b.questionnaireId) this.typeDefaults.push({ questionnaireId: b.questionnaireId, title: FORM.title, workshopType: b.workshopType, designWorkshopKind: null });
      return { status: 200, json: { questionnaireId: b.questionnaireId } };
    }
    m = one(/^\/questionnaire\/sessions\/([^/]+)$/);
    if (m && method === "GET") {
      const s = [...this.sessions, ...this.deleted].find((x) => x.id === m![1]);
      if (!s) return { status: 404, json: { detail: { code: "NOT_FOUND", message: "Not found." } } };
      return { status: 200, json: { ...s, recordings: this.recordings.filter((r) => !r.removedAt), answers: this.answers, grid: this.grid() } };
    }
    if (m && method === "DELETE") {
      const at = this.sessions.findIndex((x) => x.id === m![1]);
      if (at >= 0) this.deleted.unshift({ ...this.sessions.splice(at, 1)[0], deletedAt: "2026-10-22T09:00:00Z" });
      return { status: 204 };
    }
    if (m && method === "PATCH") {
      const s = this.sessions.find((x) => x.id === m![1])!;
      Object.assign(s, body, { version: Number(s.version) + 1 });
      return { status: 200, json: s };
    }
    m = one(/^\/questionnaire\/sessions\/([^/]+)\/restore$/);
    if (m) {
      const at = this.deleted.findIndex((x) => x.id === m![1]);
      const s = at >= 0 ? { ...this.deleted.splice(at, 1)[0], deletedAt: null } : null;
      if (s) this.sessions.unshift(s);
      return { status: 200, json: s };
    }
    m = one(/^\/questionnaire\/sessions\/([^/]+)\/(finish|researchers|respondents|merge-into\/[^/]+)$/);
    if (m) return { status: 200, json: m[2] === "respondents" ? { respondents: [] } : this.sessions[0] };
    m = one(/^\/questionnaire\/sessions\/([^/]+)\/respondents\/([^/]+)$/);
    if (m) return { status: 200, json: { id: m[2], sessionId: m[1], name: (body as Json)?.name ?? "", kind: "GUEST", notes: (body as Json)?.notes ?? null, userId: null } };
    if (path === "/questionnaire/sessions/continue") return { status: 200, json: { sessions: [session("s-next")], firstGap: {} } };
    if (path === "/questionnaire/coverage/snapshot") return { status: 200, json: { asOfSeq: "1", takes: [], removedRecordingIds: [] } };
    if (path === "/questionnaire/coverage") return { status: 200, json: this.grid() };
    if (path === "/questionnaire/completion" && method === "PUT") {
      const b = body as { artisanId: string; sectionId: string; status: string | null };
      if (b.status) this.overrides[`${b.artisanId}|${b.sectionId}`] = b.status;
      else delete this.overrides[`${b.artisanId}|${b.sectionId}`];
      return { status: 200, json: { cleared: !b.status } };
    }

    // ── answers ────────────────────────────────────────────────────────────────────────────────
    if (path === "/questionnaire/answers" && method === "PUT") {
      const items = ((body as Json).items as Json[]) ?? [];
      const results = items.map((item) => {
        const key = (a: Json) => (a.artisanId ? `A:${a.artisanId}` : `R:${a.respondentId}`);
        const current = this.answers.find((a) => key(a) === key(item) && a.questionLineageId === item.questionLineageId);
        const base = { artisanId: item.artisanId ?? null, respondentId: item.respondentId ?? null, questionLineageId: item.questionLineageId };
        // THE LOCK (owner decision 2): someone else's answer with text, unless the caller leads or is an admin.
        if (current && String(current.text).trim() && current.actorId !== ME.id && this.role !== "ADMIN") {
          return { ...base, status: "FORBIDDEN", code: "ANSWER_LOCKED", answer: null, current };
        }
        const saved = { id: current?.id ?? `ans-${this.answers.length + 1}`, version: Number(current?.version ?? 0) + 1, text: item.text, origin: item.origin, attribution: item.attribution, pinned: false, sourceRecordingId: item.sourceRecordingId ?? null, actorId: ME.id, note: item.note ?? null };
        if (current) Object.assign(current, saved);
        else this.answers.push({ ...saved, ...base });
        return { ...base, status: "SAVED", answer: saved, current: null };
      });
      return { status: 200, json: { results } };
    }
    m = one(/^\/questionnaire\/answers\/([^/]+)\/revisions$/);
    if (m)
      return {
        status: 200,
        json: {
          answerId: m[1],
          revisions: [
            { id: "rv2", text: "Wooden blocks", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: "s-1", sourceRecordingId: null, status: "PENDING", actor: { id: OTHER.id, name: OTHER.name }, createdAt: "2026-10-22T06:00:00Z", current: true },
            { id: "rv1", text: "Blocks", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: "s-1", sourceRecordingId: null, status: "PENDING", actor: { id: ME.id, name: ME.name }, createdAt: "2026-10-22T05:30:00Z", current: false }
          ]
        }
      };
    m = one(/^\/questionnaire\/recordings\/([^/]+)\/answer-proposals$/);
    if (m)
      return {
        status: 200,
        json: { proposals: [{ questionLineageId: "l2", artisanIds: ["a1"], text: "My uncle carves them in Pethapur.", origin: "TRANSCRIPT", existing: [] }] }
      };
    if (path === "/questionnaire/recordings/removed") return { status: 200, json: { items: this.recordings.filter((r) => r.removedAt) } };
    m = one(/^\/questionnaire\/recordings\/([^/]+)\/restore$/);
    if (m) {
      const r = this.recordings.find((x) => x.id === m![1]);
      if (r) r.removedAt = null;
      return { status: 200, json: r };
    }
    m = one(/^\/questionnaire\/recordings\/([^/]+)\/events$/);
    if (m) return { status: 200, json: { recordingId: m[1], events: [{ id: "e1", kind: "MOVED", before: null, after: null, actor: { id: ME.id, name: ME.name }, at: "2026-10-22T07:00:00Z" }] } };
    if (path === "/questionnaire/inbox")
      return {
        status: 200,
        json: {
          toFile: [], needsDecision: [], legacy: [], setDates: [], noParticipants: [],
          emptySessions: [{ sessionId: "s-empty", title: "Interview_2026-10-21_Bagru", sessionDate: "2026-10-21", startedAt: null }],
          primaryChanged: [], counts: { total: 1, olderThan24h: 0 }
        }
      };
    if (path === "/questionnaire/annexure")
      return {
        status: 200,
        json: {
          workshopId: WORKSHOP.id,
          workshopTitle: WORKSHOP.title,
          groups: [
            {
              kind: "WORKSHOP_INTERVIEW",
              label: "Workshop interview",
              stage: "WORKSHOP_EXPORT_ANNEXURE",
              forms: [
                {
                  id: "f1", title: FORM.title, kind: "WORKSHOP_INTERVIEW", sessionCount: 1,
                  sections: [{ code: "A", title: "Tools", questions: [{ displayCode: "A1", prompt: "Which tools do you use?", answers: [{ subject: "Giriraj Prasad Chhipa", text: "Wooden blocks" }] }] }]
                }
              ]
            }
          ]
        }
      };
    m = one(/^\/artisans\/([^/]+)\/record$/);
    if (m) {
      const s = this.sessions[0];
      return {
        status: 200,
        json: {
          artisanId: m[1],
          instruments: [
            {
              questionnaireId: "f1",
              title: FORM.title,
              summary: { sectionsCovered: 1, sectionsTotal: 2, sessions: 2, repeats: 0, open: ["B"] },
              sessions: [
                { session: s, recordings: [this.recordings[0]], uncertain: [] },
                { session: session("s-0", { sessionDate: "2026-10-20", researchers: [{ userId: OTHER.id, name: OTHER.name, role: "LEAD" }] }), recordings: [], uncertain: [] }
              ],
              document: [
                { sectionCode: "A", questions: [{ displayCode: "A1", answer: this.answers[0], primaryRecordingId: "rec-1", otherRecordingIds: [], accountsDiffer: false, recordedUnderEarlierWording: false }] }
              ],
              retired: []
            }
          ]
        }
      };
    }
    return null;
  }

  async install(page: Page): Promise<void> {
    await page.addInitScript(() => {
      window.localStorage.setItem("field_repo_token", "e2e-questionnaire-fixture");
      window.localStorage.setItem("field_repo_preferences", JSON.stringify({ theme: "light", reducedMotion: true, largerText: false, highContrast: false }));
    });
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
        if (out.status === 204) return route.fulfill({ status: 204, body: "" });
        return route.fulfill({ status: out.status, contentType: "application/json", headers: { date: new Date().toUTCString() }, body: JSON.stringify(out.json ?? null) });
      }
    );
  }
}
