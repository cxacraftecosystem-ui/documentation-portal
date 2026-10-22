"""The questionnaire, ONE package for both portals (C:/dev/IIT/docs/questionnaire/UNIFY_2026-10-21.md).

BYTE-IDENTICAL in designer-portal and documentation-portal: ``shared/questionnaire.sha256`` lists
every file and ``tests/test_questionnaire_parity.py`` fails either portal's CI when a copy drifts or
when the two manifests differ. Edit it in designer-portal and run
``python scripts/sync_questionnaire.py --to ../documentation-portal``.

The package never imports ``app.core``, ``app.api`` or ``app.services``: what a portal must supply
(the database, settings, roles, consent, storage, transcription, workshop access, naming) is reached
through ``ports`` and implemented by each portal's ``app/services/questionnaire_ports.py``.

Built from Field Repository's questionnaire v2 (DESIGN_2026-10-18.md; contract
``shared/questionnaire-contract.json``). Field Repository serves it at ``/api/questionnaire`` while
``QUESTIONNAIRE_V2`` is on; designer-portal mounts it in U1 and stays on its own forms until then.

Modules:

* ``common``     errors in the ``{detail:{code,message}}`` shape, time and row helpers;
* ``errors``     the one door to FastAPI's ``HTTPException``;
* ``ports``      the one door to portal code;
* ``authoring``  who may change a form, a session, a take (the author rule, content admins);
* ``answer_lock`` the one answer-lock rule (FORBIDDEN with code ANSWER_LOCKED, per item);
* ``subjects``   the subject key, ``A:<artisanId>`` or ``R:<respondentId>``;
* ``respondents`` guest respondents: add, edit, retract, register as an artisan;
* ``bindings``   forms bound to workshops, type defaults, and the start sheet's form;
* ``kinds``      form kinds and the three section-override states;
* ``live``       the live-media and live-session filters every reader uses;
* ``locks``      sorted advisory locks for /complete;
* ``titles``     the inputs of a generated title or file name, read from the database;
* ``sessions``   create, patch, participants, researchers, join, finish, merge, continue, soft delete;
* ``coverage``   the take view, check, grid (typed or recorded) and snapshot;
* ``recordings`` presign, resume, complete, patch, decide, primary, remove, restore, history;
* ``answers``    per-item answer writes (artisans and guests), prefill, proposals, revisions;
* ``schemas``    the contract's request and response models;
* ``readers``    what tasks, completeness, stage counts, exports and the annexure read (C10);
* ``richtext``   an answer's optional rich text and its canonical plain text (C8);
* ``structure``  a form's sections and questions for the session screens;
* ``inbox``, ``record``, ``corpus``, ``merge``, ``review``, ``legacy_resolver``, ``shims_fr``, ``shims_dr``.
"""
