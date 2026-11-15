import { expect, test, type Route } from "@playwright/test";

import { ListBackNavFake } from "./support/listBackNavFake";

const ARTISAN = {
  id: "artisans-7",
  createdById: "u-master",
  createdBy: { id: "u-master", name: "Master Admin", role: "MASTER_ADMIN" },
  editAccess: "FILL_EMPTY",
  name: "Ramesh Kumar",
  localName: null,
  gender: "Male",
  dateOfBirth: "1980-04-02T00:00:00+00:00",
  craftStartDate: "2000-01-01T00:00:00+00:00",
  experienceYears: 24,
  experienceMonths: null,
  phone: "9876543210",
  phoneCountryCode: "+91",
  email: null,
  place: "Bagru",
  address: null,
  notes: "Carves temple pillars.",
  aadhaarNumber: "XXXX XXXX 1234",
  pehchanCardAvailable: false,
  pehchanCardNumber: null,
  dos: "Wet the stone first.",
  donts: "Never dry it in the sun.",
  craftId: "c-stone",
  craft: { id: "c-stone", name: "Stone Carving" },
  workshopId: null,
  designWorkshopId: null,
  status: "APPROVED",
  recordedTimezone: "Asia/Kolkata",
  extraMetadata: {},
  location: null,
  media: [],
  completeness: null,
  createdAt: "2026-11-07T05:00:00Z",
  updatedAt: "2026-11-07T05:00:00Z"
};

const CATALOGUE = {
  version: "1",
  categories: [],
  crafts: [
    {
      id: "c-stone",
      slug: "stone-carving",
      name: "Stone Carving",
      localName: null,
      categoryId: null,
      subCategoryId: null,
      sourceSerial: null,
      stateOfOrigin: [],
      stateOfPractice: [],
      giStatus: null,
      isCatchAll: false,
      craftCode: null
    }
  ]
};

const REFUSAL = "Only the person who recorded this, an admin, or someone with Edit access to its workshop can change this filled-in field: notes.";

const JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=",
  "base64"
);

test("a refused save keeps the photo, says why in the form, and sends only what changed", async ({ page }) => {
  const fake = new ListBackNavFake();
  fake.questionnaire.role = "RESEARCHER";
  await fake.install(page);

  const patches: Record<string, unknown>[] = [];
  const deletes: string[] = [];

  await page.route(
    (url) => url.pathname === "/api/artisans/artisans-7",
    async (route: Route) => {
      const request = route.request();
      if (request.method() === "GET") return route.fulfill({ json: ARTISAN });
      if (request.method() === "PATCH") {
        patches.push(request.postDataJSON() as Record<string, unknown>);
        return route.fulfill({ status: 403, json: { detail: REFUSAL } });
      }
      return route.fallback();
    }
  );
  await page.route(
    (url) => url.pathname === "/api/crafts/catalogue",
    (route: Route) => route.fulfill({ json: CATALOGUE })
  );
  await page.route(
    (url) => url.pathname === "/api/media/presign",
    (route: Route) =>
      route.fulfill({
        json: {
          objectKey: "staged-pillar.jpg",
          bucket: "media",
          uploadUrl: new URL("/fake-store/staged-pillar.jpg", route.request().url()).toString(),
          headers: {},
          publicUrl: null
        }
      })
  );
  await page.route(
    (url) => url.pathname.startsWith("/fake-store/"),
    (route: Route) => route.fulfill({ status: 200, body: "" })
  );
  await page.route(
    (url) => url.pathname === "/api/media/object",
    (route: Route) => {
      deletes.push(route.request().url());
      return route.fulfill({ status: 204, body: "" });
    }
  );

  await page.goto("/artisans/artisans-7/edit");
  await expect(page.getByText("You can fill empty fields and add media.")).toBeVisible();

  await page
    .locator('input[type="file"]')
    .first()
    .setInputFiles({ name: "pillar.jpg", mimeType: "image/jpeg", buffer: JPEG });
  await expect(page.getByText("pillar.jpg").first()).toBeVisible();

  await page.getByRole("button", { name: "Update artisan" }).click();

  const refusal = page.getByTestId("save-refusal");
  await expect(refusal).toBeVisible();
  await expect(refusal).toContainText("notes");
  await expect(refusal).toContainText("Nothing was lost");
  await expect(page.getByText("pillar.jpg").first()).toBeVisible();
  expect(deletes).toEqual([]);

  expect(patches).toHaveLength(1);
  const sent = Object.keys(patches[0]);
  for (const untouched of ["name", "place", "gender", "dateOfBirth", "craftStartDate", "phone", "notes", "status", "aadhaarNumber", "dos", "donts", "craftId"]) {
    expect(sent).not.toContain(untouched);
  }
  expect(patches[0].formContract).toBe(2);

  await page.getByRole("button", { name: "Update artisan" }).click();
  await expect.poll(() => patches.length).toBe(2);
  await expect(page.getByText("pillar.jpg").first()).toBeVisible();
  expect(deletes).toEqual([]);
});
