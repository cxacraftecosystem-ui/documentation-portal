import { expect, test } from "@playwright/test";

import {
  accessRefusalCode,
  accessStatusChip,
  accessStatusLabel,
  invitationLabel,
  isAccessPending,
  requestLabel,
  type AccessRosterEntry
} from "@/lib/accessRoster";
import { ApiError } from "@/lib/api";

/**
 * THE SIGN-IN GATE'S TWO REFUSALS, AND THE ROSTER'S VOCABULARY, asserted rather than eyeballed.
 *
 * WHY THIS FILE HAS NO `page`. Reproducing any of it on screen means arranging for somebody to
 * actually be turned away at sign-in — a real address this institution has never admitted, a real
 * 403, a real pending row — and nobody arranges that before a release. Worse, every failure here is
 * silent: a status word that drifts looks like a design choice, and a refusal that renders as an
 * error rather than as a waiting state looks like the person mistyped their password, which is
 * precisely the belief this whole feature exists to prevent. So the decisions live in
 * `lib/accessRoster.ts` where a test can reach them. This repository has no React renderer in its
 * devDependencies, which is why this is a Playwright spec that never opens a browser — the same
 * shape, and the same argument, as `record-pickers-unit.spec.ts` beside it.
 *
 * ANDROID PARITY. Every assertion below has a Kotlin twin in
 * `android/app/src/test/java/com/fieldrepository/app/ui/AccessRosterTest.kt`. Two surfaces
 * answering one question differently is this product family's most repeated defect class; if you
 * change a rule here and the Kotlin test still passes unchanged, you have just created one.
 */

function entry(partial: Partial<AccessRosterEntry>): AccessRosterEntry {
  // Only the fields each rule reads are meaningful; the cast keeps the fixture to the point rather
  // than inventing a plausible-looking whole roster row that nothing asserts on.
  return {
    id: "r1",
    email: "person@example.org",
    status: "PENDING",
    grantedRole: "CROWDSOURCE_VOLUNTEER",
    requestCount: 1,
    ...partial
  } as AccessRosterEntry;
}

/** The body FastAPI sends for a gated refusal, wrapped exactly as `apiFetch` wraps it. */
function refusal(code: string, message: string): ApiError {
  return new ApiError(403, message, { detail: { code, message } });
}

test.describe("the status vocabulary — the contract with Android", () => {
  test("every status has one wording", () => {
    expect(accessStatusLabel("PENDING")).toBe("Awaiting approval");
    expect(accessStatusLabel("ACTIVE")).toBe("May sign in");
    expect(accessStatusLabel("REJECTED")).toBe("Not approved");
    expect(accessStatusLabel("SUSPENDED")).toBe("Suspended");
  });

  test("an unknown status is shown as itself rather than swallowed", () => {
    // The server may grow a fifth state before this bundle is rebuilt. Printing the raw value is
    // ugly and honest; mapping it to "Suspended" or to a blank cell would be neither.
    expect(accessStatusLabel("ARCHIVED")).toBe("ARCHIVED");
  });

  test("waiting is not painted as a refusal", () => {
    // PENDING is amber and ACTIVE is green; REJECTED and SUSPENDED share the error tone. A pending
    // request is not something an admin got wrong, it is work waiting for them.
    expect(accessStatusChip("PENDING")).toContain("amber");
    expect(accessStatusChip("ACTIVE")).toContain("success");
    expect(accessStatusChip("REJECTED")).toContain("error");
    expect(accessStatusChip("SUSPENDED")).toContain("error");
  });
});

test.describe("the invitation — has an admitted address ever been used?", () => {
  test("an admitted address that has never signed in says so without saying never", () => {
    expect(invitationLabel(entry({ status: "ACTIVE", firstSeenAt: null }))).toBe("Not signed in yet");
  });

  test("an address that is not admitted has no invitation outstanding", () => {
    expect(invitationLabel(entry({ status: "PENDING", firstSeenAt: null }))).toBe("No access to take up");
    expect(invitationLabel(entry({ status: "REJECTED", firstSeenAt: null }))).toBe("No access to take up");
  });

  test("a first sign-in outranks the status", () => {
    // A suspended person demonstrably DID take the access up; the row must not claim otherwise
    // just because the access has since been ended.
    expect(invitationLabel(entry({ status: "SUSPENDED", firstSeenAt: "2026-03-04T10:00:00Z" }))).toBe("Signed in");
  });
});

test.describe("how the person got here", () => {
  test("a row nobody asked for is not described as a request", () => {
    // requestCount is 0 for rows an administrator created — including every account grandfathered
    // by the gate's migration. Calling those "asked once" would misattribute the entire existing
    // user base as applicants.
    expect(requestLabel(entry({ requestCount: 0 }))).toBe("Added by an admin");
  });

  test("repeat attempts are counted, because that is how an admin notices somebody stuck", () => {
    expect(requestLabel(entry({ requestCount: 1 }))).toBe("Asked once");
    expect(requestLabel(entry({ requestCount: 7 }))).toBe("Asked 7 times");
  });
});

test.describe("the two refusals — the ruling this feature turns on", () => {
  test("a pending refusal is recognised by its code, not by its prose", () => {
    const error = refusal("ACCESS_PENDING", "Your access request is awaiting approval by an administrator.");
    expect(accessRefusalCode(error)).toBe("ACCESS_PENDING");
    expect(isAccessPending(error)).toBe(true);
  });

  test("a wrong password carries no code, so the sign-in page cannot draw it as waiting", () => {
    // The 401 is UNCHANGED and its detail is a bare string. A null code is what keeps the red error
    // and the amber waiting card apart — collapse this and a person waiting on an administrator is
    // told they mistyped their password, which is the exact failure the ruling forbids.
    const error = new ApiError(401, "Invalid email or password", { detail: "Invalid email or password" });
    expect(accessRefusalCode(error)).toBeNull();
    expect(isAccessPending(error)).toBe(false);
  });

  test("a rejected person is not told they are waiting", () => {
    const rejected = refusal("ACCESS_REJECTED", "This address is not approved for access to the repository.");
    expect(accessRefusalCode(rejected)).toBe("ACCESS_REJECTED");
    // Telling somebody an administrator has already refused that they are "awaiting approval" is a
    // lie that guarantees they wait forever.
    expect(isAccessPending(rejected)).toBe(false);
  });

  test("a suspended person is told that, and not either of the other two", () => {
    const suspended = refusal("ACCESS_SUSPENDED", "Your access to the repository has been suspended.");
    expect(accessRefusalCode(suspended)).toBe("ACCESS_SUSPENDED");
    expect(isAccessPending(suspended)).toBe(false);
  });

  test("a network failure or a 500 never masquerades as a gate decision", () => {
    expect(accessRefusalCode(new Error("Failed to fetch"))).toBeNull();
    expect(accessRefusalCode(new ApiError(500, "Internal Server Error", null))).toBeNull();
    expect(accessRefusalCode(null)).toBeNull();
    expect(accessRefusalCode(undefined)).toBeNull();
  });

  test("a code that is not the gate's is ignored", () => {
    // Other routes send structured details too (the artisan identity 409 carries a message and the
    // clashing record). None of them may switch the sign-in page into its waiting state.
    const other = new ApiError(409, "That Aadhaar number is already recorded", {
      detail: { code: "ARTISAN_IDENTITY_CONFLICT", message: "That Aadhaar number is already recorded" }
    });
    expect(accessRefusalCode(other)).toBeNull();
  });
});
