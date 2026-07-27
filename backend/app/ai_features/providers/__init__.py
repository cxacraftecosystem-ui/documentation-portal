"""Provider implementations, each of which is loaded only when a call selects it.

This module deliberately re-exports NOTHING. ``from app.ai_features.providers import *`` would
import every sibling, and one of those siblings pulls in onnxruntime — 700 MB of resident memory
on a box that has 1 GiB in total. The registry names implementations as ``"module:Class"`` strings
and imports them one at a time, after the capability's flag has been checked, which is the only
reason this package can exist in the repository without existing in the running process.

Adding a provider: write the class against the interface in :mod:`app.ai_features.providers.base`,
declare a :class:`~app.ai_features.types.ProviderDescriptor` for it in
:mod:`app.ai_features.registry`, and keep every dependency import inside the method that uses it.
"""
