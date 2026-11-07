import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  attemptKey,
  hasAttempted,
  isOnline,
  isPresignedUrl,
  MEDIA_REFRESH_BUDGET,
  presignedExpiryAt,
  presignedUrlExpired,
  refreshesSpent,
  refreshVerdict,
  resetMediaRefreshLedger,
  resolveFailedMediaUrl,
  type MediaFailure
} from "@/lib/mediaUrlRefresh";

const HOOK_SOURCE = readFileSync(
  join(__dirname, "..", "components", "hooks", "useMediaUrl.ts"),
  "utf8"
);

const MEDIA_ID = "cm-pit-loom-photo";
function signed(stamp: string, expiresIn: number): string {
  return (
    "https://bucket.s3.dualstack.ap-south-1.amazonaws.com/media/u1/pit-loom.jpg" +
    "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
    `&X-Amz-Date=${stamp}` +
    `&X-Amz-Expires=${expiresIn}` +
    "&X-Amz-SignedHeaders=host" +
    "&X-Amz-Signature=8f2c0b2ab1de"
  );
}

const NOON = Date.UTC(2026, 8, 3, 10, 0, 0);
const PERMANENT = "https://cdn.example.test/media/u1/pit-loom.jpg";

const never = () => false;
const always = () => true;
const unspent = () => 0;
const overspent = () => MEDIA_REFRESH_BUDGET;

test.beforeEach(() => {
  resetMediaRefreshLedger();
});

test("a stored CDN url is not a signature and never reads as expired", () => {
  expect(isPresignedUrl(PERMANENT)).toBe(false);
  expect(presignedExpiryAt(PERMANENT)).toBeNull();
  expect(presignedUrlExpired(PERMANENT, NOON)).toBe(false);
});

test("an absent url is inert everywhere", () => {
  expect(isPresignedUrl(null)).toBe(false);
  expect(isPresignedUrl(undefined)).toBe(false);
  expect(presignedExpiryAt("")).toBeNull();
  expect(presignedUrlExpired(null, NOON)).toBe(false);
});

test("the basic-format X-Amz-Date is parsed, because Date cannot parse it", () => {
  expect(presignedExpiryAt(signed("20260903T095500Z", 900))).toBe(
    Date.UTC(2026, 8, 3, 9, 55, 0) + 900_000
  );
});

test("a signature minted a minute ago is alive; one minted an hour ago is not", () => {
  expect(presignedUrlExpired(signed("20260903T095900Z", 900), NOON)).toBe(false);
  expect(presignedUrlExpired(signed("20260903T090000Z", 900), NOON)).toBe(true);
});

test("a signature about to die inside the round trip is already dead", () => {
  expect(presignedUrlExpired(signed("20260903T094500Z", 905), NOON)).toBe(true);
  expect(presignedUrlExpired(signed("20260903T094500Z", 1200), NOON)).toBe(false);
});

test("a url that does not say when it dies is not guessed at", () => {
  const noDate =
    "https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Expires=900";
  const noExpiry =
    "https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Date=20260903T095500Z";
  const badStamp = signed("2026-09-03T09:55:00Z", 900);

  expect(presignedExpiryAt(noDate)).toBeNull();
  expect(presignedExpiryAt(noExpiry)).toBeNull();
  expect(presignedExpiryAt(badStamp)).toBeNull();
  expect(presignedUrlExpired(badStamp, NOON)).toBe(false);
});

const FORBIDDEN: MediaFailure = { kind: "status", status: 403 };

test("a 403 on a row we can name is refreshed", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("refresh");
});

test("THE INNER LOOP GUARD: the same media and the same url are refreshed exactly once", () => {
  const url = signed("20260903T090000Z", 900);
  const input = { mediaId: MEDIA_ID, url, failure: FORBIDDEN, online: true, spent: unspent };

  expect(refreshVerdict({ ...input, attempted: never })).toBe("refresh");
  expect(refreshVerdict({ ...input, attempted: always })).toBe("already-tried");
});

test("the inner ledger keys on the URL, so a REFRESHED url may fail on its own terms", () => {
  const first = attemptKey(MEDIA_ID, signed("20260903T090000Z", 900));
  const second = attemptKey(MEDIA_ID, signed("20260903T100000Z", 900));
  expect(first).not.toBe(second);
});

test("THE TERMINATOR: a row out of budget is refused however new the url is", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T101500Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: overspent
    })
  ).toBe("budget-spent");
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T101500Z", 900),
      failure: { kind: "unreadable" },
      online: true,
      attempted: never,
      spent: overspent
    })
  ).toBe("budget-spent");
});

test("the budget is a floor, not a fence: the last unit is still spendable", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: () => MEDIA_REFRESH_BUDGET - 1
    })
  ).toBe("refresh");
});

test("offline gives up rather than queueing, and says so", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: false,
      attempted: never,
      spent: unspent
    })
  ).toBe("offline");
});

test("offline is decided BEFORE either ledger, so a failed offline read costs no attempt", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: PERMANENT,
      failure: FORBIDDEN,
      online: false,
      attempted: always,
      spent: overspent
    })
  ).toBe("offline");
});

