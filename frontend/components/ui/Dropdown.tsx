"use client";

/**
 * The three dropdown shapes this app calls for, all now one implementation.
 *
 * `Dropdown`, `MultiSelectDropdown` and `ComboBox` grew separately and drifted: only the ComboBox
 * could be typed into, only the multi-select had a Confirm button, and all three rendered their
 * menu with `position: absolute` inside the field — which meant every one of them was sheared off
 * by the nearest `overflow-hidden` dialog or filter panel, and none of them could flip upward when
 * opened on the last row of a long form.
 *
 * They are now thin adapters over `components/ui/SearchableSelect`, which floats its panel through
 * `AnchoredPopover` and grows a search box once a list is long enough to need one. The three
 * signatures are unchanged on purpose: forty-odd call sites — several of them owned by other work
 * in flight — pick up searching, "select all" and a panel that clips nothing without being edited
 * at all.
 */

import {
  SearchableMultiSelect,
  SearchableSelect,
  type SelectOption
} from "@/components/ui/SearchableSelect";

export type DropdownOption = SelectOption;

/** Themed single-select dropdown — the app's replacement for the plain browser <select>. */
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
  /** Override the option-count rule in SearchableSelect. Almost nothing should need to. */
  searchable?: boolean;
  /**
   * After a value is picked, close and move focus to the next field. On by default: these forms are
   * filled top-to-bottom in one pass, and leaving focus parked on the trigger makes the next Tab
   * land somewhere the researcher did not expect. Set false for a dropdown that FILTERS the screen
   * it sits on (a list funnel, a taxonomy switcher) rather than filling in a form field — there,
   * jumping focus away from the control you are adjusting is wrong.
   */
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

/** Themed multi-select dropdown with checkboxes (e.g. pick several crafts / artisans at once). */
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
  confirmLabel = "Confirm"
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
  /**
   * Show a Confirm button in the panel once at least one option is ticked; confirming closes the
   * panel and moves to the next field.
   *
   * A multi-select cannot advance on click the way a single-select does — picking one option is
   * usually not the end of the answer — so without an explicit "done" the researcher has to know to
   * click away, and the form gives no signal that the answer was registered. Set false where the
   * control filters a list in place rather than answering a form field.
   */
  confirmOnSelect?: boolean;
  confirmLabel?: string;
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
    />
  );
}

/**
 * Text-input-filtered dropdown: type to narrow the options, pick with mouse or arrows+Enter.
 * When `name` is set a hidden input submits the selected VALUE with the surrounding form.
 *
 * Kept as a distinct export because its callers mean "this list is meant to be searched" regardless
 * of how few records exist today — the workshop picker is one row on this deployment and will be
 * forty on the next — so it forces the search box on rather than letting the count decide.
 */
export function ComboBox({
  options,
  value,
  onChange,
  placeholder = "Select or type to search",
  name,
  ariaLabel,
  className,
  disabled,
  advanceOnSelect = true
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
}) {
  return (
    <>
      {name ? <input type="hidden" name={name} value={value} /> : null}
      <SearchableSelect
        value={value}
        onChange={onChange}
        options={options}
        placeholder={placeholder}
        disabled={disabled}
        className={className}
        ariaLabel={ariaLabel}
        searchable
        advanceOnSelect={advanceOnSelect}
      />
    </>
  );
}
