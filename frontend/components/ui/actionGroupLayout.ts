export type ActionGroupAlign = "start" | "center" | "end";

export type ActionGroupShape = "one" | "two" | "many";

export function actionGroupShape(count: number): ActionGroupShape {
  if (count <= 1) return "one";
  if (count === 2) return "two";
  return "many";
}

const ONE = "flex items-center justify-center";
const ONE_FILL = "flex items-center justify-center max-sm:[&>*]:w-full";

const TWO_BASE =
  "flex min-w-0 max-w-full flex-nowrap items-center gap-2 [&>*]:min-w-0 [&>*]:whitespace-normal max-sm:[&>*]:min-w-0 max-sm:[&>*]:flex-1 max-sm:[&>*]:text-center";

const MANY_BASE =
  "flex flex-col gap-2 max-sm:[&>*]:w-full sm:flex-row sm:flex-wrap sm:items-center sm:[&>*]:rounded-full! [&>[data-danger]]:order-last max-sm:[&>[data-danger]]:mt-2 sm:[&>[data-danger]]:ms-4";

const JUSTIFY_TWO: Record<ActionGroupAlign, string> = {
  start: "justify-start",
  center: "justify-center",
  end: "justify-end"
};

const ONE_INLINE: Record<ActionGroupAlign, string> = {
  start: "flex items-center justify-start",
  center: "flex items-center justify-center",
  end: "flex items-center justify-end"
};

const JUSTIFY_MANY: Record<ActionGroupAlign, string> = {
  start: "sm:justify-start",
  center: "sm:justify-center",
  end: "sm:justify-end"
};

export function actionGroupClass(
  count: number,
  align: ActionGroupAlign = "center",
  fill = false,
  inline = false
): string {
  const shape = actionGroupShape(count);
  if (shape === "one") return fill ? ONE_FILL : inline ? ONE_INLINE[align] : ONE;
  if (shape === "two") return `${TWO_BASE} ${JUSTIFY_TWO[align]}`;
  return `${MANY_BASE} ${JUSTIFY_MANY[align]}`;
}
