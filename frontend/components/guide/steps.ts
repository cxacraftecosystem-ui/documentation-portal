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
      "Open the workshop you are documenting under — or create it — before you record anything else.",
    why:
      "Every record you make is scoped to a workshop. Products, tools and interviews all carry a linked workshop, and the Data Browser opens on \"By workshop\", which files the whole repository under the workshop it was recorded in. On a create form the most recent workshop you have access to is preselected, so getting this right once saves you picking it on every screen afterwards.",
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
      "Create the workshop before you leave for the field — it is the container everything else drops into.",
      "Records created outside a workshop's date window are flagged as out-of-window and need a reviewer's approval."
    ]
  },
  {
    id: "craft",
    label: "Craft",
    action: "Add craft",
    icon: Brush,
    href: "/crafts?new=1",
    summary: "Add the craft being documented so artisans, products and tools have something to hang off.",
    why:
      "Craft is the shared vocabulary of the repository: artisans link to a craft, products and tools inherit the craft name from it, and the Data Browser groups every workshop's contents by craft. Adding it once keeps spellings consistent across everyone's records.",
    fields: ["Craft name (required)", "Local name", "Category", "Place", "Description", "Craft media"],
    watch: [
      "Check the list first — if the craft already exists, reuse it instead of creating a near-duplicate spelling.",
      "The local name matters as much as the English one; record what the community actually calls it."
    ]
  },
  {
    id: "artisan",
    label: "Artisan",
    action: "Record artisan",
    icon: UserIcon,
    href: "/artisans/new",
    summary: "Record the person: who they are, where they work, how to reach them, and what they have learnt.",
    why:
      "The artisan is the anchor of the dataset. Products, processes, tools and questionnaire interviews all link back to an artisan record, and the Do's and Don'ts are the artisan's own hard-won craft knowledge — the part of the archive that cannot be reconstructed later.",
    fields: [
      "Name (required)",
      "Local name",
      "Workshop",
      "Craft (required)",
      "Or new craft name",
      "Place (required)",
      "Gender",
      "Phone",
      "Email",
      "Address",
      "Notes",
      "Do's (positive prompt) (required)",
      "Don'ts (negative prompt) (required)",
      "Artisan media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Do's and Don'ts are required. Press Enter for each new point — one lesson per line.",
      "You must either select an existing craft or type a new craft name; the form will not save with neither.",
      "Photo EXIF is retained and summarised into the notes automatically — you do not need to transcribe camera details by hand."
    ]
  },
  {
    id: "product",
    label: "Product",
    action: "Record product",
    icon: Package,
    href: "/products/new",
    summary: "Record one thing this artisan makes, with its measurements, economics and photographs.",
    why:
      "The product record is where the craft becomes measurable: dimensions, cost of making, selling price and market demand are the fields researchers compare across regions. Link it to the artisan and the craft and the whole chain stays navigable.",
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
      "Pick the linked craft first — the artisan dropdown stays disabled until a craft is chosen, then only lists that craft's artisans.",
      "Use \"Document using grid\" to photograph the piece against the measuring grid: it fills length, breadth and height for you and stores the photo as evidence.",
      "Choosing a linked artisan fills the artisan name and place; choosing a linked craft fills the craft name."
    ]
  },
  {
    id: "process",
    label: "Process",
    action: "Document process",
    icon: GitBranch,
    href: "/processes?new=1",
    summary: "Walk through how that product is made, one step at a time, filming each step as it happens.",
    why:
      "The process is the craft itself. A product photograph shows the result; the step-by-step record with per-step media shows the knowledge — the sequence, the hand movements, the judgement calls that a text description always loses.",
    fields: [
      "Name of the process (required)",
      "Artisan (required)",
      "Product (required)",
      "Per step: Name of the step (required)",
      "Per step: additional context notes (optional)",
      "Per step: attached media"
    ],
    watch: [
      "Add a step with \"Add Another Step\" and pick Sequential for an ordered stage, or Group of activities for things done together.",
      "Video is the preferred format for steps — capture the action as it happens rather than posing the result.",
      "Document the process against the product you already recorded, so the two stay linked."
    ]
  },
  {
    id: "tool",
    label: "Tool",
    action: "Record tool",
    icon: Wrench,
    href: "/tools/new",
    summary: "Record the toolkit the artisan uses: what it is made of, how big it is, who made it, what it costs to replace.",
    why:
      "Tools are the most quietly endangered part of a craft — the maker of a tool often disappears before the craft does. Replacement cost, maker and tradition type are the fields that record whether the toolchain behind the craft is still alive.",
    fields: [
      "Toolkit name (required)",
      "Local name",
      "English name",
      "Workshop",
      "Linked craft (fills craft name)",
      "Craft name (required)",
      "Linked artisan (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Process used in",
      "Material",
      "Years in use",
      "Height",
      "Width",
      "Length (inches)",
      "Breadth (inches)",
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
      "Fill only the dimensions that make sense for the tool — a blade has a length and thickness, a wheel has a radius.",
      "\"Process stages\" archives your captures in order as STAGE_STEP_1, STAGE_STEP_2, … so shoot them in sequence.",
      "You can also hand tools to specific artisans later from \"Assign tools to artisans\" — for your own artisans, ones shared with you for editing, or any artisan if you are an admin."
    ]
  },
  {
    id: "questionnaire",
    label: "Questionnaire",
    action: "Take interview",
    icon: ClipboardList,
    href: "/questionnaire?new=1",
    summary: "Sit down with the artisan and work through the interview sections, recording each answer as audio.",
    why:
      "The questionnaire is the artisan speaking in their own voice and their own language. Recorded audio is auto-transcribed on the server, so you get both the original recording and searchable text without typing during the interview.",
    fields: [
      "Interview title (required)",
      "Date",
      "Place",
      "Language",
      "Primary artisan",
      "Additional artisans",
      "Per question: \"Record this question\" audio, or typed answer"
    ],
    watch: [
      "There is one interview per exact set of artisans. If an entry already exists for that set, saving adds your answers to it — it never creates a duplicate.",
      "Answer only the questions actually asked; empty questions stay open for whoever picks the interview up next.",
      "Questions already answered by someone else can only be changed by that contributor or an admin.",
      "Use \"Check completion\" at the top of the screen to see the artisans × sections matrix and find the gaps."
    ]
  },
  {
    id: "media",
    label: "Miscellaneous Media",
    action: "Upload media",
    icon: Images,
    href: "/media",
    summary: "Upload the photographs, video, audio and files that do not belong to any single record.",
    why:
      "Field work produces context that no form has a slot for: the road into the village, the market, an unplanned conversation. Miscellaneous Media keeps that material inside the repository instead of on a phone that gets wiped.",
    fields: [
      "Capture media — images, video, audio and documents",
      "Media title / object name",
      "Linked record type (required)",
      "Linked entry (optional)",
      "Caption",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Upload stays disabled until you pick a Linked record type. If the file belongs to nothing in particular, pick \"Miscellaneous Media\" and leave the entry blank.",
      "Audio uploaded here is queued for transcription after upload, exactly like interview audio.",
      "If the file does turn out to belong to a record, link it — misc media can be attached to a record afterwards."
    ]
  },
  {
    id: "review",
    label: "Review",
    action: "Track your submissions",
    icon: ClipboardCheck,
    href: "/review",
    summary: "Everything you submit goes into the review queue and comes back Approved, Rejected, or Sent for revision.",
    why:
      "Review is what turns a pile of field notes into a dataset anyone can cite. It also means you are never the last check on your own work — a reviewer above your tier reads every record before it counts as final.",
    fields: [
      "Pending — submitted, waiting for a reviewer",
      "Approved — final, counted in the dataset",
      "Needs revision — comments explain what to change",
      "Rejected — not going into the dataset"
    ],
    watch: [
      "Below Professor the status chip is locked: whatever you create is submitted as Pending. That is normal, not an error.",
      "\"Send for revision\" always carries mandatory comments — read them, fix the record, and saving resubmits it as Pending.",
      "Reviewers only see submissions from contributors ranked strictly below them; the master admin sees everyone."
    ]
  },
  {
    id: "view-data",
    label: "View Data",
    action: "Browse records",
    icon: Eye,
    href: "/data",
    summary: "Browse the whole repository as a directory tree and export a report of any subtree.",
    why:
      "This is where the documentation stops being data entry and starts being research material: the same records, filed three different ways, previewable in place and downloadable as a spreadsheet.",
    fields: [
      "By workshop — every record filed under the workshop it was made in (the view it opens on)",
      "By uploader — a workshop's records filed under the researcher who uploaded them",
      "By media type — every file filed by what kind of file it is",
      "Download report (.xlsx)",
      "Download any folder as a zip, with content-type filters"
    ],
    watch: [
      "Pick a folder, then use the breadcrumb to move back up — the tree loads lazily as you expand it.",
      "Transcripts and AI text render as formatted Markdown in the preview pane, not raw text.",
      "Dataset download is a granted permission. If your role does not have it the browser shows a restricted notice — use Search to find records instead."
    ]
  }
];
