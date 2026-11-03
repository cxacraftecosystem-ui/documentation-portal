import {
  DenoiseMode,
  EdgeEngine,
  Limits,
  TraceParams,
  TraceParamsInput,
  VectorModeParam,
  defaultTraceParams,
  withOverrides,
} from './params';

export interface StylePreset {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly group: string;
  readonly params: TraceParams;
}

const GROUP_LINE_ART = 'Line art';
const GROUP_DRAWING = 'Drawing';
const GROUP_TECHNICAL = 'Technical';
const GROUP_PRINT = 'Print & relief';
const GROUP_MAKING = 'Making';

function preset(
  id: string,
  name: string,
  group: string,
  description: string,
  over: TraceParamsInput,
): StylePreset {
  return {
    id,
    name,
    description,
    group,
    params: withOverrides(defaultTraceParams(), { ...over, styleId: id }),
  };
}

export const ALL: readonly StylePreset[] = [
  preset(
    'clean-line',
    'Clean line',
    GROUP_LINE_ART,
    'Even-weight coherent contour line with texture suppressed. The default, and the safest choice ' +
      'for a photograph of an object.',
    {
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.5 },
      output: { vectorMode: VectorModeParam.CENTERLINE, strokeWidth: 1.6, simplify: 1 },
    },
  ),
  preset(
    'pencil-sketch',
    'Pencil sketch',
    GROUP_DRAWING,
    'Soft graphite feel: XDoG at a low phi, extra smoothing, and gentle width modulation from the ' +
      'distance transform.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.4 },
      edge: { engine: EdgeEngine.XDOG, dogSigma: 1.2, xdogEpsilon: 0.19, xdogPhi: 6 },
      cleanup: { minBlobArea: 16, pruneSpurs: 3 },
      output: {
        smoothIterations: 2,
        simplify: 0.8,
        strokeWidth: 1.2,
        modulateWidth: true,
        widthScale: 0.8,
      },
    },
  ),
  preset(
    'technical-drawing',
    'Technical drawing',
    GROUP_TECHNICAL,
    'Uniform hairline with corners protected and smoothing switched off, from Canny edges.',
    {
      edge: { engine: EdgeEngine.CANNY, blurSigma: 1.4, cannyLow: 0.004, cannyHigh: 0.012 },
      cleanup: { closeRadius: 1, pruneSpurs: 3 },
      output: {
        smoothIterations: 0,
        corner: 140,
        simplify: 0.6,
        fitError: 0.9,
        strokeWidth: 0.8,
      },
    },
  ),
  preset(
    'blueprint',
    'Blueprint',
    GROUP_TECHNICAL,
    'The technical line inverted: pale strokes on a deep blue ground.',
    {
      edge: { engine: EdgeEngine.CANNY, blurSigma: 1.4, cannyLow: 0.004, cannyHigh: 0.012 },
      cleanup: { closeRadius: 1, pruneSpurs: 3 },
      output: {
        smoothIterations: 0,
        corner: 140,
        simplify: 0.6,
        fitError: 0.9,
        strokeWidth: 0.9,
        strokeColor: 0xffdce8ff,
        background: 0xff0b2a4a,
      },
    },
  ),
  preset(
    'tattoo-outline',
    'Tattoo outline',
    GROUP_DRAWING,
    'Bold closed outlines with heavy simplification. Small detail is dropped deliberately.',
    {
      edge: { engine: EdgeEngine.XDOG, dogSigma: 1.4, xdogPhi: 40 },
      cleanup: { skeletonize: false, closeRadius: 2, minBlobArea: 120, fillHolesUpTo: 64 },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 2.4,
        fitError: 2.4,
        strokeWidth: 2.6,
        minPathLength: 12,
        fillClosed: true,
      },
    },
  ),
  preset(
    'mandala',
    'Mandala',
    GROUP_PRINT,
    'Keeps fine radial detail: Laplacian-of-Gaussian thin lines with blob removal almost off.',
    {
      edge: { engine: EdgeEngine.LOG, logSigma: 1.1, logSlope: 0.01 },
      cleanup: { minBlobArea: 8, closeRadius: 0, removeIsolated: false, pruneSpurs: 2 },
      output: { simplify: 0.5, strokeWidth: 1, minPathLength: 6 },
    },
  ),
  preset(
    'comic',
    'Comic',
    GROUP_DRAWING,
    'Hard XDoG lines with filled black masses.',
    {
      edge: { engine: EdgeEngine.XDOG, xdogEpsilon: 0.185, xdogPhi: 120 },
      cleanup: { skeletonize: false, closeRadius: 2, minBlobArea: 40, fillHolesUpTo: 32 },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 1.4,
        fitError: 1.8,
        strokeWidth: 1.8,
        fillClosed: true,
      },
    },
  ),
  preset(
    'architectural',
    'Architectural',
    GROUP_TECHNICAL,
    'Straight-line bias, aggressive corner preservation and long gap bridging.',
    {
      edge: { engine: EdgeEngine.CANNY, blurSigma: 1.4, cannyLow: 0.004, cannyHigh: 0.012 },
      cleanup: {
        minBlobArea: 48,
        bridgeGaps: true,
        maxGap: 28,
        maxBridgeAngle: 35,
        pruneSpurs: 6,
      },
      output: {
        simplify: 2,
        fitError: 3,
        corner: 150,
        smoothIterations: 0,
        strokeWidth: 1.2,
      },
    },
  ),
  preset(
    'continuous-line',
    'Continuous line',
    GROUP_LINE_ART,
    'One long path where possible: maximum endpoint bridging and the largest component only.',
    {
      edge: { engine: EdgeEngine.FDOG, flow: { sigmaM: 6 } },
      cleanup: {
        minBlobArea: 64,
        maxGap: 48,
        maxBridgeAngle: 90,
        pruneSpurs: 8,
        keepLargest: 1,
      },
      output: { simplify: 1.6, smoothIterations: 2, strokeWidth: 1.6, minPathLength: 24 },
    },
  ),
  preset(
    'single-stroke',
    'Single stroke',
    GROUP_LINE_ART,
    'Pen-plotter output: one pass per stroke, uniform width, no fills, tiny paths dropped.',
    {
      edge: { engine: EdgeEngine.ADAPTIVE, useSauvola: true, adaptiveRadius: 15, adaptiveC: 0.03 },
      cleanup: { skeletonize: true, minBlobArea: 32, pruneSpurs: 5 },
      output: {
        simplify: 1.2,
        strokeWidth: 1,
        modulateWidth: false,
        minPathLength: 8,
        fillClosed: false,
        background: null,
      },
    },
  ),
  preset(
    'calligraphy',
    'Calligraphy',
    GROUP_DRAWING,
    'Thick-and-thin from the distance transform, with tapered ends.',
    {
      edge: { engine: EdgeEngine.FDOG, flow: { sigmaM: 4 } },
      cleanup: { pruneSpurs: 4 },
      output: {
        simplify: 0.8,
        smoothIterations: 2,
        strokeWidth: 2,
        modulateWidth: true,
        widthScale: 1.2,
      },
    },
  ),
  preset(
    'woodcut',
    'Woodcut',
    GROUP_PRINT,
    'High-contrast chunky shapes with thick outlines.',
    {
      preprocess: { contrast: 0.45, claheClip: 3 },
      edge: { engine: EdgeEngine.XDOG, dogSigma: 1.8, xdogEpsilon: 0.183, xdogPhi: 200 },
      cleanup: {
        skeletonize: false,
        closeRadius: 3,
        openRadius: 1,
        minBlobArea: 150,
        fillHolesUpTo: 128,
      },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 2.8,
        fitError: 3.2,
        strokeWidth: 3,
        minPathLength: 16,
        fillClosed: true,
      },
    },
  ),
  preset(
    'stencil',
    'Stencil',
    GROUP_MAKING,
    'Closed regions with small holes filled and gaps bridged, so the cut stays in one piece.',
    {
      edge: { engine: EdgeEngine.ADAPTIVE, useSauvola: true, adaptiveRadius: 21 },
      cleanup: {
        skeletonize: false,
        closeRadius: 3,
        bridgeGaps: true,
        maxGap: 20,
        fillHolesUpTo: 256,
        minBlobArea: 200,
      },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 2,
        fitError: 2.4,
        minPathLength: 20,
        fillClosed: false,
      },
    },
  ),
  preset(
    'silhouette',
    'Silhouette',
    GROUP_MAKING,
    'Filled solid, largest components only, no interior detail.',
    {
      edge: { engine: EdgeEngine.ADAPTIVE, adaptiveRadius: 31, adaptiveC: 0.01 },
      cleanup: {
        skeletonize: false,
        closeRadius: 4,
        fillHolesUpTo: Limits.MAX_AREA,
        keepLargest: 3,
        minBlobArea: 400,
      },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 2.4,
        fitError: 2.8,
        minPathLength: 24,
        fillClosed: true,
      },
    },
  ),
  preset(
    'engraving',
    'Engraving',
    GROUP_PRINT,
    'Fine dense line work: high sensitivity, minimal blob removal, thin uniform strokes.',
    {
      edge: {
        engine: EdgeEngine.FDOG,
        sensitivity: 0.75,
        xdogEpsilon: 0.138,
        flow: { sigmaC: 0.8, sigmaM: 2 },
      },
      cleanup: { minBlobArea: 6, closeRadius: 0, pruneSpurs: 2 },
      output: {
        simplify: 0.5,
        fitError: 1,
        smoothIterations: 0,
        strokeWidth: 0.7,
        minPathLength: 6,
      },
    },
  ),
  preset(
    'colouring-book',
    'Colouring book',
    GROUP_MAKING,
    'Closed regions with every gap bridged, so a bucket fill cannot leak.',
    {
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.45 },
      cleanup: {
        skeletonize: false,
        closeRadius: 2,
        bridgeGaps: true,
        maxGap: 24,
        maxBridgeAngle: 80,
        minBlobArea: 64,
      },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 1.6,
        fitError: 2,
        minPathLength: 10,
        fillClosed: false,
      },
    },
  ),
  preset(
    'embroidery',
    'Embroidery',
    GROUP_MAKING,
    'Heavily simplified centrelines with short paths dropped, so no stitch is shorter than a needle.',
    {
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.45 },
      cleanup: { minBlobArea: 120, maxGap: 16, pruneSpurs: 8 },
      output: {
        simplify: 3,
        fitError: 3.5,
        smoothIterations: 2,
        strokeWidth: 1.4,
        minPathLength: 24,
      },
    },
  ),
  preset(
    'laser-cut',
    'Laser cut',
    GROUP_MAKING,
    'Closed paths only and hairline strokes, with no open geometry a cutter would refuse.',
    {
      edge: { engine: EdgeEngine.ADAPTIVE, adaptiveRadius: 25 },
      cleanup: {
        skeletonize: false,
        closeRadius: 3,
        bridgeGaps: true,
        maxGap: 16,
        fillHolesUpTo: 512,
        minBlobArea: 250,
      },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 1.6,
        fitError: 2,
        strokeWidth: 0.25,
        minPathLength: 16,
        fillClosed: false,
      },
    },
  ),
  preset(
    'craft-pattern',
    'Craft pattern',
    GROUP_MAKING,
    'Mid-simplification outlines sized for transfer printing.',
    {
      edge: { engine: EdgeEngine.XDOG, xdogPhi: 60 },
      cleanup: { skeletonize: false, closeRadius: 2, minBlobArea: 100, fillHolesUpTo: 64 },
      output: {
        vectorMode: VectorModeParam.OUTLINE,
        simplify: 1.8,
        fitError: 2.2,
        minPathLength: 12,
        fillClosed: false,
      },
    },
  ),
  preset(
    'minimal',
    'Minimal',
    GROUP_LINE_ART,
    'Extreme simplification: the fewest paths that still read as the subject.',
    {
      edge: {
        engine: EdgeEngine.FDOG,
        sensitivity: 0.35,
        xdogEpsilon: 0.064,
        flow: { sigmaM: 5 },
      },
      cleanup: { minBlobArea: 300, closeRadius: 2, maxGap: 24, pruneSpurs: 10 },
      output: {
        simplify: 4,
        fitError: 5,
        smoothIterations: 2,
        strokeWidth: 2,
        minPathLength: 40,
      },
    },
  ),
];

const BY_ID = new Map<string, StylePreset>(ALL.map((s) => [s.id, s]));

export function byId(id: string): StylePreset | null {
  return BY_ID.get(id) ?? null;
}

export function defaultPreset(): StylePreset {
  return byId(Limits.DEFAULT_STYLE_ID) ?? ALL[0];
}

export function groups(): string[] {
  const seen: string[] = [];
  for (const s of ALL) if (!seen.includes(s.group)) seen.push(s.group);
  return seen;
}

export function inGroup(group: string): StylePreset[] {
  return ALL.filter((s) => s.group === group);
}
