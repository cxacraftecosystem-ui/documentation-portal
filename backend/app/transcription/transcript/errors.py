"""The one refusal every on-demand transcript action raises, carrying a contract error code."""

from __future__ import annotations


class PipelineRefused(Exception):
    """An action that may not happen now. ``code`` is from ``shared/transcript-contract.json``."""

    def __init__(self, code: str, message: str, status: int = 409) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
