"""The shared transcription service: one package, byte-identical in both portals.

designer-portal (DesignWorkshop) and documentation-portal (FieldRepository) each carry this
directory at ``backend/app/transcription/``. ``shared/transcription.sha256`` lists every file's
digest and ``tests/test_transcription_parity.py`` fails when either copy drifts from it. Edit the
designer-portal copy, then run ``python scripts/sync_transcription.py`` from that repo to copy it
across and rewrite both manifests.

Nothing in here imports FastAPI, Prisma or a portal module. A portal hands the package:

* the provider keys it resolved (``contract.ApiKey``),
* a ``ChainState`` snapshot of cooldowns, budgets and finished chunks read from its own database,
* a ``ChainHooks`` object that writes usage, cooldowns and chunks back as they happen,
* and, for queued recordings, a consent verdict (``consent.ConsentVerdict``).

Design: ``C:/dev/IIT/docs/transcription/TRANSCRIPTION_SERVICE_2026-10-18.md``.
"""

PACKAGE_VERSION = "2026.10.18"
