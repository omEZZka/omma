"""Bulletin/alert helpers for Omma plugins (exteraGram-compatible surface)."""

from ..base_plugin import _Internal


class AlertDialogBuilder:
    def __init__(self, activity=None):
        self._handle = _Internal.call("createAlertDialog", activity) or _AlertStub()
        self._title = None
        self._message = None

    def set_title(self, title):
        self._title = title
        _Internal.call("alertSetTitle", self._handle, title)
        return self

    def set_message(self, message):
        self._message = message
        _Internal.call("alertSetMessage", self._handle, message)
        return self

    def setPositiveButton(self, text, listener=None):
        _Internal.call("alertSetPositiveButton", self._handle, text, _wrap(listener))
        return self

    def setNegativeButton(self, text, listener=None):
        _Internal.call("alertSetNegativeButton", self._handle, text, _wrap(listener))
        return self

    def setView(self, view):
        _Internal.call("alertSetView", self._handle, view)
        return self

    def show(self):
        _Internal.call("alertShow", self._handle)
        return self


class _AlertStub:
    pass


def _wrap(listener):
    if listener is None:
        return None

    def _run(*args):
        try:
            listener(*args)
        except Exception as e:
            from ..android_utils import log

            log("alert listener error: %r" % e)

    return _run


def show_bulletin(text, kind="info"):
    _Internal.call("showBulletin", kind, str(text or ""))
