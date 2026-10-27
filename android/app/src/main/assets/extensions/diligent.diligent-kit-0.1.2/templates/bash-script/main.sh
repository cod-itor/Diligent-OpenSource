#!/usr/bin/env bash
# {{label}}: a Bash script. Run it with: bash main.sh [name]
set -euo pipefail

name="${1:-{{label}}}"
echo "Hello from ${name} on Bash ${BASH_VERSION}"

count=0
for file in *; do
  count=$((count + 1))
done
echo "Files in this folder: ${count}"
