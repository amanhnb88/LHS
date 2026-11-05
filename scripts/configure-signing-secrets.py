#!/usr/bin/env python3
"""Install only the four LHS signing secrets from the private recovery archive.
Requires an authenticated GitHub CLI with access to amanhnb88/LHS.
No secret is passed as a CLI argument or printed.
"""
import base64
import json
import subprocess
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/configure-signing-secrets.py /path/signing-recovery.json")
    path = Path(sys.argv[1]).resolve()
    data = json.loads(path.read_text())
    key_path = path.parent / data["LHS_KEYSTORE_PATH"]
    values = {
        "LHS_KEYSTORE_BASE64": base64.b64encode(key_path.read_bytes()).decode(),
        "LHS_KEYSTORE_PASSWORD": data["LHS_KEYSTORE_PASSWORD"],
        "LHS_KEY_ALIAS": data["LHS_KEY_ALIAS"],
        "LHS_KEY_PASSWORD": data["LHS_KEY_PASSWORD"],
    }
    if not all(isinstance(v, str) and v for v in values.values()):
        raise SystemExit("Incomplete recovery data.")
    account = subprocess.run(["gh", "api", "user", "--jq", ".login"], check=True, capture_output=True, text=True).stdout.strip()
    if account != "amanhnb88":
        raise SystemExit("Switch GitHub CLI to account amanhnb88 first.")
    for name, value in values.items():
        result = subprocess.run(["gh", "secret", "set", name, "--repo", "amanhnb88/LHS"], input=value, text=True, capture_output=True)
        if result.returncode:
            raise SystemExit(f"Could not set {name}. Check repository Secrets write permission.")
        print(f"Configured {name}")
    print("Four signing secrets configured. No build was triggered.")


if __name__ == "__main__":
    main()
