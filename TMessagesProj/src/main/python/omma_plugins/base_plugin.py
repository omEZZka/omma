"""Omma plugin SDK: exteraGram-compatible import surface on top of Chaquopy.

Public plugin API (BasePlugin, hook/menu contracts). The Java bridge is resolved
at import time inside try/except, and every bridge call degrades gracefully when
running on a host interpreter (e.g. unit tests on the build machine).
"""

import json
import logging
import sys
from typing import Any, Callable, Dict, Optional


class _Internal:
    """Lazily resolves the Java bridge (com.omma.messenger.plugins.OmmaPythonBridge)."""

    _bridge = None

    @classmethod
    def bridge(cls):
        if cls._bridge is not None:
            return cls._bridge if cls._bridge is not False else None
        try:
            from com.omma.messenger.plugins import OmmaPythonBridge

            cls._bridge = OmmaPythonBridge
            return cls._bridge
        except Exception:
            cls._bridge = False
            return None

    @classmethod
    def call(cls, name: str, *args):
        bridge = cls.bridge()
        if bridge is None:
            return None
        try:
            method = getattr(bridge, name)
        except AttributeError:
            return None
        try:
            return method(*args)
        except Exception:
            return None


class HookStrategy:
    """Strategy strings returned by event hooks. Java compares them literally."""

    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"


class HookResult:
    def __init__(self, strategy: str = None, request=None, response=None, update=None,
                 updates=None, params=None, error=None, result=None, resultOf=None, raw=None):
        self.strategy = strategy or HookStrategy.DEFAULT
        self.request = request
        self.response = response
        self.update = update
        self.updates = updates
        self.params = params
        self.error = error
        self.result = result
        self.resultOf = resultOf
        self.raw = raw

    def get(self, key, default=None):
        return getattr(self, key, default)

    def __repr__(self):
        return "HookResult(strategy=%s)" % self.strategy


class PluginError(Exception):
    pass


class AppEvent:
    """String-backed lifecycle values, matching the upstream SDK."""

    START = "app_start"
    STOP = "app_stop"
    PAUSE = "app_pause"
    RESUME = "app_resume"

    @staticmethod
    def from_java(value):
        return value


class MenuItemType:
    """Menu placements; values are exact Java enum names (OmmaMenuType)."""

    MESSAGE_CONTEXT_MENU = "message_context_menu"
    DRAWER_MENU = "drawer_menu"
    MAIN_MENU = "main_menu"
    CHAT_ACTION_MENU = "chat_action_menu"
    PROFILE_ACTION_MENU = "profile_action_menu"

    # Legacy alias used by older fork plugins.
    CHAT_CONTEXT = MESSAGE_CONTEXT_MENU


class MenuItemData:
    """Upstream dict contract, with attribute access for older fork plugins."""

    def __init__(self, menu_type=None, text=None, on_click=None, item_id=None,
                 icon=None, subtext=None, condition=None, priority=0):
        super().__setattr__("_data", {
            "menu_type": menu_type,
            "text": text,
            "on_click": on_click,
            "item_id": item_id,
            "icon": icon,
            "subtext": subtext,
            "condition": condition,
            "priority": priority or 0,
        })

    def __getattr__(self, name):
        try:
            return self._data[name]
        except (KeyError, AttributeError):
            raise AttributeError(name)

    def __setattr__(self, name, value):
        self._data[name] = value


class BaseHook:
    pass


class MethodHook(BaseHook):
    pass


class MethodReplacement(BaseHook):
    pass


class XposedHook(BaseHook):
    pass


