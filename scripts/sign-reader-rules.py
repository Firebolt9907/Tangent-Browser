#!/usr/bin/env python3
"""
Sign the reader-rules file for Lumina's remote update channel.

Produces exactly what ReaderRules.verifyEd25519 expects:
  - PUBLIC_KEY_B64  = base64 of the raw 32-byte Ed25519 public key  -> paste into ReaderRules.kt
  - reader_rules.json.sig = base64 of the raw 64-byte signature over the exact bytes of
    reader_rules.json -> commit + push alongside reader_rules.json in the rules repo

The PRIVATE key is generated once and kept OUTSIDE any repo (default ~/.config/lumina). Never commit
it and never put it in CI — that is the whole point of signing (repo write-access must not be enough
to forge rules).

Usage:
    python3 scripts/sign-reader-rules.py [RULES_REPO_DIR]

    RULES_REPO_DIR   path to your local rules repo clone (the folder containing reader_rules.json).
                     Defaults to $LUMINA_RULES_REPO, else ../lumina-reader-rules.

Env overrides:
    LUMINA_RULES_REPO   default rules repo dir
    LUMINA_RULES_KEY    private-key file path (default ~/.config/lumina/reader-rules-ed25519.key)

Requires: pip install cryptography
"""

import base64
import os
import sys

try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey,
        Ed25519PublicKey,
    )
    from cryptography.hazmat.primitives.serialization import (
        Encoding,
        PrivateFormat,
        PublicFormat,
        NoEncryption,
    )
except ImportError:
    sys.exit("error: this script needs the 'cryptography' package.\n  pip install cryptography")


def load_or_create_key(key_path: str) -> Ed25519PrivateKey:
    if os.path.exists(key_path):
        with open(key_path, "rb") as f:
            seed = base64.b64decode(f.read().strip())
        return Ed25519PrivateKey.from_private_bytes(seed)

    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    os.makedirs(os.path.dirname(key_path), exist_ok=True)
    # Write private key readable only by the owner.
    fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(base64.b64encode(seed))
    print(f"[reader-rules] generated a new Ed25519 private key at {key_path} (keep it offline!)")
    return key


def main() -> None:
    repo_dir = (
        (sys.argv[1] if len(sys.argv) > 1 else None)
        or os.environ.get("LUMINA_RULES_REPO")
        or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "lumina-reader-rules")
    )
    repo_dir = os.path.abspath(repo_dir)
    rules_path = os.path.join(repo_dir, "reader_rules.json")
    sig_path = rules_path + ".sig"

    if not os.path.isfile(rules_path):
        sys.exit(f"error: {rules_path} not found (pass the rules repo dir as the first argument).")

    key_path = os.environ.get(
        "LUMINA_RULES_KEY",
        os.path.join(os.path.expanduser("~"), ".config", "lumina", "reader-rules-ed25519.key"),
    )
    key = load_or_create_key(key_path)

    with open(rules_path, "rb") as f:
        payload = f.read()

    signature = key.sign(payload)  # raw 64-byte Ed25519 signature
    sig_b64 = base64.b64encode(signature).decode("ascii")
    with open(sig_path, "w") as f:
        f.write(sig_b64 + "\n")

    pub = key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)  # raw 32-byte key
    pub_b64 = base64.b64encode(pub).decode("ascii")

    # Sanity check: verify what we just produced, so a broken sig never ships.
    Ed25519PublicKey.from_public_bytes(pub).verify(signature, payload)

    print()
    print(f"[reader-rules] signed {rules_path}")
    print(f"[reader-rules] wrote  {sig_path}")
    print()
    print("PUBLIC_KEY_B64 (paste into ReaderRules.kt, replacing the empty string):")
    print(f'    const val PUBLIC_KEY_B64 = "{pub_b64}"')
    print()
    print("Next: commit & push reader_rules.json + reader_rules.json.sig in the rules repo, and make")
    print("sure its \"version\" was bumped (anti-rollback ignores a version <= the one already loaded).")


if __name__ == "__main__":
    main()
