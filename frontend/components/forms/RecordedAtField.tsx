"use client";

import { CalendarLume } from "@/components/ui/calendar-lume";
import { Field, Select } from "@/components/FormControls";

export function RecordedAtField({
  value,
  timezone = "Asia/Kolkata"
}: {
  value?: string | Date | null;
  timezone?: string | null;
}) {
  return (
    <section className="grid gap-3 rounded-md border border-[#e6dfd8] bg-field-100 p-3">
      <div>
        <h3 className="font-serif text-lg text-ink">Record time</h3>
        <p className="mt-1 text-sm text-ink-muted">The timestamp is saved in UTC. IST is selected by default for field interpretation.</p>
      </div>
      <div className="grid gap-3 lg:grid-cols-[1fr_220px]">
        <Field label="Captured at">
          <CalendarLume name="recordedAt" value={value ?? new Date()} />
        </Field>
        <Field label="Field timezone">
          <Select name="recordedTimezone" defaultValue={timezone ?? "Asia/Kolkata"}>
            <option value="Asia/Kolkata">India Standard Time (IST)</option>
            <option value="UTC">UTC</option>
          </Select>
        </Field>
      </div>
    </section>
  );
}
