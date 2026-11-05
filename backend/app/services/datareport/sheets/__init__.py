import importlib
import pkgutil

_loaded = False


def load() -> None:
    global _loaded
    if _loaded:
        return
    for info in pkgutil.iter_modules(__path__):
        importlib.import_module(f"{__name__}.{info.name}")
    _loaded = True
