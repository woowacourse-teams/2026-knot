#!/usr/bin/env python3
"""Inspect, back up, and reload the existing environment dashboard bundle on NCP."""

import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def alert_contract(config):
    result = copy.deepcopy(config)
    for group in result.get("groups", []):
        for rule in group["rules"]:
            for key in ("display_name", "summary", "description"):
                rule.get("annotations", {}).pop(key, None)
    for contact in result.get("contactPoints", []):
        for receiver in contact["receivers"]:
            for key in ("title", "message"):
                receiver["settings"].pop(key, None)
    return result


def runtime_contract(rules, policy, contacts):
    rules = copy.deepcopy(rules)
    for rule in rules:
        rule.pop("updated", None)
        for key in ("display_name", "summary", "description"):
            rule.get("annotations", {}).pop(key, None)
    contacts = copy.deepcopy(contacts)
    for receiver in contacts:
        for key in ("title", "message"):
            receiver.get("settings", {}).pop(key, None)
    return {"rules": sorted(rules, key=lambda rule: rule["uid"]),
            "policy": policy, "contacts": sorted(contacts, key=lambda item: item["uid"])}


def common_provider(text):
    lines = text.splitlines()
    starts = [index for index, line in enumerate(lines) if line.startswith("  - name:")]
    providers = {}
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(lines)
        providers[lines[start].split(":", 1)[1].strip()] = "\n".join(
            line.rstrip() for line in lines[start:end] if line.strip())
    return providers


