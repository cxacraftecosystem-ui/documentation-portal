"use client";

import {
  SearchableMultiSelect,
  SearchableSelect,
  type SelectOption
} from "@/components/ui/SearchableSelect";

export type DropdownOption = SelectOption;

export function Dropdown({
  value,
  onChange,
  options,
  placeholder = "Select",
  disabled,
  className,
  ariaLabel,
  searchable,
  advanceOnSelect = true
}: {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption[];
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  searchable?: boolean;
  advanceOnSelect?: boolean;
}) {
  return (
    <SearchableSelect
      value={value}
      onChange={onChange}
      options={options}
      placeholder={placeholder}
      disabled={disabled}
      className={className}
      ariaLabel={ariaLabel}
      searchable={searchable}
      advanceOnSelect={advanceOnSelect}
    />
  );
}

export function MultiSelectDropdown({
  values,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  ariaLabel,
  searchable,
  confirmOnSelect = true,
  confirmLabel = "Confirm",
  showChips = true
}: {
  values: string[];
  onChange: (values: string[]) => void;
  options: DropdownOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  searchable?: boolean;
  confirmOnSelect?: boolean;
  confirmLabel?: string;
  showChips?: boolean;
}) {
  return (
    <SearchableMultiSelect
      values={values}
      onChange={onChange}
      options={options}
      placeholder={placeholder}
      emptyLabel={emptyLabel}
      disabled={disabled}
      className={className}
      ariaLabel={ariaLabel}
      searchable={searchable}
      confirmOnSelect={confirmOnSelect}
      confirmLabel={confirmLabel}
      showChips={showChips}
    />
  );
}

export function ComboBox({
  options,
  value,
  onChange,
  placeholder = "Select or type to search",
  name,
  ariaLabel,
  className,
  disabled,
  advanceOnSelect = true,
  emptyLabel,
  onSearch
}: {
  options: DropdownOption[];
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  name?: string;
  ariaLabel?: string;
  className?: string;
  disabled?: boolean;
  advanceOnSelect?: boolean;
  emptyLabel?: string;
  onSearch?: (query: string) => void;
}) {
  return (
    <>
      {name ? <input type="hidden" name={name} value={value} /> : null}
      <SearchableSelect
        value={value}
        onChange={onChange}
        options={options}
        placeholder={placeholder}
        emptyLabel={emptyLabel}
        disabled={disabled}
        className={className}
        ariaLabel={ariaLabel}
        searchable
        advanceOnSelect={advanceOnSelect}
        onSearch={onSearch}
      />
    </>
  );
}
