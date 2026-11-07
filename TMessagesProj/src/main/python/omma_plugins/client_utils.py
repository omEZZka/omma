"""Client/account helpers for Omma plugins (exteraGram-compatible surface)."""

from .base_plugin import _Internal


def get_selected_account() -> int:
    result = _Internal.call("getSelectedAccount")
    return int(result) if result is not None else 0


def get_hook_account() -> int:
    result = _Internal.call("getHookAccount")
    return int(result) if result is not None else get_selected_account()


def get_current_account() -> int:
    return get_selected_account()


def get_account_instance(account=None):
    return _Internal.call("getAccountInstance", int(account) if account is not None else None)


def get_user_id(account=None):
    return _Internal.call("getUserId", int(account) if account is not None else None)


def get_context():
    return _Internal.call("getAppContext")


def get_last_fragment():
    result = _Internal.call("getLastFragment")
    if result is None:
        class _NullFragment:
            def getParentActivity(self):
                return None

            def __getattr__(self, name):
                return None

        return _NullFragment()
    return result


def _controller(name, account=None):
    return _Internal.call("getController", name, int(account) if account is not None else None)


def get_messages_controller(account=None):
    return _controller("messages", account)


def get_contacts_controller(account=None):
    return _controller("contacts", account)


def get_media_data_controller(account=None):
    return _controller("mediaData", account)


def get_connections_manager(account=None):
    return _controller("connections", account)


def get_location_controller(account=None):
    return _controller("location", account)


def get_notifications_controller(account=None):
    return _controller("notifications", account)


def get_messages_storage(account=None):
    return _controller("messagesStorage", account)


def get_send_messages_helper(account=None):
    return _controller("sendMessages", account)


def get_file_loader(account=None):
    return _controller("fileLoader", account)


def get_secret_chat_helper(account=None):
    return _controller("secretChats", account)


def get_download_controller(account=None):
    return _controller("download", account)


def get_notifications_settings(account=None):
    return _controller("notificationsSettings", account)


def get_notification_center(account=None):
    return _controller("notificationCenter", account)


def get_media_controller(account=None):
    return _controller("media", account)


def get_user_config(account=None):
    return _controller("userConfig", account)


def get_file_ref_controller(account=None):
    return _controller("fileRef", account)


def get_stats_controller(account=None):
    return _controller("stats", account)


def get_queue_by_name(name):
    return _Internal.call("getQueueByName", name)


def run_on_queue(fn, queue=None, delay=None, delay_ms=None, queue_name=None, plugin_frame_owner=None):
    def _run():
        try:
            fn()
        except Exception as e:
            log("run_on_queue error: %r" % e)

    delay_ms_value = delay_ms if delay_ms is not None else (delay * 1000 if delay else 0)
    _Internal.call("runOnQueue", _run, int(delay_ms_value or 0))


def show_error_bulletin(text):
    _Internal.call("showBulletin", "error", str(text or ""))


def show_info_bulletin(text):
    _Internal.call("showBulletin", "info", str(text or ""))


def send_request(request, fn=None, account=None, services=None):
    """Sends a TL request through the host client; fn(response, error) on completion."""
    _Internal.call("sendTlRequest", request, fn, int(account) if account is not None else None)


def send_text(dialog_id, text, account=None, **kwargs):
    _Internal.call("sendText", int(dialog_id), str(text or ""), int(account) if account is not None else None)


def send_message(dialog_id, text=None, account=None, **kwargs):
    send_text(dialog_id, text, account)


def send_photo(dialog_id, path, caption=None, account=None, **kwargs):
    _Internal.call("sendPhoto", int(dialog_id), str(path), caption, int(account) if account is not None else None)


def send_video(dialog_id, path, caption=None, account=None, **kwargs):
    _Internal.call("sendVideo", int(dialog_id), str(path), caption, int(account) if account is not None else None)


def send_document(dialog_id, path, caption=None, account=None, **kwargs):
    _Internal.call("sendDocument", int(dialog_id), str(path), caption, int(account) if account is not None else None)


def send_audio(dialog_id, path, caption=None, account=None, **kwargs):
    _Internal.call("sendAudio", int(dialog_id), str(path), caption, int(account) if account is not None else None)


def edit_message(dialog_id, message_id, text=None, account=None, **kwargs):
    _Internal.call("editMessage", int(dialog_id), int(message_id), text, int(account) if account is not None else None)


def write_temp_file(data, suffix=None, prefix=None):
    import tempfile

    handle = tempfile.NamedTemporaryFile(suffix=suffix, prefix=prefix, delete=False)
    try:
        handle.write(data if isinstance(data, bytes) else str(data).encode("utf-8"))
    finally:
        handle.close()
    return handle.name


def tempdir():
    import tempfile

    return tempfile.gettempdir()


def log(message):
    _Internal.call("pluginLog", "sdk", "info", str(message))
