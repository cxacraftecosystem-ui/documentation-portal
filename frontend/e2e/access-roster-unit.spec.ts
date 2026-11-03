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

function entry(partial: Partial<AccessRosterEntry>): AccessRosterEntry {
  return {
    id: "r1",
    email: "person@example.org",
    status: "PENDING",
    grantedRole: "CROWDSOURCE_VOLUNTEER",
    requestCount: 1,
    ...partial
  } as AccessRosterEntry;
}

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
    expect(accessStatusLabel("ARCHIVED")).toBe("ARCHIVED");
  });

  test("waiting is not painted as a refusal", () => {
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
    expect(invitationLabel(entry({ status: "SUSPENDED", firstSeenAt: "2026-03-04T10:00:00Z" }))).toBe("Signed in");
  });
});

test.describe("how the person got here", () => {
  test("a row nobody asked for is not described as a request", () => {
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
    const error = new ApiError(401, "Invalid email or password", { detail: "Invalid email or password" });
    expect(accessRefusalCode(error)).toBeNull();
    expect(isAccessPending(error)).toBe(false);
  });

  test("a rejected person is not told they are waiting", () => {
    const rejected = refusal("ACCESS_REJECTED", "This address is not approved for access to the repository.");
    expect(accessRefusalCode(rejected)).toBe("ACCESS_REJECTED");
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
    const other = new ApiError(409, "That Aadhaar number is already recorded", {
      detail: { code: "ARTISAN_IDENTITY_CONFLICT", message: "That Aadhaar number is already recorded" }
    });
    expect(accessRefusalCode(other)).toBeNull();
  });
});
