"""Omma plugin loader (Python side).

Java calls into this module through OmmaPythonBridge:
  - load_plugin(source, plugin_id)  -> registers the plugin class
  - unload_plugin(plugin_id)
  - dispatch_menu_click(plugin_id, item_id, payload)
  - dispatch_send_message_hook(plugin_id, account, params_json) -> HookResult json
  - dispatch_app_event(plugin_id, event)
  - get_settings_schema(plugin_id) -> json list of ui.settings items
  - set_plugin_enabled(plugin_id, enabled)

Plugins are exteraGram-compatible: a .py file with __id__/__name__ and a
BasePlugin subclass. Elyx (.elyx/.eaf) containers are unpacked by the Java side
and their main.py is loaded the same way.
"""

import importlib.util
import io
import json
import os
import re
import sys
import traceback
import types
import zipfile

from . import base_plugin
from .base_plugin import BasePlugin

_PLUGINS = {}
_MODULES = {}
_MENU_CALLBACKS = {}
_SEND_HOOKS = {}

_SDK_PATH = os.path.dirname(os.path.abspath(__file__))
if _SDK_PATH not in sys.path:
    # Allows "from base_plugin import BasePlugin" like the upstream SDK.
    sys.path.insert(0, os.path.dirname(_SDK_PATH))


def _register_module_aliases(module):
    sys.modules.setdefault("base_plugin", module)


def load_plugin(source: str, plugin_id: str = None) -> str:
    """Executes plugin source and returns the registered plugin id."""
    module_name = "omma_plugin_%s" % (plugin_id or "anon")
    module = types.ModuleType(module_name)
    module.__dict__["__name__"] = module_name
    sys.modules[module_name] = module
    try:
        exec(compile(source, "<%s>" % module_name, "exec"), module.__dict__)
    except Exception:
        traceback.print_exc()
        sys.modules.pop(module_name, None)
        raise

    candidate = None
    for value in list(module.__dict__.values()):
        if isinstance(value, type) and issubclass(value, BasePlugin) and value is not BasePlugin:
            candidate = value
            break
    if candidate is None:
        raise ImportError("no BasePlugin subclass found")

    resolved_id = (
        plugin_id
        or getattr(module, "__id__", None)
        or getattr(candidate, "id", None)
        or module_name
    )
    instance = candidate()
    instance._exteraless_plugin_id = resolved_id
    instance.id = resolved_id
    _PLUGINS[resolved_id] = instance
    _MODULES[resolved_id] = module
    _register_module_aliases(module)
    try:
        instance.on_plugin_load()
    except Exception:
        traceback.print_exc()
    base_plugin._Internal.call("pluginLog", resolved_id, "info", "loaded")
    return resolved_id


def load_elyx(path: str) -> str:
    """Unpacks an .elyx/.eaf container and loads its main.py."""
    with zipfile.ZipFile(path) as archive:
        main_name = None
        for name in archive.namelist():
            if name == "main.py" or name.endswith("/main.py"):
                main_name = name
                break
        if main_name is None:
            raise ImportError("elyx container has no main.py")
        source = archive.read(main_name).decode("utf-8", "replace")
    return load_plugin(source)


def unload_plugin(plugin_id: str):
    instance = _PLUGINS.pop(plugin_id, None)
    if instance is not None:
        try:
            instance.on_plugin_unload()
        except Exception:
            traceback.print_exc()
    _MODULES.pop(plugin_id, None)
    _MENU_CALLBACKS.pop(plugin_id, None)
    _SEND_HOOKS.pop(plugin_id, None)


def plugin_loaded(plugin_id: str) -> bool:
    return plugin_id in _PLUGINS


def dispatch_menu_click(plugin_id: str, item_id, payload=None):
    instance = _PLUGINS.get(plugin_id)
    if instance is None:
        return False
    # Menu callbacks were registered with add_menu_item(on_click=...).
    callback = _MENU_CALLBACKS.get((plugin_id, str(item_id)))
    if callback is None:
        return False
    try:
        callback(payload)
        return True
    except Exception:
        traceback.print_exc()
        return False


def dispatch_send_message_hook(plugin_id: str, account: int, params: dict) -> str:
    instance = _PLUGINS.get(plugin_id)
    if instance is None:
        return json.dumps({"strategy": "DEFAULT"})
    try:
        result = instance.on_send_message_hook(int(account), _Params(params))
        return json.dumps({
            "strategy": result.strategy,
            "params": result.params,
            "error": result.error,
        }, ensure_ascii=False, default=str)
    except Exception as e:
        traceback.print_exc()
        return json.dumps({"strategy": "DEFAULT", "error": str(e)})


def dispatch_app_event(plugin_id: str, event: str):
    instance = _PLUGINS.get(plugin_id)
    if instance is not None:
        try:
            instance.on_app_event(event)
        except Exception:
            traceback.print_exc()


def dispatch_update_hook(plugin_id: str, update_json: str) -> str:
    instance = _PLUGINS.get(plugin_id)
    if instance is None:
        return json.dumps({"strategy": "DEFAULT"})
    try:
        result = instance.on_update_hook(json.loads(update_json))
        return json.dumps({"strategy": result.strategy}, ensure_ascii=False)
    except Exception as e:
        traceback.print_exc()
        return json.dumps({"strategy": "DEFAULT", "error": str(e)})


def get_settings_schema(plugin_id: str) -> str:
    instance = _PLUGINS.get(plugin_id)
    if instance is None:
        return "[]"
    try:
        items = instance.create_settings()
        return json.dumps([_serialize_item(i) for i in (items or [])], ensure_ascii=False, default=str)
    except Exception:
        traceback.print_exc()
        return "[]"


def get_plugin_metadata(source: str) -> str:
    """Reads __id__/__name__/__version__ etc. without executing the plugin."""
    metadata = {}
    for key in ("__id__", "__name__", "__description__", "__author__", "__version__",
                "__icon__", "__min_app_version__", "__min_sdk_version__", "__category__"):
        match = re.search(r"^%s\s*=\s*(.+)$" % re.escape(key), source, re.MULTILINE)
        if match:
            try:
                metadata[key.strip("_")] = eval(match.group(1), {"__builtins__": {}}, {})
            except Exception:
                metadata[key.strip("_")] = match.group(1).strip("\"'")
    return json.dumps(metadata, ensure_ascii=False)


def _serialize_item(item):
    if hasattr(item, "_data"):
        return dict(item._data)
    if hasattr(item, "__dict__"):
        return {k: v for k, v in item.__dict__.items() if not k.startswith("_")}
    return str(item)


class _Params(dict):
    """Dict with attribute access for hook params coming from Java."""

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name)

    def __setattr__(self, name, value):
        self[name] = value
