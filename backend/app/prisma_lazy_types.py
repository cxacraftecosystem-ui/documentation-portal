from __future__ import annotations

import importlib
import importlib.machinery
import importlib.util
import os
import sys
import threading
from types import ModuleType

_ALIASES_OF_PRIVATE_TYPES = frozenset({"DatasourceOverride", "HttpConfig", "MetricsFormat", "SortMode", "SortOrder"})

_LOADED = "__fieldrepo_types_loaded__"

_lock = threading.RLock()


class LazyPrismaTypes(ModuleType):
    def __getattr__(self, name: str):
        if name.startswith("__") and name.endswith("__"):
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
    module = sys.modules.get(f"{package}.types")
    if module is None:
        return False
    return not isinstance(module, LazyPrismaTypes) or bool(module.__dict__.get(_LOADED))


def load(module: ModuleType) -> ModuleType:
    with _lock:
        namespace = module.__dict__
        if namespace.get(_LOADED) or not isinstance(module, LazyPrismaTypes):
            return module
        spec = module.__spec__
        assert spec is not None and spec.loader is not None
        namespace[_LOADED] = True
        try:
            spec.loader.exec_module(module)
        except BaseException:
            namespace[_LOADED] = False
            raise
        return module


def install(package: str = "prisma") -> bool:
    if os.environ.get("FIELDREPO_EAGER_PRISMA_TYPES") == "1":
        return False
    name = f"{package}.types"
    if package in sys.modules or name in sys.modules:
        return False
    try:
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
        return False
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