def deploy(root, stage, apply, dev_count=7, prod_count=7):
    os.umask(0o077)
    assert os.geteuid() == 0, "Run on the existing NCP server as root."
    config_path = Path("provisioning/alerting/knot.json")
    provider_path = Path("provisioning/dashboards/knot.yml")
    template_path = Path("provisioning/alerting/discord-templates.json")
    directories = [Path("provisioning/environment-dashboards/dev"),
                   Path("provisioning/environment-dashboards/prod")]
    targets = [provider_path, config_path, template_path, *directories]
    manifest = json.loads((stage / "manifest.json").read_text())
    for name, checksum in manifest.items():
        assert hashlib.sha256((stage / name).read_bytes()).hexdigest() == checksum, name
    changed_files = [name for name, checksum in manifest.items()
                     if not (root / name).exists()
                     or hashlib.sha256((root / name).read_bytes()).hexdigest() != checksum]
    old_config = json.loads((root / config_path).read_text())
    new_config = json.loads((stage / config_path).read_text())
    assert alert_contract(old_config) == alert_contract(new_config), \
        "Alert configuration drift: conditions, queries, routing, receiver, or links differ."
    old_providers = common_provider((root / provider_path).read_text())
    new_providers = common_provider((stage / provider_path).read_text())
    assert set(old_providers) <= set(new_providers), "Existing provider would be removed."
    for name, provider in old_providers.items():
        assert provider == new_providers[name], "Existing provider differs: " + name
    templates = json.loads((stage / template_path).read_text())
    assert len(templates["templates"]) == 1
    uids = set()
    for directory, count, env in zip(directories, [dev_count, prod_count], ["dev", "prod"]):
        dashboards = list((stage / directory).glob("*.json"))
        assert len(dashboards) == count
        assert {path.name for path in (root / directory).glob("*.json")} <= \
            {path.name for path in dashboards}, "Unexpected existing environment dashboard."
        for path in dashboards:
            dashboard = json.loads(path.read_text())
            assert dashboard["uid"].startswith("knot-" + env + "-")
            assert dashboard["uid"] not in uids
            uids.add(dashboard["uid"])
            assert not any(variable["name"] in {"env", "instance", "db"}
                           for variable in dashboard["templating"]["list"]), "Environment must stay pinned."
    common_files = list((root / "dashboards").glob("*.json"))
    assert len(common_files) == 6, "Expected six preserved common dashboards."
    common_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in common_files}
    container = "knot-observability-grafana-1"
    inspect = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
    mounts = {mount["Destination"]: mount for mount in inspect["Mounts"]}
    assert mounts["/etc/grafana/provisioning"]["Source"] == str(root / "provisioning")
    assert not mounts["/etc/grafana/provisioning"]["RW"]
    assert mounts["/var/lib/grafana/dashboards"]["Source"] == str(root / "dashboards")
    runtime_environment_hash = digest(inspect["Config"]["Env"])
    password = (root / "secrets/grafana-admin-password").read_text().strip()
    authorization = "Basic " + base64.b64encode(("knot-admin:" + password).encode()).decode()

    def api(path, method="GET"):
        request = urllib.request.Request("http://127.0.0.1:3000" + path,
                                         data=b"" if method == "POST" else None,
                                         headers={"Authorization": authorization}, method=method)
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return {"rules": api("/api/v1/provisioning/alert-rules"),
                "policy": api("/api/v1/provisioning/policies"),
                "contacts": api("/api/v1/provisioning/contact-points")}

    before = snapshot()
    previous_templates = api("/api/v1/provisioning/templates")
    before_contract = runtime_contract(**before)
    expected_rule_uids = {rule["uid"] for group in new_config["groups"] for rule in group["rules"]}
    # Additional provisioning files (service/backup checks) are preserved by this
    # dashboard-only installer; don't treat their rules as unexpected or remove them.
    for path in (root / "provisioning/alerting").glob("*.json"):
        if path.name != config_path.name:
            expected_rule_uids.update(rule["uid"] for group in json.loads(path.read_text()).get("groups", [])
                                      for rule in group["rules"])
    assert {rule["uid"] for rule in before["rules"]} == expected_rule_uids, "Runtime alert rule drift."
    for entry in api("/api/search?type=dash-db&limit=1000"):
        if entry["uid"] in uids:
            assert entry.get("folderTitle") == "Knot-" + entry["uid"].split("-")[1], \
                "Dashboard UID already belongs to another folder."
    print(json.dumps({"preflight": "pass", "apply": apply,
                      "commonDashboards": 6, "newDashboards": len(uids),
                      "alertRules": len(expected_rule_uids), "fileAlertContract": digest(alert_contract(old_config)),
                      "runtimeAlertContract": digest(before_contract)}, ensure_ascii=False))
    if not apply:
        return
    backup = Path(tempfile.mkdtemp(prefix="environment-backup.", dir=root))
    for relative in [*targets, Path("dashboards"), Path("compose.yml"), Path("compose.team.yml")]:
        source = root / relative
        destination = backup / relative
        if source.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, destination)
            else:
                shutil.copy2(source, destination)
    (backup / "runtime-before.json").write_text(json.dumps(before))
    (backup / "notification-templates-before.json").write_text(json.dumps(previous_templates))
    (backup / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print("Backup: " + str(backup), flush=True)

    def install(relative, source_root):
        source = source_root / relative
        destination = root / relative
        if source.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
            (root / "provisioning/environment-dashboards").chmod(0o755)
            destination.chmod(0o755)
            for path in source.iterdir():
                assert path.is_file()
                shutil.copy2(path, destination / path.name)
                (destination / path.name).chmod(0o644)
        else:
            shutil.copy2(source, destination)
            destination.chmod(0o644)

    try:
        for relative in targets:
            install(relative, stage)
        for name, checksum in manifest.items():
            assert hashlib.sha256((root / name).read_bytes()).hexdigest() == checksum
        if any(name.startswith("provisioning/environment-dashboards/")
               or name == str(provider_path) for name in changed_files):
            print(json.dumps({"dashboardsReload": api("/api/admin/provisioning/dashboards/reload", "POST")}))
        if any(name.startswith("provisioning/alerting/") for name in changed_files):
            print(json.dumps({"alertingReload": api("/api/admin/provisioning/alerting/reload", "POST")}))
        after = snapshot()
        assert before_contract == runtime_contract(**after), "Runtime alert contract changed."
        assert digest(json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
                      ["Config"]["Env"]) == runtime_environment_hash
        for path in common_files:
            assert hashlib.sha256(path.read_bytes()).hexdigest() == common_hashes[path.name]
        search = api("/api/search?type=dash-db&limit=1000")
        installed = {entry["uid"]: entry for entry in search}
        for uid in uids:
            assert installed[uid]["folderTitle"] == "Knot-" + uid.split("-")[1]
            fetched = api("/api/dashboards/uid/" + uid)
            assert fetched["meta"]["provisioned"]
        for env, count in [("dev", dev_count), ("prod", prod_count)]:
            assert len([entry for entry in search if entry.get("folderTitle") == "Knot-" + env]) == count
        assert len([entry for entry in search if entry.get("folderTitle") == "Knot"]) == 6
        receiver = next(item for item in after["contacts"] if item["uid"] == "knot-discord")
        desired = new_config["contactPoints"][0]["receivers"][0]["settings"]
        assert receiver["settings"]["title"] == desired["title"]
        assert receiver["settings"]["message"] == desired["message"]
        installed_templates = api("/api/v1/provisioning/templates")
        readable = next(item for item in installed_templates if item["name"] == "knot-readable-discord")
        assert readable["template"].strip() == templates["templates"][0]["template"].strip()
        for item in previous_templates:
            if item["name"] != "knot-readable-discord":
                assert item in installed_templates, "Unrelated notification template changed."
        (backup / "runtime-after.json").write_text(json.dumps(after))
        print(json.dumps({"applied": True, "backup": str(backup),
                          "runtimeAlertContractUnchanged": True,
                          "commonDashboardsUnchanged": True,
                          "links": ["https://grafana.knoted.kr/d/" + uid for uid in sorted(uids)]},
                         ensure_ascii=False))
    except Exception:
        for relative in targets:
            destination = root / relative
            if destination.is_dir():
                shutil.rmtree(destination)
            elif destination.exists():
                destination.unlink()
            if (backup / relative).exists():
                install(relative, backup)
        for resource in ("dashboards", "alerting"):
            try:
                api("/api/admin/provisioning/" + resource + "/reload", "POST")
            except Exception as error:
                print("Rollback reload failed: " + type(error).__name__)
        print("Rollback restored files. Backup retained: " + str(backup))
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/opt/knot-observability"))
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Back up, install, and reload after preflight.")
    parser.add_argument("--dev-count", type=int, choices=[7, 8], default=7,
                        help="Use 8 when including the explicitly requested catalog 17175 dashboard.")
    parser.add_argument("--prod-count", type=int, choices=[7, 8], default=7,
                        help="Use 8 only with a stage preserving the existing request tracing dashboard.")
    arguments = parser.parse_args()
    deploy(arguments.root, arguments.stage, arguments.apply, arguments.dev_count, arguments.prod_count)
