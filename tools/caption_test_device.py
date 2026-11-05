"""Owner-authorized operations restricted to the isolated caption-test package.

Settings travel through memory only. No audio, cache, credentials or private preference values
are printed, written on the workstation, or included in the repository.
"""
import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET

SOURCE = "io.github.iteramen.voxdroid"
TARGET = SOURCE + ".captiontest"
COMPONENT = TARGET + "/" + SOURCE + ".VoxdroidA11yService"
PREFS = {
    "stt_base_url", "stt_model", "stt_prompt", "stt_timeout_s", "no_speech_threshold",
    "allow_insecure_http", "chip_languages", "polish_enabled", "polish_base_url",
    "polish_model", "polish_min_words", "polish_timeout_s", "capitalize_first_word", "strip_terminal_period",
}


def adb(*args, data=None, optional=False):
    result = subprocess.run(["adb", *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode and not optional:
        raise RuntimeError("isolated device operation failed; no private output displayed")
    return result.stdout if result.returncode == 0 else b""


def read(package, name):
    data = adb("exec-out", "run-as", package, "cat", f"shared_prefs/{name}.xml", optional=True)
    return ET.fromstring(data) if data else ET.Element("map")


def write(name, root):
    adb("shell", "run-as", TARGET, "mkdir", "-p", "shared_prefs")
    adb("shell", "run-as", TARGET, "sh", "-c", f"'cat > shared_prefs/{name}.xml'",
        data=ET.tostring(root, encoding="utf-8", xml_declaration=True))


def enable(on):
    for key in ("enabled_accessibility_services", "accessibility_button_targets"):
        current = adb("shell", "settings", "get", "secure", key).decode().strip()
        entries = [v for v in current.split(":") if v and v != "null" and v != COMPONENT]
        if on:
            entries.append(COMPONENT)
        value = ":".join(entries)
        adb("shell", "settings", "put", "secure", key, value if value else "''")
        if key == "enabled_accessibility_services" and (on or not entries):
            adb("shell", "settings", "put", "secure", "accessibility_enabled", "1" if on else "0")


def mock():
    adb("shell", "am", "force-stop", TARGET)
    root = ET.Element("map")
    for key, value in {"stt_base_url": "http://127.0.0.1:18881/v1", "polish_base_url": "http://127.0.0.1:18881/v1",
                       "stt_model": "test", "polish_model": "test", "chip_languages": "en"}.items():
        ET.SubElement(root, "string", name=key).text = value
    ET.SubElement(root, "int", name="stt_timeout_s", value="15")
    ET.SubElement(root, "int", name="polish_timeout_s", value="15")
    ET.SubElement(root, "boolean", name="polish_enabled", value="true")
    write("voxdroid", root)
    write("secrets", ET.Element("map"))


def copy_settings():
    adb("shell", "run-as", SOURCE, "id")  # Fail safely when the source is not a readable debug installation.
    adb("shell", "am", "force-stop", TARGET)
    for name in ("voxdroid", "secrets"):
        original, destination = read(SOURCE, name), read(TARGET, name)
        def wanted(key):
            if name == "secrets":
                return key in {"stt_token", "polish_token"} or key.startswith("lane_token_")
            return key in PREFS or key.startswith(("lane_base_url_", "lane_inherit_token_"))
        for node in list(destination):
            if wanted(node.get("name", "")):
                destination.remove(node)
        for node in original:
            if wanted(node.get("name", "")):
                destination.append(node)
        write(name, destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("mock", "copy-settings", "enable", "disable"))
    parser.add_argument("--confirm-isolated-handset", action="store_true", required=True)
    args = parser.parse_args()
    try:
        if not adb("shell", "pm", "path", TARGET).startswith(b"package:"):
            raise RuntimeError("isolated package is not installed")
        {"mock": mock, "copy-settings": copy_settings, "enable": lambda: enable(True), "disable": lambda: enable(False)}[args.action]()
        print("Completed isolated caption-test operation; private values were not displayed")
    except Exception:
        print("Isolated test operation failed; private output suppressed", file=sys.stderr)
        sys.exit(1)
