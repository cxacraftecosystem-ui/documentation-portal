import logging
import time
from dataclasses import dataclass
from typing import Any

from app.ai_features.errors import ProviderTimeout
from app.ai_features.types import Capability

logger = logging.getLogger(__name__)

_ANNOUNCED: set[str] = set()


def warn_once(key: str, message: str, *args: Any) -> None:
    if key in _ANNOUNCED:
        return
    _ANNOUNCED.add(key)
    logger.warning(message, *args)


def reset_warn_once_cache() -> None:
    _ANNOUNCED.clear()


@dataclass(frozen=True)
class Deadline:
    budget_seconds: float
    started_at: float

    @classmethod
    def start(cls, budget_seconds: float) -> "Deadline":
        return cls(budget_seconds=budget_seconds, started_at=time.perf_counter())

    @property
    def elapsed_seconds(self) -> float:
        return time.perf_counter() - self.started_at

    @property
    def elapsed_ms(self) -> int:
        return int(self.elapsed_seconds * 1000)

    @property
    def expired(self) -> bool:
        return self.elapsed_seconds >= self.budget_seconds

    def remaining(self, *, minimum: float = 0.0) -> float:
        return max(minimum, self.budget_seconds - self.elapsed_seconds)

    def check(self, stage: str, *, capability: Capability, provider: str) -> None:
        if not self.expired:
            return
        raise ProviderTimeout(
            f"{capability} exceeded its {self.budget_seconds:g}s budget before {stage} "
            f"({self.elapsed_seconds:.1f}s elapsed)",
            capability=capability,
            provider=provider,
            remediation=(
                "Raise AI_FEATURES_TIMEOUT_SECONDS, shrink the image, or run this on the "
                "background queue where a long job is expected."
            ),
        )


def overrun_note(
    deadline: Deadline, *, stage: str, capability: Capability, provider: str
) -> tuple[str, ...]:
    if not deadline.expired:
        return ()
    warn_once(
        f"overrun:{capability}:{provider}",
        "ai_features: %s via %s took %.1fs, over the %.0fs budget — raise "
        "AI_FEATURES_TIMEOUT_SECONDS or send smaller images",
        capability,
        provider,
        deadline.elapsed_seconds,
        deadline.budget_seconds,
    )
    return (
        f"{stage} took {deadline.elapsed_seconds:.1f}s, over the "
        f"{deadline.budget_seconds:g}s budget",
    )
