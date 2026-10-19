"""Questionnaire v2: sessions, recordings, computed coverage, canonical answers.

DESIGN: ``C:/dev/IIT/docs/questionnaire/DESIGN_2026-10-18.md``. Contract: BUILD_PLAN section 1 and
``shared/questionnaire-contract.json``. Every route lives in ``app/api/routes/questionnaire_v2.py``
and answers 404 while ``QUESTIONNAIRE_V2`` is false, so the old routes behave exactly as before.

Modules:

* ``common``   errors in the ``{detail:{code,message}}`` shape, time and row helpers;
* ``live``     the live-media filter every reader uses;
* ``locks``    sorted advisory locks for /complete;
* ``titles``   the inputs of a generated title or file name, read from the database;
* ``sessions`` create, patch, participants, researchers, join, finish, merge, continue, delete;
* ``coverage`` the take view, check, grid and snapshot;
* ``recordings`` presign, resume, complete, patch, decide, primary, remove, restore, sha verify;
* ``answers``  per-item answer writes, prefill, proposals;
* ``inbox``, ``record``, ``corpus``, ``legacy_resolver``.
"""
