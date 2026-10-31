"""The transcript pipeline's pure half: turns, translation, renderers, citations, budget.

Part of the shared transcription package, so byte-identical in both portals and listed in
``shared/transcription.sha256``. Like the rest of the package it imports no FastAPI, no Prisma and
no portal module: the Prisma store (``services/transcript_store.py``), the orchestration
(``services/transcript_pipeline.py``) and the routes (``api/routes/transcripts.py``) call into it.

Design: ``C:/dev/IIT/docs/transcription/DESIGN_2026-10-31_Scribe-Gemini-Pipeline.md``.
API contract: ``shared/transcript-contract.json``.

Nothing in here logs transcript text or a key.
"""

PROMPT_VERSION = "transcript-pipeline/1"
