import { butiSelvedgeUrl } from "@/components/hero/buti";

/**
 * A thin embroidered strip down the left and right margins of the signed-in pages.
 *
 * The signed-in app is a wide, near-white field (`bg-bg-0`) with the content column capped at
 * `max-w-7xl`, so on any desktop screen there is empty page either side of the work. The landing
 * page already prints the house buti across its bands; this carries the same motif — the same
 * exported geometry, not a lookalike — down the two edges, the way a selvedge runs down a length of
 * cloth. It is decoration and nothing else: no state, no interaction, nothing to read.
 *
 * ── FOUR THINGS THAT MAKE IT A MARGIN RATHER THAN A FEATURE ───────────────────────────────────
 *
 * **It is masked, not filled.** The strip paints `rgb(var(--ink-900))` through the motif's alpha,
 * so it is dark ink on the light theme and light ink on the dark one from ONE data URI, and it
 * follows the palette if the palette moves. A filled SVG would need a variant per theme and would
 * silently go wrong on the next token change — see [butiSelvedgeUrl].
 *
 * **It is fixed, not in flow.** `position: fixed` keeps the strips at the viewport edges and out of
 * the layout entirely: nothing reflows, no page gets narrower, and a long page does not have to
 * paint kilometres of motif. `pointer-events-none` means a click near the edge still reaches
 * whatever is under it, and a strip can never intercept a drag on a form control.
 *
 * **It is behind everything.** `z-index: 0` against the shell's own stacking, and the shell's
 * content sits above it. The nav island is `z-[60]`, dialogs higher still; nothing here can cover a
 * control. The opacity is deliberately at the bottom of what is visible — this should register as
 * texture at the edge of vision and never compete with the page.
 *
 * **It is desktop-only.** `hidden md:block`. On a handset the whole viewport IS the content column;
 * 16px of ornament down each edge would be 32px taken off a form that is already tight, which is
 * the opposite of a margin. The same reasoning covers print: `print:hidden`, because ink on paper
 * costs someone money and nobody is printing a record for its border.
 *
 * ── AND THE ONE THING IT MUST NOT DO ──────────────────────────────────────────────────────────
 *
 * `aria-hidden` with no text, no label and no role. A screen reader that announced a decorative
 * border on every page would put a wall in front of the work on every navigation. There is nothing
 * here to convey: a reader who cannot see it has lost nothing but ornament.
 */
export function PageSelvedge() {
  const motif = butiSelvedgeUrl();

  // 16px of strip against a 24px motif cell shows the buti a little cropped at the petals, which is
  // what a real selvedge does where the cloth is cut. `contain` would letterbox it into a bordered
  // square instead and read as a column of stamps.
  const strip: React.CSSProperties = {
    maskImage: motif,
    WebkitMaskImage: motif,
    maskSize: "16px 16px",
    WebkitMaskSize: "16px 16px",
    maskRepeat: "repeat-y",
    WebkitMaskRepeat: "repeat-y",
    maskPosition: "center top",
    WebkitMaskPosition: "center top",
    backgroundColor: "rgb(var(--ink-900))",
  };

  return (
    <div aria-hidden className="pointer-events-none fixed inset-y-0 left-0 right-0 z-0 hidden md:block print:hidden">
      <div className="absolute inset-y-0 left-0 w-4 opacity-[0.07]" style={strip} />
      <div className="absolute inset-y-0 right-0 w-4 opacity-[0.07]" style={strip} />
    </div>
  );
}
