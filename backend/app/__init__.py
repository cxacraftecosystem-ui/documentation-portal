"""Field Repository API and queue worker."""

# Before anything below imports prisma: its generated types module (84,774 TypedDicts, about a
# gigabyte) is loaded only if something reads it. app/prisma_lazy_types.py says why and how.
from app import prisma_lazy_types as _prisma_lazy_types

_prisma_lazy_types.install()
