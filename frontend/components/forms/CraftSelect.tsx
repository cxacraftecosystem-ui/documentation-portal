"use client";

/**
 * `CraftSelect` — the one way to pick a craft on the web (REQUIREMENTS_2026-10-20 §2.4).
 *
 * Byte-identical in designer-portal and documentation-portal; a parity test in each repo pins it.
 *
 * - Options come only from `GET /crafts/catalogue` (`lib/craftCatalogue.ts`). Nothing typed is ever
 *   submitted: the text box searches, a pick submits the craft's id.
 * - Grouped under "Category › Sub-category"; each row shows the name, a GI badge, and the states of
 *   practice in muted text.
 * - Search folds case and accents over name, slug, aliases, states and sub-category, ranked by
 *   `appSearchScore` (`craftSearchScore`).
 * - Keyboard-complete: arrows move, Enter picks, Escape closes. Single and multi modes.
 * - With `name`, a zero-size text mirror submits the id(s) and carries `required` into native
 *   validation (a hidden input is exempt from it).
 */

import { Check, ChevronDown, X } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import {
  CRAFT_ADD_TO_LIST,
  CRAFT_NO_MATCH,
  CRAFT_NOT_IN_LIST,
  craftIndex,
  craftStatesLine,
  groupCrafts,
  searchCraftCatalogue,
  useCraftCatalogue,
  type CatalogueCraft
} from "@/lib/craftCatalogue";
import { cn } from "@/lib/utils";

type CraftSelectCommon = {
  id?: string;
  /** Submitted through FormData: the craft id (single) or one entry per id (multi). */
  name?: string;
  required?: boolean;
  disabled?: boolean;
  placeholder?: string;
  ariaLabel?: string;
  /** Ids of the visible label(s) naming this control. */
  labelledBy?: string;
  describedBy?: string;
  className?: string;
  /** Show "Add it to the list" under a search with no match (`canManageCrafts`). */
  canAddCraft?: boolean;
  /** Runs before "Add it to the list" leaves for `/crafts?new=…` — save the form draft here. */
  onBeforeAddCraft?: (query: string) => void;
  /** A themed control fires no native input event; forms mark themselves dirty from this. */
  onDirty?: () => void;
  /** Names for ids not on the list (a LEGACY craft an old record still points at). */
  fallbackLabels?: Record<string, string>;
  /** Offer only these crafts (a filter narrowed by a workshop's own crafts). Absent = every craft. */
  allowedIds?: readonly string[];
};

export type CraftSelectSingleProps = CraftSelectCommon & {
  multiple?: false;
  value: string;
  onChange: (id: string, craft: CatalogueCraft | null) => void;
};

export type CraftSelectMultiProps = CraftSelectCommon & {
  multiple: true;
  value: string[];
  onChange: (ids: string[], crafts: CatalogueCraft[]) => void;
};

export type CraftSelectProps = CraftSelectSingleProps | CraftSelectMultiProps;

