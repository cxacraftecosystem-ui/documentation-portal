"""Load the generated ``prisma.types`` module only when something actually reads it.

WHY (measured 2026-10-19). ``prisma generate`` writes ``prisma/types.py`` for this schema's 74 models
at ``recursive_type_depth = 5``: 28 MB of source, 84,774 ``TypedDict`` classes. ``import prisma``
executes all of it, because the package ``__init__`` imports ``types`` and ``client.py`` imports three
names from it. In a python:3.14 container the API, started as its unit starts it, took 841 MB and
twelve minutes to answer /health on it (the first import, compiling the bytecode, 1.39 GB and twenty
minutes); with this module, 167 MB and four seconds. On the t3.small it is most of the ~800 MB that each of the API and the queue worker held (and
pushed into swap) at idle, and most of the 17 minutes the API took to answer /health after the
05:11 restart that day. In CI it is the three minutes the backend suite spends before it collects.

Those classes exist for type checkers. The generated client only ever names them in annotations
(``client.py`` and ``actions.py`` start with ``from __future__ import annotations``, so annotations
are never evaluated) and in ``cast(types.<Model>CountAggregateOutput, ...)`` inside
``count(select=...)``, which this application never calls. At run time nothing else reads them.

WHAT THIS DOES. Before ``prisma`` is imported, ``sys.modules["prisma.types"]`` is set to a module
object whose body has not run yet. The handful of names the client imports from it at start-up are
answered from ``prisma._types``, where the generated file takes them from too (the same objects, so
``is`` holds). Reading ANY other attribute runs the real, generated ``types.py`` into that same module
object, from its usual ``__pycache__`` bytecode, and from then on it is exactly the module ``prisma``
would have imported. Behaviour is identical; only the moment the cost is paid moves, and in this
application that moment never comes. ``backend/tests/test_import_footprint.py`` pins both halves.

``app/__init__.py`` calls :func:`install` so it runs before any ``app.*`` module imports ``prisma``,
and ``python -m app.prisma_cli`` does the same for the Prisma CLI. It does nothing if ``prisma`` is
already imported, if the client has not been generated, or if ``FIELDREPO_EAGER_PRISMA_TYPES=1`` is
set (the escape hatch: the old, eager import).
"""

from __future__ import annotations

import importlib
import importlib.machinery
import importlib.util
import os
import sys
import threading
from types import ModuleType

#: Names the generated client imports from ``prisma.types`` while ``prisma`` itself is importing.
#: In the generated ``types.py`` each is an alias of the same name in ``prisma._types``.
_ALIASES_OF_PRIVATE_TYPES = frozenset({"DatasourceOverride", "HttpConfig", "MetricsFormat", "SortMode", "SortOrder"})

#: Set in the module's namespace once the generated body has started running.
_LOADED = "__fieldrepo_types_loaded__"

_lock = threading.RLock()


class LazyPrismaTypes(ModuleType):
    """``prisma.types`` before its body has run. The first read of any other name runs it."""

    def __getattr__(self, name: str):
        if name.startswith("__") and name.endswith("__"):
            # Probes such as copy, pickle or inspect asking for __wrapped__ or __path__: answering
            # them must not cost the full load.
            raise AttributeError(name)
        namespace = ModuleType.__getattribute__(self, "__dict__")
        if name in _ALIASES_OF_PRIVATE_TYPES and not namespace.get(_LOADED):
            return getattr(importlib.import_module(f"{namespace['__package__']}._types"), name)
        load(self)
        if name in namespace:
            return namespace[name]
        raise AttributeError(f"module {namespace['__name__']!r} has no attribute {name!r}")

    def __dir__(self):
        load(self)
        return list(ModuleType.__getattribute__(self, "__dict__"))


def is_loaded(package: str = "prisma") -> bool:
    """True when ``<package>.types`` has run its generated body (or was imported the eager way)."""
    module = sys.modules.get(f"{package}.types")
    if module is None:
        return False
    return not isinstance(module, LazyPrismaTypes) or bool(module.__dict__.get(_LOADED))


def load(module: ModuleType) -> ModuleType:
    """Run the generated ``types.py`` into the lazy module, once. Returns the module."""
    with _lock:
        namespace = module.__dict__
        if namespace.get(_LOADED) or not isinstance(module, LazyPrismaTypes):
            return module
        spec = module.__spec__
        assert spec is not None and spec.loader is not None
        # The marker goes first: the generated file ends with `from . import types, ...`, which reads
        # this module back while its body is still running.
        namespace[_LOADED] = True
        try:
            spec.loader.exec_module(module)
        except BaseException:
            namespace[_LOADED] = False
            raise
        return module


def install(package: str = "prisma") -> bool:
    """Put the lazy ``<package>.types`` in place. True when it did; False when it left things alone."""
    if os.environ.get("FIELDREPO_EAGER_PRISMA_TYPES") == "1":
        return False
    name = f"{package}.types"
    if package in sys.modules or name in sys.modules:
        return False
    try:
        # A top-level find_spec locates the package without running its __init__.
        found = importlib.util.find_spec(package)
    except (ImportError, ValueError):
        return False
    if found is None or not found.submodule_search_locations:
        return False
    for directory in found.submodule_search_locations:
        path = os.path.join(directory, "types.py")
        if os.path.isfile(path):
            break
    else:
        return False  # not generated yet: prisma's own "run prisma generate" error stays as it was
    loader = importlib.machinery.SourceFileLoader(name, path)
    spec = importlib.util.spec_from_file_location(name, path, loader=loader)
    if spec is None:
        return False
    module = LazyPrismaTypes(name)
    module.__spec__ = spec
    module.__file__ = path
    module.__loader__ = loader
    module.__package__ = package
    module.__cached__ = spec.cached
    sys.modules[name] = module
    return True
