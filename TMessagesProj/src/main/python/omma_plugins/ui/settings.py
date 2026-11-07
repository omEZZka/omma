"""Settings screen DSL for Omma plugins (exteraGram-compatible surface).

Items are plain descriptors; the Java side renders them via OmmaPluginSettingsBridge.
"""


class _Item:
    type = "custom"

    def __init__(self, **kwargs):
        for key, value in kwargs.items():
            setattr(self, key, value)


class Header(_Item):
    type = "header"

    def __init__(self, text=None, **kwargs):
        super().__init__(text=text, **kwargs)


class Divider(_Item):
    type = "divider"

    def __init__(self, text=None, **kwargs):
        super().__init__(text=text, **kwargs)


class Switch(_Item):
    type = "switch"

    def __init__(self, key=None, text=None, default=False, subtext=None, icon=None,
                 on_change=None, on_long_click=None, link_alias=None, **kwargs):
        super().__init__(key=key, text=text, default=default, subtext=subtext, icon=icon,
                         on_change=on_change, on_long_click=on_long_click,
                         link_alias=link_alias, **kwargs)


class Selector(_Item):
    type = "selector"

    def __init__(self, key=None, text=None, default=None, items=None, subtext=None,
                 icon=None, on_change=None, on_long_click=None, link_alias=None, **kwargs):
        super().__init__(key=key, text=text, default=default, items=items, subtext=subtext,
                         icon=icon, on_change=on_change, on_long_click=on_long_click,
                         link_alias=link_alias, **kwargs)


class Input(_Item):
    type = "input"

    def __init__(self, key=None, text=None, default=None, subtext=None, icon=None,
                 on_change=None, on_long_click=None, link_alias=None, **kwargs):
        super().__init__(key=key, text=text, default=default, subtext=subtext, icon=icon,
                         on_change=on_change, on_long_click=on_long_click,
                         link_alias=link_alias, **kwargs)


class Text(_Item):
    type = "text"

    def __init__(self, text=None, subtext=None, icon=None, accent=False, red=False,
                 on_click=None, on_long_click=None, create_sub_fragment=None,
                 link_alias=None, **kwargs):
        super().__init__(text=text, subtext=subtext, icon=icon, accent=accent, red=red,
                         on_click=on_click, on_long_click=on_long_click,
                         create_sub_fragment=create_sub_fragment, link_alias=link_alias, **kwargs)


class EditText(_Item):
    type = "edit_text"

    def __init__(self, key=None, hint=None, default=None, multiline=False,
                 max_length=None, mask=None, on_change=None, **kwargs):
        super().__init__(key=key, hint=hint, default=default, multiline=multiline,
                         max_length=max_length, mask=mask, on_change=on_change, **kwargs)


class Custom(_Item):
    type = "custom"

    def __init__(self, item=None, view=None, factory=None, factory_args=None,
                 on_click=None, on_long_click=None, create_sub_fragment=None,
                 link_alias=None, **kwargs):
        super().__init__(item=item, view=view, factory=factory, factory_args=factory_args,
                         on_click=on_click, on_long_click=on_long_click,
                         create_sub_fragment=create_sub_fragment, link_alias=link_alias, **kwargs)