export function CraftSelect(props: CraftSelectProps) {
  const {
    id,
    name,
    required = false,
    disabled = false,
    ariaLabel,
    labelledBy,
    describedBy,
    className,
    canAddCraft = false,
    onBeforeAddCraft,
    onDirty,
    fallbackLabels,
    allowedIds
  } = props;
  const multiple = props.multiple === true;
  const selectedIds = useMemo(
    () => (props.multiple === true ? props.value : props.value ? [props.value] : []),
    [props.multiple, props.value]
  );
  const placeholder = props.placeholder ?? (multiple ? "Search crafts" : "Search and pick a craft");

  const { catalogue, error, loading, reload } = useCraftCatalogue();
  const autoId = useId();
  const inputId = id ?? `craft-select-${autoId}`;
  const listboxId = `${inputId}-listbox`;
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const mirrorRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);

  const index = catalogue ? craftIndex(catalogue) : null;
  const matches = useMemo(() => {
    if (!catalogue) return [];
    const found = searchCraftCatalogue(catalogue, query);
    if (!allowedIds) return found;
    const allowed = new Set(allowedIds);
    return found.filter((craft) => allowed.has(craft.id));
  }, [catalogue, query, allowedIds]);
  const groups = useMemo(() => (catalogue ? groupCrafts(catalogue, matches) : []), [catalogue, matches]);
  // The flat order the arrows walk: the order the groups draw. `start` is each group's first index.
  const flat = useMemo(() => groups.flatMap((group) => group.crafts), [groups]);
  const groupStarts = useMemo(() => {
    const starts: number[] = [];
    let next = 0;
    for (const group of groups) {
      starts.push(next);
      next += group.crafts.length;
    }
    return starts;
  }, [groups]);
  const active = flat.length ? Math.min(highlight, flat.length - 1) : -1;
  const optionId = (craftId: string) => `${inputId}-option-${craftId}`;

  const labelFor = (craftId: string) => index?.byId.get(craftId)?.name ?? fallbackLabels?.[craftId] ?? "Craft not on the list";
  const singleLabel = !multiple && selectedIds[0] ? labelFor(selectedIds[0]) : "";

  // Native validation speaks the contract's sentence, not the browser's "fill in this field".
  useEffect(() => {
    mirrorRef.current?.setCustomValidity(required && selectedIds.length === 0 ? CRAFT_NOT_IN_LIST : "");
  }, [required, selectedIds.length]);

  // Keep the highlighted row in view as the arrows move it.
  useEffect(() => {
    if (!open || active < 0) return;
    const node = listRef.current?.querySelector<HTMLElement>(`[data-craft-index="${active}"]`);
    node?.scrollIntoView({ block: "nearest" });
  }, [open, active]);

  const close = () => {
    setOpen(false);
    setQuery("");
  };

  const openList = () => {
    if (disabled) return;
    if (!open) {
      setOpen(true);
      setHighlight(0);
    }
  };

  const pick = (craft: CatalogueCraft) => {
    onDirty?.();
    if (props.multiple === true) {
      const next = selectedIds.includes(craft.id) ? selectedIds.filter((x) => x !== craft.id) : [...selectedIds, craft.id];
      props.onChange(next, next.map((x) => index?.byId.get(x)).filter((c): c is CatalogueCraft => Boolean(c)));
      setQuery("");
      inputRef.current?.focus();
    } else {
      props.onChange(craft.id, craft);
      close();
      inputRef.current?.focus();
    }
  };

  const remove = (craftId: string) => {
    onDirty?.();
    if (props.multiple === true) {
      const next = selectedIds.filter((x) => x !== craftId);
      props.onChange(next, next.map((x) => index?.byId.get(x)).filter((c): c is CatalogueCraft => Boolean(c)));
    } else {
      props.onChange("", null);
    }
    inputRef.current?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      if (!open) {
        openList();
        return;
      }
      if (!flat.length) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setHighlight((current) => (Math.min(current, flat.length - 1) + step + flat.length) % flat.length);
      return;
    }
    if (event.key === "Home" && open && !query) {
      event.preventDefault();
      setHighlight(0);
      return;
    }
    if (event.key === "End" && open && !query) {
      event.preventDefault();
      setHighlight(Math.max(0, flat.length - 1));
      return;
    }
    if (event.key === "Enter") {
      if (!open) return;
      // Enter belongs to the list while it is open: it must never submit the form or walk focus.
      event.preventDefault();
      event.stopPropagation();
      if (active >= 0) pick(flat[active]);
      return;
    }
    if (event.key === "Escape" && open) {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key === "Backspace" && multiple && !query && selectedIds.length) {
      remove(selectedIds[selectedIds.length - 1]);
      return;
    }
    if (event.key === "Tab" && open) close();
  };

  const addHref = `/crafts?new=${encodeURIComponent(query.trim())}`;

  return (
    <div
      className={cn("relative min-w-0", className)}
      // Typing to search is not an edit: keep the form's dirty tracking and Enter-walker out of it.
      onInput={(event) => event.stopPropagation()}
    >
      <div
        ref={anchorRef}
        className={cn(
          "field-input flex min-h-10 flex-wrap items-center gap-1.5 py-1.5! pr-9!",
          disabled && "cursor-not-allowed opacity-60"
        )}
        onMouseDown={(event) => {
          if (event.target === inputRef.current) return;
          event.preventDefault();
          inputRef.current?.focus();
          openList();
        }}
      >
        {multiple
          ? selectedIds.map((craftId) => (
              <span
                key={craftId}
                className="inline-flex max-w-full items-center gap-1 rounded-full bg-purple-50 py-0.5 pl-2.5 pr-1 text-xs font-medium text-purple-800 dark:bg-purple-950 dark:text-purple-200"
              >
                <span className="truncate">{labelFor(craftId)}</span>
                {disabled ? null : (
                  <button
                    type="button"
                    aria-label={`Remove ${labelFor(craftId)}`}
                    onMouseDown={(event) => event.stopPropagation()}
                    onClick={() => remove(craftId)}
                    className="relative grid h-6 w-6 shrink-0 place-items-center rounded-full transition hover:bg-purple-100 dark:hover:bg-purple-900 pointer-coarse:before:absolute pointer-coarse:before:-inset-2 pointer-coarse:before:content-['']"
                  >
                    <X className="h-3.5 w-3.5" aria-hidden />
                  </button>
                )}
              </span>
            ))
          : null}
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={open && active >= 0 ? optionId(flat[active].id) : undefined}
          aria-label={labelledBy ? undefined : (ariaLabel ?? (multiple ? "Crafts" : "Craft"))}
          aria-labelledby={labelledBy}
          aria-describedby={describedBy}
          autoComplete="off"
          spellCheck={false}
          disabled={disabled}
          placeholder={multiple && selectedIds.length ? "" : singleLabel || placeholder}
          value={open ? query : singleLabel}
          onChange={(event) => {
            setQuery(event.target.value);
            setHighlight(0);
            if (!open) setOpen(true);
          }}
          onClick={openList}
          onKeyDown={onKeyDown}
          // A form that walks focus on Enter reads this attribute and skips the field.
          data-craft-select=""
          className="min-w-[8rem] flex-1 border-0 bg-transparent p-0 text-sm text-ink-900 outline-hidden placeholder:text-ink-300"
        />
        <ChevronDown
          className={cn("pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </div>
      {!multiple && selectedIds[0] && !disabled && !required ? (
        <button
          type="button"
          aria-label="Clear craft"
          onClick={() => remove(selectedIds[0])}
          className="absolute right-8 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-full text-ink-500 transition hover:bg-surface-50 hover:text-ink-900 pointer-coarse:h-11 pointer-coarse:w-11"
        >
          <X className="h-3.5 w-3.5" aria-hidden />
        </button>
      ) : null}

      {name ? (
        multiple ? (
          <>
            {selectedIds.map((craftId) => (
              <input key={craftId} type="hidden" name={name} value={craftId} />
            ))}
            <input
              ref={mirrorRef}
              type="text"
              value={selectedIds.join(",")}
              required={required}
              onChange={() => undefined}
              tabIndex={-1}
              aria-hidden="true"
              className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
            />
          </>
        ) : (
          <input
            ref={mirrorRef}
            type="text"
            name={name}
            value={selectedIds[0] ?? ""}
            required={required}
            onChange={() => undefined}
            tabIndex={-1}
            aria-hidden="true"
            className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
          />
        )
      ) : null}

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        label={multiple ? "Crafts" : "Craft"}
        matchAnchorWidth
        maxWidth={560}
        className="overflow-hidden! p-0!"
      >
        <div ref={listRef} id={listboxId} role="listbox" aria-multiselectable={multiple || undefined} className="max-h-80 overflow-y-auto py-1">
          {!catalogue && loading ? <p className="px-3 py-2 text-sm text-ink-500">Loading crafts…</p> : null}
          {!catalogue && !loading && error ? (
            <div className="flex items-center justify-between gap-2 px-3 py-2 text-sm text-ink-700">
              <span>{error}</span>
              <button type="button" className="field-button-secondary min-h-8! px-3! py-1!" onMouseDown={(event) => event.preventDefault()} onClick={reload}>
                Try again
              </button>
            </div>
          ) : null}
          {catalogue && !flat.length ? (
            <div className="px-3 py-2 text-sm text-ink-700">
              <p>{query.trim() ? CRAFT_NO_MATCH(query.trim()) : "No crafts on the list yet."}</p>
              {canAddCraft && query.trim() ? (
                <a
                  href={addHref}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => onBeforeAddCraft?.(query.trim())}
                  className="mt-1 inline-flex font-medium text-purple-700 underline-offset-2 hover:underline dark:text-purple-300"
                >
                  {CRAFT_ADD_TO_LIST}
                </a>
              ) : null}
            </div>
          ) : null}
          {groups.map((group, groupAt) => (
              <div key={group.key} role="group" aria-labelledby={`${inputId}-group-${group.key}`}>
                <p
                  id={`${inputId}-group-${group.key}`}
                  role="presentation"
                  className="sticky top-0 z-10 bg-card/95 px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wide text-ink-500 backdrop-blur-sm"
                >
                  {group.heading}
                </p>
                {group.crafts.map((craft, inGroup) => {
                  const at = groupStarts[groupAt] + inGroup;
                  const selected = selectedIds.includes(craft.id);
                  const states = craftStatesLine(craft);
                  return (
                    <div
                      key={craft.id}
                      id={optionId(craft.id)}
                      role="option"
                      aria-selected={selected}
                      data-craft-index={at}
                      onMouseDown={(event) => event.preventDefault()}
                      onMouseMove={() => setHighlight(at)}
                      onClick={() => pick(craft)}
                      className={cn(
                        "flex cursor-pointer items-start gap-2 px-3 py-2 text-sm pointer-coarse:py-3",
                        at === active ? "bg-purple-50 dark:bg-purple-950" : ""
                      )}
                    >
                      <span className="mt-0.5 h-4 w-4 shrink-0">
                        {selected ? <Check className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden /> : null}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-1.5">
                          <span className="font-medium text-ink-900">{craft.name}</span>
                          {craft.giStatus === "GI" ? (
                            <span className="rounded-full border border-purple-300 px-1.5 text-[10px] font-semibold leading-4 text-purple-700 dark:text-purple-300">
                              GI
                            </span>
                          ) : null}
                        </span>
                        {states ? <span className="block truncate text-xs text-ink-500">{states}</span> : null}
                      </span>
                    </div>
                  );
                })}
              </div>
          ))}
        </div>
      </AnchoredPopover>
    </div>
  );
}
