"""Android helpers for Omma plugins (exteraGram-compatible surface)."""

from .base_plugin import _Internal


def run_on_ui_thread(func, delay=0, services=None):
    def _run():
        try:
            func()
        except Exception as e:
            log("run_on_ui_thread error: %r" % e)

    if delay:
        _Internal.call("runOnUiDelayed", _run, int(delay * 1000))
    else:
        _Internal.call("runOnUi", _run)


class OnClickListener:
    def __init__(self, forward):
        self._forward = forward

    def onClick(self, view):
        try:
            self._forward(view)
        except Exception as e:
            log("onClick error: %r" % e)


class OnLongClickListener:
    def __init__(self, forward):
        self._forward = forward

    def onLongClick(self, view):
        try:
            return bool(self._forward(view))
        except Exception as e:
            log("onLongClick error: %r" % e)
            return False


class OnTouchListener:
    def __init__(self, forward):
        self._forward = forward

    def onTouch(self, view, event):
        try:
            return bool(self._forward(view, event))
        except Exception as e:
            log("onTouch error: %r" % e)
            return False


class OnKeyListener:
    def __init__(self, forward):
        self._forward = forward

    def onKey(self, view, keyCode, event):
        try:
            return bool(self._forward(view, keyCode, event))
        except Exception as e:
            log("onKey error: %r" % e)
            return False


def dp(value):
    result = _Internal.call("dp", float(value))
    return result if result is not None else value


def get_string(name):
    return _Internal.call("getAppString", name)


def get_context():
    return _Internal.call("getAppContext")


def get_activity():
    return _Internal.call("getCurrentActivity")


def copy_to_clipboard(text):
    _Internal.call("copyToClipboard", str(text or ""))


def set_clipboard_text(text):
    copy_to_clipboard(text)


def log(message):
    _Internal.call("pluginLog", "sdk", "info", str(message))
