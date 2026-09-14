/**
 * The engine's public surface.
 *
 * Kotlin groups these functions into `object`s (`Color`, `Resample`, `Threshold`, ...). The TypeScript
 * mirror keeps the same grouping through namespace re-exports, so `Color.toGray(img)` reads identically in
 * both engines and a reader moving between them never has to translate a call site.
 *
 * Types, classes and enums are re-exported flat as well, because a type annotation reads badly through a
 * namespace and because `import type { GrayF }` is what every consumer actually wants.
 *
 * Nothing in this tree touches the DOM. The only interop with a canvas lives in
 * `RgbaImage.toImageData` / `RgbaImage.fromImageData`, which take and return structurally-typed plain
 * objects, so the whole engine runs unchanged inside a Web Worker.
 */

// --- buffers -----------------------------------------------------------------------------------------
export { GrayF, Mask, RgbaImage, Px } from './buffers';
export type { ImageDataLike, Range } from './buffers';

// --- imaging namespaces ------------------------------------------------------------------------------
export * as Classify from './classify';
export * as Color from './color';
export * as Components from './components';
export * as Contrast from './contrast';
export * as Convolve from './convolve';
export * as Denoise from './denoise';
export * as Distance from './distance';
export * as EdgeCanny from './edgeCanny';
export * as EdgeDog from './edgeDog';
export * as EdgeFlow from './edgeFlow';
export * as EdgeLog from './edgeLog';
export * as Fft from './fft';
export * as Geometry from './geometry';
export * as Matte from './matte';
export * as Morphology from './morphology';
export * as Resample from './resample';
export * as Thinning from './thinning';
export * as Threshold from './threshold';

// --- imaging types and enums -------------------------------------------------------------------------
export { Channel } from './color';
export { GradientOp, Gradients } from './convolve';
export { SeShape } from './morphology';
export { Labels } from './components';
export { FlowField, flowParams } from './edgeFlow';
export type { FlowParams } from './edgeFlow';
export type { SourceProfile } from './classify';

// --- vector namespaces -------------------------------------------------------------------------------
export * as BezierFit from './bezierFit';
export * as Boolean2D from './boolean2d';
export * as ContourTrace from './contourTrace';
export * as Raster from './raster';
export * as Simplify from './simplify';
export * as SkeletonTrace from './skeletonTrace';
export * as Smooth from './smooth';
export * as StrokeStyle from './strokeStyle';
export * as SvgPathData from './svgPathData';
export * as SvgWriter from './svgWriter';
export * as Vectorize from './vectorize';

// --- vector types ------------------------------------------------------------------------------------
export {
  DEFAULT_FLATTEN_TOLERANCE,
  DEFAULT_STYLE,
  FillRule,
  LineCap,
  LineJoin,
  Mat2D,
  VecDocument,
  VecPath,
  VecSeg,
  cubicAt,
  quadAt,
  vecLayer,
  vecPoint,
  vecShape,
  vecStyle,
} from './path';
export type { CubicSeg, LineSeg, QuadSeg, VecLayer, VecPoint, VecShape, VecStyle } from './path';
export { BoolOp } from './boolean2d';
export { VectorMode, DEFAULT_VECTORIZE_PARAMS, vectorizeParams } from './vectorize';
export type { VectorizeParams, RunStats } from './vectorize';
export type { Contour } from './contourTrace';
export type { Polyline, PolylinesWithWidths } from './skeletonTrace';
export { DEFAULT_SVG_OPTIONS, svgOptions } from './svgWriter';
export type { SvgOptions } from './svgWriter';

// --- export ------------------------------------------------------------------------------------------
export * as PngEncoder from './pngEncoder';
export * as PdfWriter from './pdfWriter';
export * as EpsWriter from './epsWriter';
export * as DxfWriter from './dxfWriter';
export {
  ExportFormat,
  ExportOptions,
  encodeBmp,
  encodeTiff,
  encodePngImage,
  encodeUtf8,
  exportDocument,
  exportDxf,
  exportEps,
  exportPdf,
  exportSvg,
  outputSize,
} from './exportFormats';
export type { ExportOptionsInit } from './exportFormats';

// --- pipeline ----------------------------------------------------------------------------------------
export {
  AUTO_THRESHOLD,
  DenoiseMode,
  EdgeEngine,
  Limits,
  MatteMode,
  ThinningMode,
  VectorModeParam,
  defaultCleanupParams,
  defaultEdgeParams,
  defaultFlowSettings,
  defaultMatteParams,
  defaultOutputParams,
  defaultPreprocessParams,
  defaultTraceParams,
  sanitizeCleanupParams,
  sanitizeEdgeParams,
  sanitizeFlowSettings,
  sanitizeMatteParams,
  sanitizeOutputParams,
  sanitizePreprocessParams,
  sanitizeTraceParams,
  withOverrides,
} from './params';
export type {
  CleanupParams,
  CleanupParamsInput,
  EdgeParams,
  EdgeParamsInput,
  FlowSettings,
  FlowSettingsInput,
  MatteParams,
  MatteParamsInput,
  OutputParams,
  OutputParamsInput,
  PreprocessParams,
  PreprocessParamsInput,
  TraceParams,
  TraceParamsInput,
} from './params';

export * as Styles from './styles';
export * as Subjects from './subjects';
export type { StylePreset } from './styles';
export type { SubjectPreset } from './subjects';

export * as Pipeline from './pipeline';
export { CancellationToken, CancelledError, edgeModelRegistry, stageIds } from './pipeline';
export type { EdgeModel, ProgressListener, StageResult, TraceResult } from './pipeline';

export * as ProjectCodec from './project';
export { PROJECT_SCHEMA_VERSION, projectMeta } from './project';
export type { ProjectDocument, ProjectMeta } from './project';

// --- namespace aliases -------------------------------------------------------------------------------
// The flat re-exports above are what a call site usually wants, but the Kotlin engine groups these same
// members under an `object` (`ProjectCodec`, `Exporter`) or a file the docs refer to by name (`Params`).
// Both spellings resolve here for the same reason the Kotlin side carries its `typealias` lines: a name
// that resolves only one way is a compile error in somebody else's module, and a reader moving between
// the two engines should never have to translate a call site.
export * as Params from './params';
export * as Project from './project';
export * as ExportFormats from './exportFormats';
export * as Exporter from './exportFormats';
