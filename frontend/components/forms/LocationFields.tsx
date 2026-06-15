import { Field, TextInput } from "@/components/FormControls";

export function LocationFields() {
  return (
    <div className="grid gap-3 border-t border-neutral-200 pt-4 sm:grid-cols-2 lg:grid-cols-4">
      <Field label="Latitude">
        <TextInput name="latitude" type="number" step="any" placeholder="23.2599" />
      </Field>
      <Field label="Longitude">
        <TextInput name="longitude" type="number" step="any" placeholder="77.4126" />
      </Field>
      <Field label="Altitude">
        <TextInput name="altitude" type="number" step="any" />
      </Field>
      <Field label="Accuracy metres">
        <TextInput name="accuracy" type="number" step="any" />
      </Field>
      <Field label="GPS place name">
        <TextInput name="placeName" />
      </Field>
      <Field label="GPS address">
        <TextInput name="locationAddress" className="lg:col-span-3" />
      </Field>
    </div>
  );
}
