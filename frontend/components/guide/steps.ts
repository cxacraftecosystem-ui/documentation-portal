import {
  Brush,
  ClipboardCheck,
  ClipboardList,
  Eye,
  GitBranch,
  Images,
  Package,
  User as UserIcon,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

/**
 * The ten steps of the documentation process, in the order a researcher actually performs
 * them in the field.
 *
 * Every `label` here is the Android-parity feature name (see
 * `.claude/skills/field-repo-frontend/SKILL.md` → "Naming"): the walkthrough must call a
 * screen exactly what the dashboard tile and the Android menu call it, or the guide teaches
 * a vocabulary the product does not use. `fields` mirrors the real form labels one-for-one,
 * so a researcher reading the guide recognises the screen when they open it.
 *
 * The labels are copied from the forms themselves — `components/forms/ArtisanForm`,
 * `ProductForm`, `ToolForm`, `ProcessForm`, the inline forms on the workshops / crafts /
 * questionnaire / media pages — plus the two shared sections every record form mounts:
 * `<WorkshopSelect>` (label "Workshop") and `<LocationFields>` (heading "Location"). Rename a
 * field on a form and it must be renamed here and in `docs/WALKTHROUGH.md`, which carries the
 * same lists in prose.
 */
export type GuideStep = {
  /** Stable anchor id — the rail scrolls to `#${id}` and the URL hash survives a reload. */
  id: string;
  /** Android-parity feature name. Never invent a new one. */
  label: string;
  /** The dashboard tile's verb for this action ("Record artisan", "Take interview", …). */
  action: string;
  icon: LucideIcon;
  /** The real screen this step is teaching. */
  href: string;
  /** One sentence: what you are doing at this point in the field. */
  summary: string;
  /** Why the dataset needs it — the reason the step is not optional. */
  why: string;
  /** The fields the screen actually asks for, in screen order. Required ones are marked. */
  fields: string[];
  /** Field-tested cautions: the things that trip people up on their first workshop. */
  watch: string[];
};

export const GUIDE_STEPS: GuideStep[] = [
  {
    id: "workshop",
    label: "Workshop",
    action: "Record workshop",
    icon: UsersRound,
    href: "/workshops?new=1",
    summary:
      "Open or create the workshop first.",
    why:
      "Every record belongs to a workshop, and new forms preselect your latest one.",
    fields: [
      "Workshop title (required)",
      "Place (required)",
      "Start and end date",
      "Description",
      "Notes",
      "Linked artisans",
      "Crafts covered",
      "Workshop media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Create it before you leave for the field.",
      "Records outside its dates need approval."
    ]
  },
  {
    id: "craft",
    label: "Craft",
    action: "Add craft",
    icon: Brush,
    href: "/crafts?new=1",
    summary: "Add the craft being documented.",
    why:
      "Artisans, products and tools link to it, keeping spellings consistent.",
    fields: ["Craft name (required)", "Local name", "Category", "Place", "Description", "Craft media"],
    watch: [
      "Reuse an existing craft rather than adding a near-duplicate.",
      "Record the local name the community uses."
    ]
  },
  {
    id: "artisan",
    label: "Artisan",
    action: "Record artisan",
    icon: UserIcon,
    href: "/artisans/new",
    summary: "Who they are, where they work and what they’ve learnt.",
    why:
      "Everything links back to the artisan. Their Do’s and Don’ts can’t be recovered later.",
    fields: [
      "Name (required)",
      "Local name",
      "Workshop",
      "Craft (required)",
      "Or new craft name",
      "Place (required)",
      "Gender",
      "Date of birth (required)",
      "Craft start date (required)",
      "Experience (years)",
      "Experience (months)",
      "Phone",
      "Email",
      "Address",
      "Notes",
      "Do's (required)",
      "Don'ts (required)",
      "Artisan media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Do’s and Don’ts are required — one point per line.",
      "Choose a craft or type a new one.",
      "Photo details are added to the notes automatically."
    ]
  },
  {
    id: "product",
    label: "Product",
    action: "Record product",
    icon: Package,
    href: "/products/new",
    summary: "One thing they make — size, cost and photos.",
    why:
      "Dimensions, cost and price are what researchers compare across regions.",
    fields: [
      "Product name (required)",
      "Local name",
      "Workshop",
      "Product type",
      "Linked craft (fills craft name)",
      "Craft name (required)",
      "Linked artisan (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Time taken to complete",
      "Size",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Cost of making",
      "Selling price",
      "Market demand",
      "Raw materials used",
      "Main tools used",
      "Function or use",
      "Remarks",
      "Product media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Pick the craft first; it filters the artisans.",
      "\"Document using grid\" measures the piece from a photo.",
      "Linked craft and artisan fill their names for you."
    ]
  },
  {
    id: "process",
    label: "Process",
    action: "Document process",
    icon: GitBranch,
    href: "/processes?new=1",
    summary: "How the product is made, step by step, on video.",
    why:
      "The steps on video capture knowledge a photo or description loses.",
    fields: [
      "Name of the process (required)",
      "Artisan (required)",
      "Product (required)",
      "Per step: Name of the step (required)",
      "Per step: additional context notes (optional)",
      "Per step: attached media"
    ],
    watch: [
      "Use Sequential for ordered steps, Group for parallel ones.",
      "Film the action, not the result.",
      "Link it to the product you recorded."
    ]
  },
  {
    id: "tool",
    label: "Tool",
    action: "Record tool",
    icon: Wrench,
    href: "/tools/new",
    summary: "Material, size, maker and replacement cost.",
    why:
      "Tool makers often vanish before the craft does — record who makes them.",
    fields: [
      "Toolkit name (required)",
      "Local name",
      "English name",
      "Workshop",
      "Linked crafts (fills craft name)",
      "Craft name (required)",
      "Linked artisans (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Process used in",
      "Material",
      "Years in use",
      "Height (cm)",
      "Width (cm)",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Thickness",
      "Weight",
      "Radius",
      "Maker",
      "Tradition type",
      "Replacement cost",
      "Suggestions for improvement",
      "Remarks",
      "Process stages",
      "Tool media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Fill only the dimensions that apply.",
      "cm and inch boxes fill each other.",
      "Link several crafts and artisans at once.",
      "Shoot process stages in order.",
      "Assign tools to artisans later from \"Assign tools to artisans\"."
    ]
  },
  {
    id: "questionnaire",
    label: "Questionnaire",
    action: "Take interview",
    icon: ClipboardList,
    href: "/questionnaire?new=1",
    summary: "Work through the sections, recording each answer.",
    why:
      "Audio is transcribed, so you keep their voice and searchable text.",
    // Regenerated against the form on 2026-09-16 and now held to `shared/questionnaire-form-
    // contract.json` by `backend/tests/test_questionnaire_form_contract.py`, together with the
    // Android register and the printed guide. It listed a "Date" the form has not had since the
    // server began deriving `interviewDate` from `recordedAt` (see page.tsx:997), and omitted
    // Workshop, Questionnaire, Status and Interview notes. `WalkthroughStepsTest.kt` holds this
    // array and the Kotlin one equal, so the two move together — what it could never say is which
    // of them was right, which is why the contract exists.
    fields: [
      "Interview title (required)",
      "Place",
      "Language",
      "Workshop",
      "Questionnaire",
      "Status",
      "Artisans interviewed",
      "Per question: \"Record this question\" audio, or typed answer",
      "Interview notes"
    ],
    watch: [
      "One interview per set of artisans; saving adds to an existing one.",
      "Answer only what was asked.",
      "Others’ answers can only be changed by them or an admin.",
      "\"Check completion\" shows the gaps."
    ]
  },
  {
    id: "media",
    label: "Miscellaneous Media",
    action: "Upload media",
    icon: Images,
    href: "/media",
    summary: "Files that don’t belong to one record.",
    why:
      "Keeps field context with your records, not on a phone.",
    fields: [
      "Capture media — images, video, audio and documents",
      "Media title / object name",
      "Linked record type (required)",
      "Linked entry (optional)",
      "Caption",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Pick a record type first, or \"Miscellaneous Media\".",
      "Audio is transcribed.",
      "You can link it to a record later."
    ]
  },
  {
    id: "review",
    label: "Review",
    action: "Track your submissions",
    icon: ClipboardCheck,
    href: "/review",
    summary: "Submissions come back Approved, Rejected or Sent for revision.",
    why:
      "A reviewer above you checks every record before it counts.",
    fields: [
      "Pending — submitted, waiting for a reviewer",
      "Approved — final, counted in the dataset",
      "Needs revision — comments explain what to change",
      "Rejected — not going into the dataset"
    ],
    watch: [
      "Below Professor, everything is submitted as Pending.",
      "Fix what the comments say; saving resubmits it.",
      "Reviewers see work from those below them."
    ]
  },
  {
    id: "view-data",
    label: "View Data",
    action: "Browse records",
    icon: Eye,
    href: "/data",
    summary: "Browse records as folders and export any of them.",
    why:
      "The same records, filed three ways, ready to preview and download.",
    fields: [
      "By workshop — every record filed under the workshop it was made in (the view it opens on)",
      "By uploader — a workshop's records filed under the researcher who uploaded them",
      "By media type — every file filed by what kind of file it is",
      "Download report (.xlsx)",
      "Download any folder as a zip, with content-type filters"
    ],
    watch: [
      "Use the breadcrumb to go back up.",
      "Transcripts preview as formatted text.",
      "Without dataset access, use Search instead."
    ]
  }
];