class BasePlugin:
    _exteraless_plugin_id: Optional[str] = None
    _exteraless_send_message_registered = False

    id: str = ""
    enabled: bool = True

    def on_plugin_load(self):
        """Called when the plugin is enabled or restored at app start."""

    def on_plugin_unload(self):
        """Called when the plugin is disabled or the app stops."""

    def on_app_event(self, event: str):
        """Called with an AppEvent constant on app lifecycle events."""

    def create_settings(self):
        """Return a list of ui.settings items, or None for no settings UI."""

    def pre_request_hook(self, request):
        return HookResult()

    def post_request_hook(self, response):
        return HookResult()

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        return HookResult()

    def on_update_hook(self, update):
        return HookResult()

    def on_updates_hook(self, updates):
        return HookResult()

    # ---------- infrastructure ----------

    @property
    def plugin_id(self) -> str:
        return self._exteraless_plugin_id or self.id or ""

    @property
    def log(self):
        return _PluginLogger(self.plugin_id)

    @property
    def logger(self):
        """Upstream exteraGram SDK exposes self.logger (logging.Logger-like)."""
        import logging

        name = "omma.plugin.%s" % (self.plugin_id or "anon")
        existing = logging.Logger.manager.loggerDict.get(name)
        if isinstance(existing, logging.Logger):
            return existing
        logger = logging.getLogger(name)
        if not logger.handlers:
            handler = _BridgeLogHandler(self.plugin_id)
            handler.setFormatter(logging.Formatter("%(message)s"))
            logger.addHandler(handler)
            logger.setLevel(logging.DEBUG)
        return logger

    def get_setting(self, key: str, default=None, cache: bool = True, raw: bool = False):
        value = _Internal.call("getPluginSetting", self.plugin_id, key)
        if value is None:
            return default
        if raw:
            return value
        try:
            return json.loads(value) if isinstance(value, str) else value
        except Exception:
            return value

    def set_setting(self, key: str, value):
        payload = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
        _Internal.call("setPluginSetting", self.plugin_id, key, payload)

    def export_settings(self) -> Dict:
        raw = _Internal.call("exportPluginSettings", self.plugin_id)
        if isinstance(raw, str):
            try:
                return json.loads(raw)
            except Exception:
                return {}
        return raw or {}

    def import_settings(self, values: Dict):
        _Internal.call("importPluginSettings", self.plugin_id, json.dumps(values or {}, ensure_ascii=False))

    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        """Register pre/post request hooks for a TL request name."""
        _Internal.call("addRequestHook", self.plugin_id, name, bool(match_substring), int(priority))

    def remove_hook(self):
        _Internal.call("removeHooks", self.plugin_id)

    def add_on_send_message_hook(self, priority: int = 0):
        """Register the outgoing-message hook (on_send_message_hook)."""
        _Internal.call("addSendMessageHook", self.plugin_id, int(priority))
        self._exteraless_send_message_registered = True

    def add_file_hook(self, *args, **kwargs):
        pass

    def remove_file_hook(self, *args, **kwargs):
        pass

    def add_intent_hook(self, *args, **kwargs):
        pass

    def remove_intent_hook(self, *args, **kwargs):
        pass

    def reload_settings(self):
        _Internal.call("reloadPluginSettings", self.plugin_id)

    def get_plugin_config(self):
        return _Internal.call("getPluginConfig", self.plugin_id)

    def add_menu_item(self, menu_item_data: MenuItemData, text=None, on_click=None,
                      condition=None, priority=None, icon=None, subtext=None,
                      item_id=None, overrides=None, payload=None, key=None, value=None):
        """Register a menu item; returns its item_id."""
        if not isinstance(menu_item_data, MenuItemData):
            menu_item_data = MenuItemData(
                menu_type=menu_item_data, text=text, on_click=on_click,
                item_id=item_id, icon=icon, subtext=subtext,
                condition=condition, priority=priority or 0,
            )
        generated = _Internal.call(
            "addMenuItem", self.plugin_id,
            json.dumps({
                "menu_type": menu_item_data.menu_type,
                "text": menu_item_data.text,
                "item_id": menu_item_data.item_id,
                "icon": menu_item_data.icon,
                "subtext": menu_item_data.subtext,
                "priority": menu_item_data.priority,
            }, ensure_ascii=False),
        )
        return generated or menu_item_data.item_id

    def remove_menu_item(self, item_id):
        _Internal.call("removeMenuItem", self.plugin_id, item_id)

    @property
    def client(self):
        return None

    def hook_method(self, *args, **kwargs):
        return None

    def hook_all_methods(self, *args, **kwargs):
        return []

    def hook_all_constructors(self, *args, **kwargs):
        return []

    def unhook_method(self, *args, **kwargs):
        pass


class _PluginLogger:
    def __init__(self, plugin_id: str):
        self._plugin_id = plugin_id

    def _emit(self, level, message):
        _Internal.call("pluginLog", self._plugin_id, level, str(message))

    def info(self, message):
        self._emit("info", message)

    def warning(self, message):
        self._emit("warn", message)

    def error(self, message):
        self._emit("error", message)

    def debug(self, message):
        self._emit("debug", message)


class _BridgeLogHandler(logging.Handler):
    """Routes logging.Logger records to the Java bridge."""

    def __init__(self, plugin_id):
        super().__init__()
        self._plugin_id = plugin_id

    def emit(self, record):
        try:
            message = self.format(record)
        except Exception:
            message = record.getMessage()
        level = "debug"
        if record.levelno >= logging.ERROR:
            level = "error"
        elif record.levelno >= logging.WARNING:
            level = "warn"
        elif record.levelno >= logging.INFO:
            level = "info"
        _Internal.call("pluginLog", self._plugin_id, level, str(message))


# Modules re-exported for plugin imports; the Java loader adds this package's
# parent directory to sys.path so "from base_plugin import BasePlugin" works.
# Submodule aliases mirror the flat upstream SDK layout.
import importlib as _importlib

sys.modules.setdefault("base_plugin", sys.modules[__name__])
for _alias in ("android_utils", "client_utils", "hook_utils", "ui.settings", "ui.alert", "ui"):
    _module = _importlib.import_module("omma_plugins." + _alias)
    sys.modules.setdefault(_alias, _module)
