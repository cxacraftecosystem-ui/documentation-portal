import { butiSelvedgeUrl } from "@/components/hero/buti";

export function PageSelvedge() {
  const motif = butiSelvedgeUrl();

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
