"use client";

import { useMemo, useState } from "react";

import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import type { CraftCatalogue } from "@/lib/craftCatalogue";

/** Which category a sub-category sits in, or "" when the id is unknown. */
export function categoryOfSubCategory(catalogue: CraftCatalogue, subCategoryId: string): string {
  for (const category of catalogue.categories) {
    if (category.subCategories.some((sub) => sub.id === subCategoryId)) return category.id;
  }
  return "";
}

/**
 * Category → Sub-category, two dropdowns (REQUIREMENTS_2026-10-20 §2.4, `CraftForm`). The value is
 * the sub-category id; the category shown is the one that holds it, or the one just chosen while no
 * sub-category is picked yet. With `name`, the sub-category id is submitted through FormData.
 */
export function CraftSubCategorySelect({
  catalogue,
  value,
  onChange,
  name,
  required = false,
  disabled = false
}: {
  catalogue: CraftCatalogue;
  value: string;
  onChange: (subCategoryId: string) => void;
  name?: string;
  required?: boolean;
  disabled?: boolean;
}) {
  const categories = useMemo(() => [...catalogue.categories].sort((a, b) => a.sortOrder - b.sortOrder), [catalogue]);
  const [picked, setPicked] = useState("");
  const categoryId = (value && categoryOfSubCategory(catalogue, value)) || picked;
  const subs = useMemo(() => {
    const category = categories.find((row) => row.id === categoryId);
    return category ? [...category.subCategories].sort((a, b) => a.sortOrder - b.sortOrder) : [];
  }, [categories, categoryId]);

  return (
    <>
      <FieldBlock label="Category" required={required}>
        <Dropdown
          ariaLabel="Category"
          value={categoryId}
          disabled={disabled}
          placeholder="Choose a category"
          advanceOnSelect={false}
          onChange={(next) => {
            setPicked(next);
            if (next !== categoryId) onChange("");
          }}
          options={categories.map((category) => ({ value: category.id, label: category.name }))}
        />
      </FieldBlock>
      <FieldBlock label="Sub-category" required={required}>
        <Dropdown
          ariaLabel="Sub-category"
          value={value}
          disabled={disabled || !categoryId}
          placeholder={categoryId ? "Choose a sub-category" : "Choose a category first"}
          onChange={onChange}
          options={subs.map((sub) => ({ value: sub.id, label: sub.name }))}
        />
        {name ? (
          // Not type="hidden": a hidden input is exempt from constraint validation (see FormControls).
          <input
            type="text"
            name={name}
            value={value}
            required={required}
            onChange={() => undefined}
            tabIndex={-1}
            aria-hidden="true"
            className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
          />
        ) : null}
      </FieldBlock>
    </>
  );
}