test("a local preview with no persisted id has nothing to re-request", () => {
  expect(
    refreshVerdict({
      mediaId: null,
      url: "blob:x",
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("no-record");
});

test("a 404 or a 5xx is left broken and honest", () => {
  for (const status of [404, 410, 500, 503]) {
    expect(
      refreshVerdict({
        mediaId: MEDIA_ID,
        url: PERMANENT,
        failure: { kind: "status", status },
        online: true,
        attempted: never,
        spent: unspent
      }),
      String(status)
    ).toBe("gone");
  }
});

test("a failure we could not read is refreshed once — the CORS case", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: PERMANENT,
      failure: { kind: "unreadable" },
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("refresh");
});

test("an unknowable network reads as online, never as offline", () => {
  expect(isOnline()).toBe(true);
});

type Call = { url: string; range: string | null };

function stubNetwork(freshUrl: string | null, objectStatus = 403) {
  const calls: Call[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ url, range: headers.get("Range") });
    if (url.includes("/api/media/")) {
      return new Response(JSON.stringify({ id: MEDIA_ID, url: freshUrl }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("", { status: objectStatus });
  }) as typeof globalThis.fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

function stubMintingNetwork() {
  let minted = 0;
  const calls: Call[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ url, range: headers.get("Range") });
    if (url.includes("/api/media/")) {
      minted += 1;
      const fresh = signed(`20260903T10${String(minted).padStart(2, "0")}00Z`, 900);
      return new Response(JSON.stringify({ id: MEDIA_ID, url: fresh }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("", { status: 403 });
  }) as typeof globalThis.fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

test("a 403 becomes exactly one row re-read, and the fresh url is handed back", async () => {
  const stale = signed("20260903T090000Z", 900);
  const fresh = signed("20260903T100000Z", 900);
  const net = stubNetwork(fresh);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBe(fresh);

    expect(net.calls[0].url).toBe(stale);
    expect(net.calls[0].range).toBe("bytes=0-0");
    expect(net.calls[1].url).toContain(`/api/media/${MEDIA_ID}`);
    expect(net.calls).toHaveLength(2);
  } finally {
    net.restore();
  }
});

test("THE SECOND FAILURE OF THE SAME URL COSTS NOTHING", async () => {
  const stale = signed("20260903T090000Z", 900);
  const net = stubNetwork(signed("20260903T100000Z", 900));
  try {
    await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale });
    const afterFirst = net.calls.length;

    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBeNull();
    expect(net.calls).toHaveLength(afterFirst);
    expect(hasAttempted(attemptKey(MEDIA_ID, stale))).toBe(true);
  } finally {
    net.restore();
  }
});

test("A GENUINELY UNRENDERABLE FILE STOPS AT THE BUDGET, however many fresh urls it is handed", async () => {
  const net = stubMintingNetwork();
  try {
    let url: string | null = signed("20260903T090000Z", 900);
    const handed: string[] = [];
    for (let round = 0; round < 10 && url; round += 1) {
      const next: string | null = await resolveFailedMediaUrl({ id: MEDIA_ID, url });
      if (!next) break;
      handed.push(next);
      url = next;
    }

    expect(handed).toHaveLength(MEDIA_REFRESH_BUDGET);
    expect(new Set(handed).size).toBe(handed.length);
    expect(refreshesSpent(MEDIA_ID)).toBe(MEDIA_REFRESH_BUDGET);
    expect(net.calls.filter((call) => call.url.includes("/api/media/"))).toHaveLength(MEDIA_REFRESH_BUDGET);
    const spentUrl = handed[handed.length - 1];
    const before = net.calls.length;
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: spentUrl })).toBeNull();
    expect(net.calls).toHaveLength(before);
  } finally {
    net.restore();
  }
});

test("one file's budget is its own — a gallery is not rationed against itself", async () => {
  const net = stubMintingNetwork();
  try {
    for (const id of ["photo-a", "photo-b", "photo-c"]) {
      expect(await resolveFailedMediaUrl({ id, url: signed("20260903T090000Z", 900) })).toBeTruthy();
      expect(refreshesSpent(id)).toBe(1);
    }
  } finally {
    net.restore();
  }
});

test("a server that hands back the SAME url is answered with null, not with a re-arm", async () => {
  const stale = signed("20260903T090000Z", 900);
  const net = stubNetwork(stale);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBeNull();
  } finally {
    net.restore();
  }
});

test("a row whose url the server now withholds resolves to null, and clears nothing", async () => {
  const net = stubNetwork(null);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: PERMANENT })).toBeNull();
  } finally {
    net.restore();
  }
});

test("a 404 on the object never reaches the API at all", async () => {
  const net = stubNetwork(signed("20260903T100000Z", 900), 404);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: PERMANENT })).toBeNull();
    expect(net.calls).toHaveLength(1);
    expect(net.calls[0].url).toBe(PERMANENT);
  } finally {
    net.restore();
  }
});

test("a row with no url is not probed", async () => {
  const net = stubNetwork(signed("20260903T100000Z", 900));
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: null })).toBeNull();
    expect(net.calls).toHaveLength(0);
  } finally {
    net.restore();
  }
});

test("the hook holds no ref guard, which is the deadlock shape reactStrictMode punishes", () => {
  expect(HOOK_SOURCE).not.toContain("useRef");
  expect(HOOK_SOURCE).toContain("let cancelled = false;");
});

test("the pre-emptive arm checks the budget itself, because it never asks for a verdict", () => {
  expect(HOOK_SOURCE).toContain("refreshesSpent(mediaId) >= MEDIA_REFRESH_BUDGET");
});

test("the hook drops its override when the row's own url changes", () => {
  expect(HOOK_SOURCE).toContain("setFresh(null);");
  expect(HOOK_SOURCE).toContain("}, [stored, mediaId]);");
});

test("the hook never clears a url it could not replace", () => {
  const writes = HOOK_SOURCE.match(/setFresh\(/g) ?? [];
  expect(writes.length).toBe(3);
  expect(HOOK_SOURCE).toContain("if (!cancelled && next && next !== stored) setFresh(next);");
  expect(HOOK_SOURCE).toContain("if (next) setFresh(next);");
});
