#!/bin/bash
# This script tests a Python package after uploading it to PyPI.
set -euo pipefail

# Validate arguments
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <package_name> <version>"
    exit 1
fi

# Variables
PACKAGE_NAME="$1"
VERSION="$2"
INTERVAL=10 # seconds
MAX_ATTEMPTS=30
ENTRY_POINTS=("certoraRun" "certoraMutate" "certoraSolanaProver" "certoraSorobanProver" "certoraEVMProver" "certoraRanger" "certoraConcord" "certoraCVLFormatter" "certoraSuiProver")

# Print package details
echo "📦 Package name: $PACKAGE_NAME"
echo "📌 Version: $VERSION"

# Retry loop to wait for package availability on PyPI
echo "🔍 Checking for package availability on PyPI..."
for ATTEMPT in $(seq 1 "$MAX_ATTEMPTS"); do
    if python3 -m pip install --dry-run "$PACKAGE_NAME==$VERSION" &>/dev/null; then
        echo "✅ Target version $VERSION found on PyPI!"
        break
    elif [ "$ATTEMPT" -lt "$MAX_ATTEMPTS" ]; then
        echo "⏳ Attempt $ATTEMPT/$MAX_ATTEMPTS: Package not yet available, retrying in $INTERVAL seconds..."
        sleep "$INTERVAL"
    else
        echo "❌ Attempt $ATTEMPT/$MAX_ATTEMPTS: Package $PACKAGE_NAME==$VERSION not found on PyPI."
        exit 1
    fi
done

# Install the package
echo "⬇️ Installing $PACKAGE_NAME==$VERSION..."
python3 -m pip install "$PACKAGE_NAME==$VERSION"

# Check all entry points
echo "🚀 Verifying entrypoints..."
for entrypoint in "${ENTRY_POINTS[@]}"; do
    if command -v "$entrypoint" >/dev/null 2>&1; then
        echo "▶️ Running: $entrypoint --help"
        "$entrypoint" --help
    else
        echo "⚠️ Warning: Entrypoint '$entrypoint' not found!"
    fi
done

echo "🎉 All checks completed successfully."
