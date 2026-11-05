"""Synthetic tests: no handset connection and no real preferences or credentials."""
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

import caption_test_device as device


class IsolatedDeviceTest(unittest.TestCase):
    def test_copy_projects_only_speech_settings_and_tokens_preserving_test_opt_in(self):
        original = {
            "voxdroid": ET.fromstring('<map><string name="stt_base_url">https://example.invalid</string>'
                                     '<string name="lane_base_url_he">https://lane.invalid</string>'
                                     '<boolean name="voice_captions" value="false"/>'
                                     '<string name="unrelated_private_value">must not copy</string></map>'),
            "secrets": ET.fromstring('<map><string name="stt_token">synthetic token</string>'
                                    '<string name="lane_token_he">synthetic lane token</string>'
                                    '<string name="unrelated_secret">must not copy</string></map>'),
        }
        destination = {
            "voxdroid": ET.fromstring('<map><boolean name="voice_captions" value="true"/>'
                                     '<string name="stt_base_url">old</string>'
                                     '<string name="lane_base_url_fr">old lane</string></map>'),
            "secrets": ET.fromstring('<map><string name="polish_token">old</string></map>'),
        }
        writes, operations = {}, []
        with patch.object(device, "adb", side_effect=lambda *a, **k: operations.append(a) or b"ok"), \
                patch.object(device, "read", side_effect=lambda pkg, name: original[name] if pkg == device.SOURCE else destination[name]), \
                patch.object(device, "write", side_effect=lambda name, value: writes.update({name: value})):
            device.copy_settings()
        self.assertEqual(operations, [("shell", "run-as", device.SOURCE, "id"),
                                      ("shell", "am", "force-stop", device.TARGET)])
        settings = {n.get("name"): n for n in writes["voxdroid"]}
        self.assertEqual(set(settings), {"voice_captions", "stt_base_url", "lane_base_url_he"})
        self.assertEqual(settings["voice_captions"].get("value"), "true")
        self.assertEqual({n.get("name") for n in writes["secrets"]}, {"stt_token", "lane_token_he"})

    def test_enable_disable_preserves_other_services_and_shortcuts(self):
        other = "example.other/.Service"
        values = {"enabled_accessibility_services": other, "accessibility_button_targets": other}
        mutations = []

        def adb(*args, **_):
            if args[:4] == ("shell", "settings", "get", "secure"):
                return values[args[4]].encode()
            self.assertEqual(args[:4], ("shell", "settings", "put", "secure"))
            mutations.append(args[4:])
            values[args[4]] = args[5]
            return b""

        with patch.object(device, "adb", side_effect=adb):
            device.enable(True)
            self.assertEqual(values["enabled_accessibility_services"], other + ":" + device.COMPONENT)
            device.enable(False)
        self.assertEqual(values["enabled_accessibility_services"], other)
        self.assertEqual(values["accessibility_button_targets"], other)
        self.assertNotIn(("accessibility_enabled", "0"), mutations)

    def test_unreadable_source_fails_before_stopping_or_writing_any_app(self):
        with patch.object(device, "adb", side_effect=RuntimeError("synthetic unreadable source")) as adb, \
                patch.object(device, "write") as write:
            with self.assertRaises(RuntimeError):
                device.copy_settings()
        self.assertEqual(adb.call_count, 1)
        write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
