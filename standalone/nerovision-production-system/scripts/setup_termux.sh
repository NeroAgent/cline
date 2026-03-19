#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TERMUX_DIR="${ROOT_DIR}/termux"

pkg update -y
pkg install -y python git clang cmake make rust libjpeg-turbo fftw libopenblas

python -m pip install --upgrade pip setuptools wheel
python -m pip install -r "${TERMUX_DIR}/requirements.txt"

mkdir -p "${TERMUX_DIR}/var/logs" "${TERMUX_DIR}/var/snapshots" "${TERMUX_DIR}/var/memory" "${TERMUX_DIR}/var/models"

echo "NeroVision Termux environment is ready."
