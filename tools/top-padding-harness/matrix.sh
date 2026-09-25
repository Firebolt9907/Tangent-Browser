#!/usr/bin/env bash
# Offline behavioral controller matrix, using the exact script extracted from Kotlin.
set -euo pipefail
cd "$(dirname "$0")"
node matrixTest.js "${1:-48}"
