"""How a stored dimension came to be known: typed off a tape, computed from marks, or guessed by a model.

**THE LAW THIS MODULE EXISTS TO MAKE EXPRESSIBLE.**

    EVERY STORED DIMENSION STATES ITS METHOD. A METHOD THAT IS IRREPRODUCIBLE ADDITIONALLY REQUIRES
    ACCEPTANCE BY A NAMED PERSON.

Not "AI needs a layer and arithmetic needs nothing". The line is about REPRODUCIBILITY, because that
is the only property that decides what a later reader can do with the number. Arithmetic over two
pixel distances may write into a documented dimension — but only while a reader can tell that
arithmetic is what wrote it. A vision model's estimate may reach the same field — but only as a
proposal a person accepted, because nobody can re-derive it and nothing can check it.

**THE CONCRETE FAILURE THIS PREVENTS, WHICH IS ALREADY IN THIS DATABASE.** ``ProductDocumentation``
and ``ToolDocumentation`` carry ``lengthInches`` / ``breadthInches`` / ``heightInches``, printed as a
documented dimension by ``services/record_fields.py`` — the "Dimensions (LxBxH in)" cell on both
models — and therefore read in the data browser, in every .xlsx sheet and in the CSV exports by
somebody costing a production run. Re-check that both tables really carry all three with::

    grep -n "heightInches" backend/prisma/schema.prisma

which must answer for ``ProductDocumentation`` and for ``ToolDocumentation`` (the tool's arrived in
migration ``20260913120100``; before it, a grid-measured tool height landed in the unit-less
``height`` column and no row could say what unit it was in).

THREE DIFFERENT PROCESSES WRITE THOSE COLUMNS and, until this module, the row recorded no difference
between them:

1. a researcher reading a tape measure and typing the number;
2. the offline photo-geometry path — ``PhotoMeasure`` on the handset and its twin on the web — a
   ratio of two pixel distances, deterministic, with a stated error bar that is thrown away at the
   screen edge;
3. ``POST /media/analyze-measurement`` — Gemini looking at a photograph of the object on a grid sheet
   and, in the prompt's own verb, ESTIMATING it.

And it was worse than silence. ``records.merge_field_provenance`` stamps every changed non-empty
field with the ``{by, byName, at}`` of the account that pressed Save, and the dimension columns are
not in its ``PROVENANCE_SKIP_FIELDS`` (they must not be — see below). So a Gemini estimate that
auto-filled a form field was stored attributed BY NAME to the researcher who saved the form. The
record did not merely fail to say a machine produced the number — it positively asserted that a named
human measured it. That is the defect. A wrong dimension is a costing error; a wrong dimension
wearing somebody else's name is a costing error nobody can trace and that person cannot disown.

**WHY THE HUMAN STAMP BECOMES TRUE AGAIN RATHER THAN BEING REMOVED.** The remedy is not to strip
``by``/``byName`` off a machine-filled dimension, and it is not to add the dimension columns to
``PROVENANCE_SKIP_FIELDS`` either — both delete the most useful fact on the row, which is WHO can be
asked about the number. It is to put ``method`` BESIDE them, at which point the same three keys stop
claiming authorship and start claiming what actually happened::

    lengthInches: {by: usr_7, byName: "R. Menon", at: "...", method: "VISION_MODEL",
                   methodProvider: "gemini", methodModelId: "gemini-2.5-flash-lite",
                   methodConfidence: 0.8}

Read aloud: *a vision model estimated this, and R. Menon accepted it into the record at that moment.*
That is a true sentence, it is the sentence an auditor needs, and it needs no acceptance column and
no migration — ``extraMetadata`` is an existing Json column on both tables. The acceptance IS the
save, once the method sits next to the signature.

**WHY AN ABSENT MARKER IS ``UNRECORDED`` AND NOT ``TYPED``, WHICH IS THE ONE DECISION HERE MOST
LIKELY TO BE "SIMPLIFIED" LATER.** It is tempting to read a save with no marker as a typed
measurement, on the reasoning that typing is what every client did before this existed. That would be
the original defect with a new spelling: the rows already in this database include Gemini estimates
that auto-filled a field, so defaulting to TYPED would assert a human measured a number a model
guessed — for exactly the rows where the assertion is false, and with no way left to tell. Nobody
wrote the method down, so the method is unrecorded, and :data:`UNRECORDED` says so in that word. A
fact nobody recorded is stated as unknown, never guessed, and never left as a null that reads like
"none".

**THE HONEST LIMIT, STATED HERE SO NOBODY HAS TO DISCOVER IT.** A marker arriving on a request body is
a CLAIM, not a proof. A client could send ``TYPED`` for a number Gemini produced, and this server
cannot tell — exactly as it cannot tell whether a typed 12 was read off a tape or invented. The
marker is precisely as trustworthy as every other value in the form body it rides on, which is the
level of trust this whole API already runs on. What it is not is *worse* than what came before: the
record asserted the false thing BY CONSTRUCTION, for every machine-filled dimension, with no client
involved at all. Moving from "always wrong" to "as reliable as the rest of the body" is the whole of
the available improvement, and it is worth having.

A STRONGER GATE WOULD BE A ROW THAT EXISTS BEFORE ANYBODY ACCEPTS IT — a proposal stored on its own,
so a DECLINED reading is still evidence and an export can print what was accepted and by whom. This
repository has no table for that, and inventing one is a migration, a scope decision and a second
review surface. This module is the part that can land alone, today, with no schema change; the
vocabulary below is deliberately the vocabulary such a table would carry, so that work would be an
addition rather than a translation.

**NOTHING IN THIS MODULE WRITES ANYTHING.** No ``db`` import, no Prisma, no network, no ``await``.
These are the rules, and rules that cannot be tested without a database do not get tested. Every
function here is pure, so the whole of this file's behaviour is asserted in
``tests/test_measurement_provenance.py`` on a laptop with no Postgres — and, the part that matters in
the field, a marker for a courtyard measurement is a dictionary literal the handset can compose with
no signal. ``test_nothing_in_this_module_can_write_read_or_wait_for_anything`` parses this file and
fails if that stops being true.

--------------------------------------------------------------------------------------------------
THE CLIENT SPECIFICATION — what the web and Android must do, none of which is implemented here
--------------------------------------------------------------------------------------------------

The server half is this module, ``services/ai.py`` and ``api/routes/media.py`` (which produce the
marker), ``schemas/records.py`` (which accepts it on all four record bodies and refuses a malformed
one by name), ``services/access.REVISION_SKIP_FIELDS`` (which keeps it out of the audit ledger) and
``records.merge_field_provenance`` (which stores it). The client half is in files this module does
not own, and it is written down HERE rather than in a planning document because this is the file
whose vocabulary those clients have to speak.

**THE ORDER THESE LAND IN, AND WHY IT IS NOT NEGOTIABLE.** ``APIModel`` is
``ConfigDict(extra="forbid")``, so until the four schema declarations exist a client sending
``measurementMethods`` has its ENTIRE save rejected with a 422 naming a key the researcher has never
heard of — and the web's ``saveOrQueue`` refuses to queue a 4xx ("the server saw it and said no"), so
with no signal the work is thrown away rather than retried. Android's outbox does not retry a 4xx
either. DEPLOY THE SCHEMA BEFORE EITHER CLIENT, never the other way round; the web deploys to Vercel
and this API to EC2 separately, so a newer web build meeting an older API is the ordinary case rather
than the unlucky one.

And ``access.REVISION_SKIP_FIELDS`` must carry :data:`MARKER_BODY_KEY` before the schema makes the key
sendable at all — the reason is under §THE RECORD HALF, and the rows it prevents are in an
append-only audit table that landing the skip entry afterwards cannot un-write. Both of those land in
the same change as this module, so there is no window between them in this tree; the order still
matters to anyone who backs part of it out.

**1. Propose, then let a person confirm. Never auto-fill.**

``POST /media/analyze-measurement`` returns, beside the unchanged ``analysis`` block::

    method: "VISION_MODEL"        provider: "gemini"
    modelId: "gemini-2.5-flash-lite"
    selfReportedConfidence: 0.8 | null      confidenceIsCalibrated: false
    requiresAcceptance: true
    methodMarker: { method, provider, modelId, selfReportedConfidence }

``requiresAcceptance`` is ``true`` on every response this endpoint can produce, including the
failures. A client that honours it shows the number as a PROPOSAL with a button, and writes nothing
into form state until the button is pressed. The call sites are ``GridMeasurement.tsx`` →
``ProductForm.tsx`` / ``ToolForm.tsx`` on the web, and ``GridMeasurementSection`` in
``MainActivity.kt`` on Android.

Adding keys is safe for every build already in the field: ``ApiClient.kt`` builds its ``Json`` with
``ignoreUnknownKeys = true``, so an installed handset that has never heard of ``methodMarker``
ignores it rather than throwing — the difference between a purely additive server change and a fleet
of phones that cannot read a measurement response at all. Re-check that with::

    grep -n "ignoreUnknownKeys" android/app/src/main/java/com/fieldrepository/app/data/ApiClient.kt

**2. Send the marker back when the value is saved.**

On the product / tool request body, a top-level object keyed by column name::

    measurementMethods: {
      "lengthInches":  {"method": "VISION_MODEL", "provider": "gemini",
                        "modelId": "gemini-2.5-flash-lite", "selfReportedConfidence": 0.8},
      "heightInches":  {"method": "PHOTO_GEOMETRY", "technique": "RECTIFIED"}
    }

    (and ``breadthInches`` is simply absent, which is UNRECORDED — the ordinary case, and what a
    hand-typed dimension looks like on the wire. See below for why it is not TYPED.)

Send ``methodMarker`` verbatim as it arrived for a confirmed model reading. Send ``{"method":
"PHOTO_GEOMETRY", "technique": "SCALE"}`` — or ``"RECTIFIED"`` — for the offline geometry path, which
is the only new obligation that path acquires and it costs it one dictionary literal it already has
every fact for.

``{"method": "TYPED"}`` IS ACCEPTED AND NEITHER CLIENT SENDS IT, WHICH IS CORRECT. An earlier draft
of this paragraph told clients to mark a hand-typed number TYPED, and both the web and the handset
declined — independently, and they were right. TYPED is a POSITIVE CLAIM that a person read this off
an instrument, and a form cannot honestly make it: a box holding a number nobody touched this session
looks identical to one somebody just typed, and the overwhelming majority of saves carry dimensions
loaded from an existing record. A client that stamped TYPED on all of them would assert a measurement
act that never happened, for every row it touched — the same class of lie as a model estimate wearing
a researcher's name, which is the defect this module exists to close.

So the rule the clients follow, and the one this server is built around, is: **mark only what a
ROUTE produced.** Everything else is absent, absence is :data:`UNRECORDED`, and UNRECORDED is an
honest and distinguishable answer. TYPED remains in the vocabulary for a surface that can genuinely
witness the act — a dedicated "I measured this with a tape" affirmation, which nothing ships today.

THE KEY IS OMITTED ENTIRELY WHEN THERE IS NOTHING TO SAY, and it is never sent as ``null``. Sending
nothing is legal and means :data:`UNRECORDED`; it must never mean TYPED. ``null`` is not the same as
absent here: against a server deployed before this field existed, ``"measurementMethods": null`` is
an ``extra_forbidden`` 422 on the WHOLE save — the same unqueueable, unretried loss described above,
for a key the client had nothing to put in.

**3. THE ACCEPTANCE RULE, WHICH IS THE MECHANISM AND NOT A DETAIL OF IT.**

A marker is a CLAIM ABOUT HOW THIS NUMBER WAS OBTAINED, and it is false the instant the number stops
being the one the route proposed. So a client stores the accepted number ALONGSIDE its marker, and at
SAVE TIME re-checks that stored number against what is in the box, CHARACTER FOR CHARACTER. A marker
reaches the request body only when the box still holds the exact string the route wrote. Nothing is
trusted about HOW the box came to differ — keystroke, paste, clear, or a future third writer —
because the check is on the VALUE, not on the event.

The one case value-equality cannot see is a person typing the identical digits back by hand, so the
acceptance is ALSO forgotten from the box's own ``onChange``: that is a hand-typed number and must be
recorded as one. Two guards, because each is blind where the other sees.

This server cannot enforce any of that — see §THE HONEST LIMIT — which is exactly why it is written
down in the file both clients' authors will be reading when they compose the body.

**4. Say that the grid control needs a connection, and offer the thing that does not.**

``POST /media/analyze-measurement`` is network-only: no queue, no outbox, no retry. On failure both
clients already say "enter it manually", which is honest and actionable. What is still missing is
client-side: neither control's hint text warns that it needs a connection, and neither points at the
offline ``PhotoMeasure`` path, which is on the same device, needs no network, measures the same
dimension and reports an error bar. In a courtyard with no signal the app currently offers the
failing option and hides the working one.

--------------------------------------------------------------------------------------------------
THE RECORD HALF — where the false attribution was written, and what is there now
--------------------------------------------------------------------------------------------------

``records.merge_field_provenance`` is where the record asserted that a named human had measured a
model's guess. It now pops the marker off the save body and merges :func:`method_stamps` into the
stamp it was already writing::

    stamps = method_stamps(new_data.pop(MARKER_BODY_KEY, None), fields=new_data.keys())
    ...
    for field, value in new_data.items():
        ...
        provenance[field] = stamp | stamps.get(field, {})   # inside the existing changed-fields loop

Four properties of those two lines, each of which is load-bearing and each of which has a test:

* the ``pop`` is what keeps a non-column out of the Prisma ``data`` dict. ``clean_data`` only drops
  ``None``, so a marker that is actually sent would otherwise reach
  ``db.productdocumentation.create(data=...)`` as an unknown column — a 500 on the save, not a
  validation error a researcher could act on;
* :data:`MARKER_BODY_KEY` is also in ``records.PROVENANCE_SKIP_FIELDS``, so the marker object can
  never be attributed as though it were a field somebody filled in. That entry is belt and braces —
  the pop runs first — and it is the defence for a future caller that merges provenance without
  popping;
* the merge sits INSIDE the existing changed-fields loop, which never fires for an unchanged value.
  Both web forms re-send every dimension on every save, so a client that blanket-sent ``TYPED`` for
  every box would otherwise launder every accepted machine measurement on a record into an apparent
  human one the next time somebody fixed a typo in ``remarks``;
* ``stamp | stamps.get(...)`` and not the reverse, so the method joins ``{by, byName, at}`` instead of
  replacing them. Stripping the name would delete the most useful fact on the row.

:func:`method_stamps` defaults every dimension column it is asked about to :data:`UNRECORDED`, so a
save from a client that has not implemented its half writes an explicit "nobody recorded a method"
rather than nothing — honest, distinguishable, and never the false human claim. Expect a burst of
those from the installed fleet and read them as the design.

**THE KEY THAT HAD TO BE ADDED IN A FILE THIS MODULE DOES NOT OWN.** ``access.REVISION_SKIP_FIELDS``
lists :data:`MARKER_BODY_KEY`, and it is the only entry in that set that is not a column. Re-check
with ``grep -n "MARKER_BODY_KEY" backend/app/services/access.py``.

WHAT IT PREVENTS. ``guard_record_edit`` runs ``record_revision`` on the raw ``data`` BEFORE
``merge_field_provenance`` pops the key. Without the entry, every marker-bearing PATCH diffs
``measurementMethods`` from ``None`` — ``get_value(record, "measurementMethods")`` is None,
``values_match(None, {...})`` is False — and writes a ``RecordRevision``, which breaks
``record_revision``'s own contract ("No-op when nothing meaningful changed") and fills an admin audit
trail with an edit nobody made. Moving the pop earlier is not available: the merge must still see the
marker. The marker is a hint about how a value was produced, not a value.

--------------------------------------------------------------------------------------------------
WHAT IS DELIBERATELY NOT IN THIS CHANGE, SO NOBODY READS ITS ABSENCE AS A DECISION
--------------------------------------------------------------------------------------------------

* **THE READ SIDE STILL PRINTS A BARE NUMBER.** ``record_fields.py``'s "Dimensions (LxBxH in)" cell
  is built by ``dims(...)`` and says nothing about method, so a stored ``VISION_MODEL`` stamp is
  visible only to somebody reading ``extraMetadata``. That placement matters more than it looks:
  the field-contributions panel is permission-gated, while the dimension cell is gated on nothing
  beyond being allowed to see the record — it is what the reviewer, the officer and the CSV
  download all read. Whoever adds it should print a clause for ``VISION_MODEL`` and
  ``PHOTO_GEOMETRY`` ONLY: appending "method not recorded" to the majority of rows in this database
  is noise that trains a reader to skip the clause on the row where it matters.
* **THE QUEUED WRITE PATH HAS NO HUMAN IN IT AT ALL.** ``media_queue._measurement_update_data``
  writes the model's ``lengthInches`` / ``breadthInches`` straight onto the record whenever the
  column is empty, without going through ``merge_field_provenance`` — so that number carries no
  stamp whatsoever, not even a false one. It is the sharpest form of this defect and it is
  untouched here, because closing it is a behaviour change to the media pipeline rather than a
  provenance one. See the note at that function.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any

#: What every provenance field holds when nobody recorded it, spelled out in that word.
#:
#: ONE SPELLING, EVERYWHERE, ON PURPOSE. Two spellings of one discipline is how an export prints
#: "UNRECORDED" in one column and "UNKNOWN" in the next and a reader concludes they mean different
#: things. If another honest-unknown ever lands in this backend, import this rather than retyping it.
UNRECORDED = "UNRECORDED"

#: The key a client puts its per-field markers under, on a product or tool request body.
MARKER_BODY_KEY = "measurementMethods"


class MeasurementMethod(str, Enum):
    """How a dimension came to be known. Four values, and the fourth is the honest absence of an answer.

    NOT A RANKING, and not a quality score. ``PHOTO_GEOMETRY`` with a well-placed scale reference is
    more accurate than a tape held crooked, and a ``TYPED`` number from somebody with callipers beats
    both. What distinguishes them is what a LATER READER can do: re-derive it, ask the person, or
    neither.
    """

    #: A person read a scale and typed the number. Unverifiable and perfectly ordinary — this is what
    #: the overwhelming majority of dimensions in this repository are, and it needs no ceremony.
    TYPED = "TYPED"

    #: Deterministic arithmetic over marks a person placed on a photograph: ``PhotoMeasure`` on the
    #: handset and its twin on the web. Same marks, same number, on either surface, for ever —
    #: which is why it may fill a field with no acceptance step. See :attr:`technique` for which of
    #: the two geometries produced it.
    PHOTO_GEOMETRY = "PHOTO_GEOMETRY"

    #: A vision model looked at a photograph and estimated. Irreproducible by construction: the same
    #: image through the same endpoint next month may answer differently, legitimately, because the
    #: provider swapped a checkpoint. Requires acceptance.
    VISION_MODEL = "VISION_MODEL"

    #: Nobody wrote down how this number came to be known. Every dimension stored before this module
    #: existed is this, including the ones a model produced. NEVER a synonym for TYPED.
    UNRECORDED = UNRECORDED

    @property
    def requires_acceptance(self) -> bool:
        """Must a named person have accepted this number before it may sit in a documented field?

        True for :attr:`VISION_MODEL` alone, and the asymmetry is the entire point of this module.
        A typed number is a person's own act — there is nothing to accept, they did it. Arithmetic is
        re-runnable from the marks, so a reader who doubts it can check it. A model's estimate is
        neither, so the only thing that can stand behind it is somebody's signature.

        :attr:`UNRECORDED` answers False, which is not permission — it is the truth that a row nobody
        recorded a method for cannot also have recorded an acceptance. Do not turn this into a
        validation gate on the write path: it would reject every legacy row on its next save.
        """
        return self is MeasurementMethod.VISION_MODEL

    @property
    def reproducible(self) -> bool | None:
        """Would repeating the method on the same evidence give the same number? ``None`` where the
        question does not apply.

        Three-valued on purpose. PHOTO_GEOMETRY is True and VISION_MODEL is False, but TYPED is
        neither — a person reading a tape is not a computation, and answering True would claim
        determinism nobody measured while answering False would imply the number is untrustworthy,
        which it is not. ``None`` is "the question does not apply to this method", and a caller that
        wants a two-way branch should ask :attr:`requires_acceptance` instead.
        """
        if self is MeasurementMethod.PHOTO_GEOMETRY:
            return True
        if self is MeasurementMethod.VISION_MODEL:
            return False
        return None


#: The two geometries the offline photo-measure path can use, named here in the spelling the clients
#: send so all three surfaces can be found in one search — Android's ``TECHNIQUE_SCALE`` /
#: ``TECHNIQUE_RECTIFIED`` and the web's ``"SCALE" | "RECTIFIED"`` union. Re-check both halves with::
#:
#:     grep -rn "RECTIFIED" android/app/src/main/java/com/fieldrepository/app frontend/
TECHNIQUE_SCALE = "SCALE"
TECHNIQUE_RECTIFIED = "RECTIFIED"

#: A DIFFERENT AXIS from :class:`MeasurementMethod`, which is why a technique is a separate field
#: rather than two more enum members: both are ``PHOTO_GEOMETRY`` as far as a reader of the record is
#: concerned, and the distinction between them matters only to somebody re-deriving the number.
GEOMETRY_TECHNIQUES: frozenset[str] = frozenset({TECHNIQUE_SCALE, TECHNIQUE_RECTIFIED})

#: The columns on ``ProductDocumentation`` / ``ToolDocumentation`` that hold a documented dimension,
#: and therefore the only fields a method marker may name.
#:
#: ENUMERATED, so a marker cannot stamp a method onto an unrelated column. Without this a client could
#: send ``{"costOfMaking": {"method": "TYPED"}}`` and have it written into the provenance blob, where
#: it would read as though this system had an opinion about how a cost was arrived at. A marker naming
#: anything outside this set is REFUSED at the schema by :func:`marker_body_problems` and dropped in
#: silence by :func:`method_stamps` — two layers, on purpose, and that function's docstring says why
#: the inner one may not raise.
#:
#: ALL THREE ARE REAL COLUMNS ON BOTH TABLES. ``ToolDocumentation.heightInches`` is the newest of them
#: (migration ``20260913120100``) and is the reason this set can be shared by both models instead of
#: being two lists that can disagree. Re-check with::
#:
#:     grep -n "heightInches" backend/prisma/schema.prisma
#:
#: WHAT IS DELIBERATELY OUT, so nobody reads the shared set as an invitation: ``ToolDocumentation``
#: also has ``height``, ``width``, ``thickness``, ``weight`` and ``radius``. ``thickness``, ``weight``
#: and ``radius`` are ordinary typed inputs with no grid control and no photo-measure control pointed
#: at them, and there is nothing to say about how they were measured.
#:
#: ``height`` AND ``width`` ARE THE INTERESTING PAIR, AND THEY STAY OUT FOR A REASON THAT GOT
#: STRONGER RATHER THAN WEAKER. This comment used to call ``height`` "the UNIT-LESS legacy column"
#: whose unit nothing in the database could say. That is still true of every row saved before
#: 2026-09-15 and true of nothing saved after it: the tool form now labels ``height`` "Height (cm)"
#: and ``width`` "Width (cm)", and each is filled BY CONVERSION from its inches partner
#: (``height`` <-> ``heightInches``, ``width`` <-> ``breadthInches``) at 2.54 cm to the inch.
#:
#: A DERIVED NUMBER MUST NOT CARRY A MARKER OF ITS OWN. When a researcher accepts a grid reading into
#: ``heightInches``, the marker belongs to ``heightInches`` — that is the box the model measured —
#: and the centimetre value beside it is arithmetic on that reading, not a second measurement. Giving
#: it its own stamp would put a vision model's name against a number no model ever produced, and two
#: stamps that could disagree about one measurement is exactly the fiction this module exists to
#: refuse. So the cm boxes carry no provenance, and a client aiming a marker at one is aiming at the
#: wrong column: :func:`marker_body_problems` tells it so by name.
DIMENSION_FIELDS: frozenset[str] = frozenset({"lengthInches", "breadthInches", "heightInches"})

#: The key the PROVIDER answers under: the measurement prompt asks Gemini for "confidence from 0 to 1",
#: so that is the word in its JSON.
PROVIDER_CONFIDENCE_KEY = "confidence"

#: The key the same number travels under on the wire and on a marker. Renamed deliberately rather than
#: passed through as ``confidence``: a client reading ``confidence`` beside a dimension will put
#: "confidence: 80%" in front of a researcher, and this number has never been calibrated against a
#: tape measure. The longer name is the label, and it is the same reason ``confidenceIsCalibrated`` is
#: on the response — the photo-geometry path's ``uncertainty`` IS a propagated error bar and the two must
#: not look alike on a wire that carries both.
MARKER_CONFIDENCE_KEY = "selfReportedConfidence"


def self_reported_confidence(analysis: Any, *, key: str = PROVIDER_CONFIDENCE_KEY) -> float | None:
    """The model's own confidence, if it gave one on the 0–1 scale the prompt asked for. Else None.

    ``key`` selects which spelling to read — :data:`PROVIDER_CONFIDENCE_KEY` for a provider's own JSON,
    :data:`MARKER_CONFIDENCE_KEY` for a marker coming back from a client. ONE VALIDATOR AND TWO KEY
    NAMES, rather than two functions: the checks below are the whole reason a client cannot store a
    confidence of 7, and a second reader written later would not have them.

    **SELF-REPORTED AND UNCALIBRATED, AND THIS FUNCTION CANNOT CHANGE THAT.** The measurement prompt
    asks Gemini for "confidence from 0 to 1", so what comes back is a model's claim about itself.
    Whether it discriminates a confident read from a guess is UNMEASURED — nothing in this repository
    calibrates it, tests it against a tape measure, or thresholds on it. It travels because a
    researcher deciding whether to trust a proposal is better off seeing it than not, and it travels
    labelled (``confidenceIsCalibrated: false``) so nobody downstream builds a gate on it by accident.

    A MISSING CONFIDENCE STAYS MISSING. The honest-unknown rule: no default, no "0.5 because it
    answered at all", no inference from whether the value parsed. A number the model did not give is a
    number this system does not have.

    Out-of-range values are DROPPED RATHER THAN CLAMPED. A model answering 1.5 has not told us it is
    completely certain — it has failed to answer the question asked, and clamping to 1.0 would
    manufacture the loudest possible claim out of a malformed one. Rejected too: booleans (``True`` is
    an ``int`` in Python and would arrive as a confidence of 1.0), NaN and infinity.
    """
    if not isinstance(analysis, Mapping):
        return None
    raw = analysis.get(key)
    # A model that wrote its number as a string is still a model that gave a number; reading it is not
    # inventing it. Anything else — a word, a list, None — is no answer.
    if isinstance(raw, str):
        try:
            raw = float(raw.strip())
        except ValueError:
            return None
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None
    value = float(raw)
    if not math.isfinite(value) or not (0.0 <= value <= 1.0):
        return None
    return value


@dataclass(frozen=True, slots=True)
class MeasurementProvenance:
    """How one measurement came to be known — the value object both halves of the wire speak.

    Frozen because it is a statement about something that has already happened. A mutable provenance
    is one a later line of code can quietly improve, and the improvement nobody notices is a provider
    name corrected to the one that "must have" produced a reading.
    """

    method: MeasurementMethod
    #: Which service produced it, or :data:`UNRECORDED`. ``UNRECORDED`` rather than None so a reader
    #: never has to decide what a null means.
    provider: str = UNRECORDED
    #: The model id, or :data:`UNRECORDED`. Without it, a systematic error found in six months (a
    #: provider silently swapping a checkpoint) cannot be traced to the material it damaged.
    model_id: str = UNRECORDED
    #: See :func:`self_reported_confidence`. None means the model reported none.
    self_reported_confidence: float | None = None
    #: For :attr:`MeasurementMethod.PHOTO_GEOMETRY` only: which geometry. See
    #: :data:`GEOMETRY_TECHNIQUES`.
    technique: str | None = None

    @property
    def requires_acceptance(self) -> bool:
        return self.method.requires_acceptance

    @property
    def reproducible(self) -> bool | None:
        """See :attr:`MeasurementMethod.reproducible`. Proxied so a caller holding a provenance can ask
        without reaching through to the enum — the offline geometry path holds one of these and nothing
        else, and having to know that the answer lives one attribute deeper is how a caller ends up
        testing ``provenance.method == "PHOTO_GEOMETRY"`` by hand instead."""
        return self.method.reproducible

    def payload(self) -> dict[str, Any]:
        """The keys ``POST /media/analyze-measurement`` adds to its response.

        ``requiresAcceptance`` is present on every response including the failures, and that is
        deliberate: it is a statement about what KIND of thing this endpoint produces, not about one
        reading. A client branching on it needs the key to exist before it knows whether a number
        arrived, and a key that appears only on success is one every client will forget to check.

        ``confidenceIsCalibrated`` is hard ``False`` and there is no code path that sets it True. It
        exists so the number beside it can never be mistaken for a measured error bar — which is what
        ``photoMeasure``'s ``uncertainty`` is, and the two must not look alike on a wire that carries
        both. Do not add a branch here; a calibrated flag is something somebody MEASURES against a
        tape, not something a caller passes in.
        """
        return {
            "method": self.method.value,
            "provider": self.provider,
            "modelId": self.model_id,
            MARKER_CONFIDENCE_KEY: self.self_reported_confidence,
            "confidenceIsCalibrated": False,
            "requiresAcceptance": self.requires_acceptance,
            "methodMarker": self.marker(),
        }

    def marker(self) -> dict[str, Any]:
        """What a client echoes back, unchanged, when it saves the value it was given.

        Deliberately the same shape a client composes by hand for a typed or geometry measurement, so
        there is one marker format and not one-per-origin. Keys whose answer is not a known fact are
        OMITTED rather than sent as :data:`UNRECORDED`: a ``provider`` on a typed measurement is a
        question that does not apply, and storing the word on every hand-typed dimension would fill
        the provenance blob with answers to questions nobody asked.
        """
        marker: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            marker["provider"] = self.provider
        if self.model_id != UNRECORDED:
            marker["modelId"] = self.model_id
        if self.self_reported_confidence is not None:
            marker[MARKER_CONFIDENCE_KEY] = self.self_reported_confidence
        if self.technique:
            marker["technique"] = self.technique
        return marker

    def stamp(self) -> dict[str, Any]:
        """What is stored beside ``{by, byName, at}`` in ``extraMetadata.fieldProvenance[field]``.

        ``method`` is always present — a stamp whose method had to be inferred from which other keys
        exist is the absence this module was written to end. The rest appear only when they are facts,
        for the reason :meth:`marker` gives. The ``method``-prefixed spelling keeps them from colliding
        with any field name a record already has, and makes the whole set greppable in one search when
        somebody asks which rows carry a machine-produced dimension.
        """
        stamp: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            stamp["methodProvider"] = self.provider
        if self.model_id != UNRECORDED:
            stamp["methodModelId"] = self.model_id
        if self.self_reported_confidence is not None:
            stamp["methodConfidence"] = self.self_reported_confidence
        if self.technique:
            stamp["methodTechnique"] = self.technique
        return stamp


#: The provenance of a dimension nobody recorded a method for. Every row written before this module
#: existed, and every save from a client that has not implemented its half yet.
UNRECORDED_PROVENANCE = MeasurementProvenance(method=MeasurementMethod.UNRECORDED)


def vision_model_provenance(analysis: Any, *, provider: str, model_id: str) -> MeasurementProvenance:
    """The provenance of a reading ``POST /media/analyze-measurement`` just produced.

    ``provider`` and ``model_id`` are keyword-only and have no defaults, so a caller with nothing to
    say has to write :data:`UNRECORDED` deliberately. A defaulted provider is the one that silently
    becomes wrong when a second provider is added to the chain — and this backend already runs a
    provider chain for speech-to-text, so that day is not hypothetical.
    """
    return MeasurementProvenance(
        method=MeasurementMethod.VISION_MODEL,
        provider=(provider or "").strip() or UNRECORDED,
        model_id=(model_id or "").strip() or UNRECORDED,
        self_reported_confidence=self_reported_confidence(analysis),
    )


def provenance_of_marker(marker: Any) -> MeasurementProvenance:
    """Read a client-sent marker. Anything unreadable becomes :data:`UNRECORDED`, never ``TYPED``.

    **THE DIRECTION OF THE FALLBACK IS THE WHOLE SAFETY PROPERTY.** A malformed marker resolving to
    TYPED would let a client turn a model's estimate into an apparent human measurement by sending
    slightly wrong JSON — the defect this module exists to remove, reachable by accident. Resolving to
    UNRECORDED instead loses information the client meant to send, which is recoverable, visible, and
    never a false claim.

    Note that a marker naming a method IS taken at its word, including ``TYPED``, and the module
    docstring says why: it is a claim on a request body, exactly as trustworthy as the number it
    describes. The fallback protects against malformed input, not against a client that lies.

    Case-sensitive on purpose. ``"typed"`` is not a token any part of this system writes, and
    accepting it would be the sort of kindness that turns an unrecognised value into an assertion
    about how somebody measured something.
    """
    if not isinstance(marker, Mapping):
        return UNRECORDED_PROVENANCE
    raw_method = marker.get("method")
    if not isinstance(raw_method, str):
        return UNRECORDED_PROVENANCE
    try:
        method = MeasurementMethod(raw_method)
    except ValueError:
        return UNRECORDED_PROVENANCE

    def _text(key: str) -> str:
        value = marker.get(key)
        return value.strip() if isinstance(value, str) and value.strip() else UNRECORDED

    technique = marker.get("technique")
    return MeasurementProvenance(
        method=method,
        provider=_text("provider"),
        model_id=_text("modelId"),
        # Read under the MARKER's spelling, through the same validator the provider's own answer goes
        # through — so a client cannot store a confidence of 7, of "high", or of true by sending it
        # back. Reading ``confidence`` here instead is a real bug with a test on it: the endpoint
        # hands out ``selfReportedConfidence``, so a client echoing the marker back verbatim would
        # have its confidence silently dropped and the stored stamp would lose the only number on it.
        self_reported_confidence=self_reported_confidence(marker, key=MARKER_CONFIDENCE_KEY),
        technique=technique if technique in GEOMETRY_TECHNIQUES else None,
    )


#: Every key a marker may carry a FACT under, in the spelling :meth:`MeasurementProvenance.marker`
#: writes and a client echoes back. Named for the refusal sentences, which have to be able to say
#: what was allowed instead of only what was wrong.
#:
#: NOT A CLOSED SET ON THE WIRE, and :func:`marker_body_problems` deliberately does not refuse a key
#: outside it — see the "OPEN KEYS, CLOSED VALUES" paragraph there for the version-skew argument.
MARKER_FACT_KEYS: tuple[str, ...] = (
    "method",
    "provider",
    "modelId",
    MARKER_CONFIDENCE_KEY,
    "technique",
)


def marker_body_problems(markers: Any, *, present_fields: Any) -> list[str]:
    """Everything wrong with a client-sent ``measurementMethods`` body, one finished sentence each.

    An empty list means the body is storable exactly as sent. PURE, like everything else here: the
    caller decides what a problem costs. :mod:`app.schemas.records` raises them as a 422 on all four
    record schemas; nothing on the save path calls this.

    ``present_fields`` is the dimension columns THIS request also carries a value for. The schema
    passes the non-null dimensions off the same model instance.

    ── WHY THIS EXISTS BESIDE :func:`provenance_of_marker`, WHICH ALREADY "HANDLES" ALL OF IT ───────

    Because they answer different questions at different moments:

    * :func:`provenance_of_marker` runs INSIDE A SAVE, on every path, including saves whose marker
      never came off a request body. It must never raise — failing a record edit over a provenance
      hint trades a real loss for a cosmetic one — so it DEGRADES: an unreadable marker becomes
      :data:`UNRECORDED`, never ``TYPED``, and the direction of that fallback is the safety property
      of this whole module. None of that changes, and this function does not weaken it.
    * this one runs at the API BOUNDARY, before anything is written, where the alternative to a
      refusal is not a safe default but A SILENT LIE OF OMISSION. A researcher presses Accept on a
      vision-model reading, the client sends a marker with a typo in the method name, the degrade
      writes ``UNRECORDED``, and the row is now indistinguishable from one saved by a client that
      never implemented any of this. Nobody is told — not in the client, not in the record — and an
      accepted machine reading is recorded as nothing.

    So the boundary refuses and the save path degrades, and the degrade is the second line of defence
    rather than the only one.

    ── WHAT A REFUSAL COSTS, STATED HERE BECAUSE IT IS NOT FREE ────────────────────────────────────

    A ``ValueError`` out of a pydantic validator is a 422 on the WHOLE request, and neither client
    queue retries a 4xx — the web's ``saveOrQueue`` will not queue one ("the server saw it and said
    no") and Android's outbox parks it. So a client bug in the marker throws away a form the
    researcher filled in, and offline it is not retried either. That is why every sentence below names
    the exact key, the exact value, and what to send instead: the cost is only worth paying while a
    client author can fix it from the message without opening this repository.

    THE REFUSALS ARE THE SET THAT EXISTS ON THE DAY THE KEY BECAME SENDABLE, AND NO NEW ONE MAY BE
    ADDED LATER. Once a client ships, its body may be composed OFFLINE and posted a fortnight later —
    Android's outbox, the web's queue — so a save that meets a new rule may predate that rule, and a
    handset is allowed to be that far behind the server. Tightening this function, renaming a
    :class:`MeasurementMethod` member, narrowing :data:`DIMENSION_FIELDS` or requiring a key that is
    optional today all widen this set from a shipped client's point of view, and each strands a
    filled-in form that a queue can only replay into the same refusal. WHAT TO DO INSTEAD with
    something newly wrong: degrade it in :func:`provenance_of_marker`, which already turns anything
    unreadable into :data:`UNRECORDED` and never into ``TYPED``, and tell the client author somewhere
    that is not a 422 on a researcher's filled-in form.

    ── OPEN KEYS, CLOSED VALUES ──────────────────────────────────────────────────────────────────

    A key inside a marker that is not in :data:`MARKER_FACT_KEYS` is IGNORED, not refused, and it is
    the one accept-and-drop deliberately left here. A marker is echoed back VERBATIM as the endpoint
    handed it out, so a server that later adds a key to :meth:`MeasurementProvenance.marker` would
    otherwise 422 every client echoing it at an older instance mid-deploy — and the web and the API
    deploy separately, so mid-deploy skew is the ordinary case. The VALUES of the keys this system
    does define are closed: each either lands in the stamp or is refused here, and none is silently
    dropped at the boundary.
    """
    allowed_fields = ", ".join(sorted(DIMENSION_FIELDS))
    if not isinstance(markers, Mapping):
        # PARENTHESISED, not two strings side by side in the list: an implicit concatenation inside a
        # collection literal is one missing comma away from being two refusals instead of one, and
        # ruff refuses it (ISC004) for exactly that reason.
        return [
            (
                f"{MARKER_BODY_KEY} must be an object keyed by dimension name, such as "
                '{"lengthInches": {"method": "TYPED"}}.'
            )
        ]

    present = {field for field in (present_fields or ()) if field in DIMENSION_FIELDS}
    known_methods = {m.value for m in MeasurementMethod}
    problems: list[str] = []

    for field in sorted(markers):
        marker = markers[field]
        where = f"{MARKER_BODY_KEY}.{field}"

        # ENUMERATED FIRST, because every check under this one reads the marker as a statement ABOUT
        # a documented dimension, and there is no such statement to make about ``costOfMaking``.
        # Left to ``method_stamps`` this is a silent drop; refused here it names the three columns a
        # method may describe, which is the whole answer a client author needs.
        #
        # THE CASE THAT IS NOT HYPOTHETICAL IN THIS REPOSITORY IS ``height``. Until migration
        # 20260913120100 a tool had no ``heightInches``, so a grid-measured tool height had nowhere
        # documented to go and the unit-less ``height`` box was the only column that would take it. A
        # client written against that world aims its marker there, and this is what tells it to move
        # the value one box over instead of dropping the method in silence.
        #
        # THE SECOND CLIENT THAT AIMS HERE BY MISTAKE IS THE NEW ONE. ``height`` and ``width`` became
        # the CENTIMETRE partners of ``heightInches`` and ``breadthInches`` on 2026-09-15, so a form
        # that accepts a machine reading now writes TWO boxes and it would be an easy slip to stamp
        # both. The cm box is arithmetic on the inches reading, not a reading of its own; the refusal
        # below names the three columns a method may describe, which is the whole answer.
        if field not in DIMENSION_FIELDS:
            problems.append(
                f"{MARKER_BODY_KEY} names {field}, which is not a documented dimension. A "
                f"measurement method may only describe {allowed_fields}."
            )
            continue

        if not isinstance(marker, Mapping):
            problems.append(
                f"{where} must be an object stating a method, such as "
                '{"method": "TYPED"} for a number somebody typed.'
            )
            continue

        raw_method = marker.get("method")
        if not isinstance(raw_method, str) or raw_method not in known_methods:
            # Case-sensitive, for the reason :func:`provenance_of_marker` gives: accepting "typed"
            # would turn an unrecognised token into an assertion about how somebody measured
            # something. Refused here it costs the client one corrected string instead.
            problems.append(
                f"{where} states a measurement method this server does not know: {raw_method!r}. "
                f"Send one of {', '.join(sorted(known_methods))}."
            )
            continue
        method = MeasurementMethod(raw_method)

        # A METHOD IS A STATEMENT ABOUT A NUMBER, SO THE NUMBER HAS TO BE IN THE SAME REQUEST.
        # ``merge_field_provenance`` narrows the stamps to the columns the save is writing, so a
        # marker for a dimension this body does not carry describes nothing that is happening here
        # and is dropped — after the researcher pressed Accept on it.
        #
        # NOTE WHAT THIS DOES NOT REFUSE: a dimension sent with an UNCHANGED value. That key is
        # present, so it passes here, and the changed-fields loop inside ``merge_field_provenance``
        # is what declines to re-stamp it. That is the anti-laundering rule and it belongs there,
        # where the stored row is in scope; a schema cannot see the stored row and must not pretend
        # to.
        if field not in present:
            problems.append(
                f"{where} states how {field} was measured, but this request sends no value for "
                f"{field}. Send the measurement in the same request, or leave the method out."
            )
            continue

        technique = marker.get("technique")
        if technique is not None:
            if technique not in GEOMETRY_TECHNIQUES:
                problems.append(
                    f"{where} names a photo-measurement technique this server does not know: "
                    f"{technique!r}. Send {' or '.join(sorted(GEOMETRY_TECHNIQUES))}, or leave "
                    f"technique out."
                )
            elif method is not MeasurementMethod.PHOTO_GEOMETRY:
                # Stored, this puts ``methodTechnique: "SCALE"`` beside ``method: "VISION_MODEL"``
                # in an audit trail: a geometry that did not happen, asserted next to the method
                # that did. Nothing in this repository composes that — ``vision_model_provenance``
                # never sets a technique, and the offline path sets one only with PHOTO_GEOMETRY.
                problems.append(
                    f"{where} carries a technique on a {method.value} reading, and a technique says "
                    f"how a PHOTO_GEOMETRY measurement was derived. Leave it out unless the method "
                    f"is PHOTO_GEOMETRY."
                )

        # AN EXPLICIT NULL IS AN ABSENCE, NOT A CLAIM, so only a value that is present and
        # unreadable is refused — the honest-unknown rule :func:`self_reported_confidence` states,
        # applied to the refusal rather than to the parse. ``payload()`` sends this key as null when
        # the model reported no confidence, and the specification tells clients to echo the marker
        # back verbatim, so a server that refused the null would refuse its own answer relayed back
        # to it — breaking exactly the clients that followed the instruction.
        if marker.get(MARKER_CONFIDENCE_KEY) is not None and (
            self_reported_confidence(marker, key=MARKER_CONFIDENCE_KEY) is None
        ):
            problems.append(
                f"{where} carries a {MARKER_CONFIDENCE_KEY} this server cannot read: "
                f"{marker.get(MARKER_CONFIDENCE_KEY)!r}. Send a number from 0 to 1, or leave the "
                f"key out."
            )

    return problems


def method_stamps(markers: Any, *, fields: Any = None) -> dict[str, dict[str, Any]]:
    """``{column: stamp}`` for the dimension columns a save touches. The record half's one call.

    ``fields`` narrows the result to the columns actually being written — pass the keys of the update
    data. Omit it and every column in :data:`DIMENSION_FIELDS` named by a marker is returned.

    **EVERY DIMENSION COLUMN IN THE RESULT CARRIES A METHOD, INCLUDING THE ONES NOBODY DECLARED.** A
    save that mentions ``lengthInches`` and sends no marker for it gets ``{"method": "UNRECORDED"}``
    rather than nothing, because a reader must be able to see a state machine instead of inferring one
    from an absence. An absent stamp and a stamp reading UNRECORDED would otherwise be
    indistinguishable from a row written before any of this existed, and the whole point is to be able
    to tell.

    Markers naming anything outside :data:`DIMENSION_FIELDS` are dropped in silence rather than
    refused: this runs inside a save, and failing somebody's record edit over a stray key in a
    provenance hint would trade a real loss for a cosmetic one. The boundary refuses the same key by
    name — see :func:`marker_body_problems` for why both layers exist.
    """
    sent = markers if isinstance(markers, Mapping) else {}
    if fields is None:
        touched = {field for field in sent if field in DIMENSION_FIELDS}
    else:
        touched = {field for field in fields if field in DIMENSION_FIELDS}
    return {field: provenance_of_marker(sent.get(field)).stamp() for field in sorted(touched)}
