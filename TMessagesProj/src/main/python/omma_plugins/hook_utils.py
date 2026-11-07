"""Java class/field access helpers for Omma plugins (exteraGram-compatible surface)."""

from .base_plugin import _Internal


def find_class(name):
    return _Internal.call("findClass", name)


def get_private_field(obj, name):
    return _Internal.call("getPrivateField", obj, name)


def set_private_field(obj, name, value):
    _Internal.call("setPrivateField", obj, name, value)


def get_static_private_field(clazz, name):
    return _Internal.call("getStaticPrivateField", clazz, name)


def set_static_private_field(clazz, name, value):
    _Internal.call("setStaticPrivateField", clazz, name, value)
