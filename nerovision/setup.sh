#!/data/data/com.termux/files/usr/bin/bash
# NeroVision — Termux install script
# Idempotent: safe to re-run at any time.
set -euo pipefail

# ---------------------------------------------------------------------------
# ANSI colours
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

LOGFILE="$(pwd)/setup.log"
exec > >(tee -a "$LOGFILE") 2>&1

info()  { echo -e "${BLUE}[INFO]${RESET}  $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
fail()  { echo -e "${RED}[FAIL]${RESET}  $*"; }

# ---------------------------------------------------------------------------
# 1. Preflight
# ---------------------------------------------------------------------------
info "NeroVision setup — $(date)"
if [ ! -d /data/data/com.termux ]; then
    fail "This script must be run inside Termux."
    exit 1
fi
ok "Running inside Termux"

# ---------------------------------------------------------------------------
# 2. Package manager update
# ---------------------------------------------------------------------------
info "Updating package lists..."
pkg update -y
ok "Packages updated"

# ---------------------------------------------------------------------------
# 3. System packages
# ---------------------------------------------------------------------------
info "Installing system packages..."
pkg install -y \
    python python-pip clang cmake make git wget curl \
    ffmpeg portaudio libsndfile openblas
ok "System packages installed"

# ---------------------------------------------------------------------------
# 4. pip upgrade
# ---------------------------------------------------------------------------
info "Upgrading pip..."
pip install --upgrade pip
ok "pip upgraded"

# ---------------------------------------------------------------------------
# 5. Core Python packages
# ---------------------------------------------------------------------------
info "Installing core Python packages..."
pip install "numpy>=1.24" "websockets>=12.0" psutil
ok "Core Python packages installed"

# ---------------------------------------------------------------------------
# 6. llama-cpp-python (with OpenBLAS)
# ---------------------------------------------------------------------------
if python -c "import llama_cpp" 2>/dev/null; then
    ok "llama-cpp-python already installed"
else
    info "Building llama-cpp-python with OpenBLAS (this takes a while)..."
    if CMAKE_ARGS="-DLLAMA_BLAS=ON -DLLAMA_BLAS_VENDOR=OpenBLAS" \
       FORCE_CMAKE=1 \
       pip install llama-cpp-python --no-binary :all:; then
        ok "llama-cpp-python installed"
    else
        warn "llama-cpp-python build failed — LLM will run in stub mode."
        warn "You can retry manually: FORCE_CMAKE=1 pip install llama-cpp-python"
    fi
fi

# ---------------------------------------------------------------------------
# 7. Whisper (STT)
# ---------------------------------------------------------------------------
if python -c "import pywhispercpp" 2>/dev/null; then
    ok "pywhispercpp already installed"
elif pip install pywhispercpp 2>/dev/null; then
    ok "pywhispercpp installed"
elif python -c "import whisper" 2>/dev/null; then
    ok "openai-whisper already installed"
elif pip install openai-whisper 2>/dev/null; then
    ok "openai-whisper installed"
else
    warn "Neither pywhispercpp nor openai-whisper could be installed."
    warn "Voice transcription will use stub mode."
fi

# ---------------------------------------------------------------------------
# 8. FAISS
# ---------------------------------------------------------------------------
if python -c "import faiss" 2>/dev/null; then
    ok "faiss-cpu already installed"
elif pip install faiss-cpu 2>/dev/null; then
    ok "faiss-cpu installed"
else
    warn "faiss-cpu install failed — brute-force numpy fallback will be used."
fi

# ---------------------------------------------------------------------------
# 9. sentence-transformers (optional, large)
# ---------------------------------------------------------------------------
if [ "${NERO_SKIP_SENTENCE_TRANSFORMERS:-0}" = "1" ]; then
    info "Skipping sentence-transformers (NERO_SKIP_SENTENCE_TRANSFORMERS=1)"
else
    if python -c "from sentence_transformers import SentenceTransformer" 2>/dev/null; then
        ok "sentence-transformers already installed"
    elif pip install sentence-transformers 2>/dev/null; then
        ok "sentence-transformers installed"
    else
        warn "sentence-transformers install failed — TF-IDF hash fallback will be used."
    fi
fi

# ---------------------------------------------------------------------------
# 10. Model download
# ---------------------------------------------------------------------------
MODEL_DIR="$(pwd)/models"
mkdir -p "$MODEL_DIR"

MODEL_URL="https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf"
MODEL_FILE="$MODEL_DIR/Phi-3-mini-4k-instruct-q4.gguf"

if [ "${NERO_SKIP_MODEL_DOWNLOAD:-0}" = "1" ]; then
    info "Skipping model download (NERO_SKIP_MODEL_DOWNLOAD=1)"
elif ls "$MODEL_DIR"/*.gguf 1>/dev/null 2>&1; then
    ok "GGUF model already present in $MODEL_DIR"
else
    info "Downloading Phi-3-mini-4k-instruct Q4 (~2.3 GB)..."
    if command -v wget >/dev/null 2>&1; then
        wget -c -O "$MODEL_FILE" "$MODEL_URL" || {
            warn "wget download failed — trying curl..."
            curl -L -C - -o "$MODEL_FILE" "$MODEL_URL" || {
                warn "Model download failed. You can download manually:"
                warn "  wget -c -O $MODEL_FILE $MODEL_URL"
            }
        }
    elif command -v curl >/dev/null 2>&1; then
        curl -L -C - -o "$MODEL_FILE" "$MODEL_URL" || {
            warn "Model download failed. You can download manually:"
            warn "  curl -L -C - -o $MODEL_FILE $MODEL_URL"
        }
    else
        warn "Neither wget nor curl available — cannot download model."
    fi
    if [ -f "$MODEL_FILE" ]; then
        ok "Model downloaded to $MODEL_FILE"
    fi
fi

# ---------------------------------------------------------------------------
# 11. Runtime directories
# ---------------------------------------------------------------------------
APP_DATA="/data/data/com.nerovision.assistant/files"
if mkdir -p "$APP_DATA" 2>/dev/null && [ -w "$APP_DATA" ]; then
    DATA_ROOT="$APP_DATA"
else
    DATA_ROOT="$HOME/nerovision_data"
    warn "Cannot write to $APP_DATA — using $DATA_ROOT instead"
fi

mkdir -p "$DATA_ROOT/logs" "$DATA_ROOT/snapshots" "$DATA_ROOT/memory" "$DATA_ROOT/crashes"
ok "Runtime directories created in $DATA_ROOT"

# Export env vars if using fallback path
if [ "$DATA_ROOT" != "$APP_DATA" ]; then
    export NERO_LOG_DIR="$DATA_ROOT/logs/"
    export NERO_SNAPSHOT_DIR="$DATA_ROOT/snapshots/"
    export NERO_MEMORY_DIR="$DATA_ROOT/memory/"
fi

# ---------------------------------------------------------------------------
# 12. Ensure __init__.py files exist
# ---------------------------------------------------------------------------
for d in core services llm memory nero_operator; do
    touch "$(pwd)/$d/__init__.py"
done
ok "__init__.py files ensured"

# ---------------------------------------------------------------------------
# 13. Generate .env template
# ---------------------------------------------------------------------------
ENV_FILE="$(pwd)/.env"
if [ ! -f "$ENV_FILE" ]; then
    cat > "$ENV_FILE" << 'ENVEOF'
# NeroVision environment configuration
# Uncomment and modify as needed.

# NERO_LOG_DIR=/data/data/com.nerovision.assistant/files/logs/
# NERO_SNAPSHOT_DIR=/data/data/com.nerovision.assistant/files/snapshots/
# NERO_MEMORY_DIR=/data/data/com.nerovision.assistant/files/memory/
# NERO_LLM_MODEL_PATH=./models/Phi-3-mini-4k-instruct-q4.gguf
# NERO_LLM_THREADS=4
# NERO_WHISPER_MODEL=~/.cache/whisper/ggml-base.en.bin
# NERO_EMBED_MODEL=all-MiniLM-L6-v2
# NERO_MEMORY_MAX_ENTRIES=10000
# NERO_SKIP_SENTENCE_TRANSFORMERS=0
# NERO_SKIP_MODEL_DOWNLOAD=0
ENVEOF
    ok ".env template created"
else
    ok ".env already exists — not overwriting"
fi

# ---------------------------------------------------------------------------
# 14. Generate start.sh
# ---------------------------------------------------------------------------
START_SCRIPT="$(pwd)/start.sh"
cat > "$START_SCRIPT" << 'STARTEOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi
cd "$SCRIPT_DIR"
exec python main.py "$@"
STARTEOF
chmod +x "$START_SCRIPT"
ok "start.sh generated"

# ---------------------------------------------------------------------------
# 15. Summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${GREEN}╔══════════════════════════════════════════════════════╗${RESET}"
echo -e "${BOLD}${GREEN}║       NeroVision setup complete!                    ║${RESET}"
echo -e "${BOLD}${GREEN}╚══════════════════════════════════════════════════════╝${RESET}"
echo ""
echo -e "${CYAN}To start:${RESET}"
echo -e "  ${BOLD}bash start.sh${RESET}"
echo -e "  ${BOLD}bash start.sh --debug${RESET}            (verbose logging)"
echo -e "  ${BOLD}bash start.sh --no-llm --no-voice${RESET} (lightweight mode)"
echo ""
echo -e "${CYAN}Required Android permissions:${RESET}"
echo -e "  ${YELLOW}•${RESET} ${BOLD}Accessibility Service${RESET}  — UI tree access (Settings → Accessibility)"
echo -e "  ${YELLOW}•${RESET} ${BOLD}Display Over Other Apps${RESET} — avatar overlay (Settings → Apps)"
echo -e "  ${YELLOW}•${RESET} ${BOLD}Microphone${RESET}             — voice input"
echo -e "  ${YELLOW}•${RESET} ${BOLD}Screen Capture${RESET}         — screenshot ingestion"
echo ""
echo -e "${CYAN}Port map:${RESET}"
echo -e "  9000  main_bridge      Android ↔ Python IPC"
echo -e "  9001  health_monitor   watchdog endpoint"
echo -e "  9002  accessibility    UI tree socket"
echo -e "  9003  audio_receiver   microphone stream"
echo -e "  9004  vision_service   UI + screenshot"
echo -e "  9005  voice_service    Whisper STT"
echo -e "  9006  llm_interface    LLM inference"
echo -e "  9007  vector_memory    FAISS memory"
echo -e "  9090  avatar_ws        WebSocket for avatar"
echo ""
echo -e "${CYAN}Data directory:${RESET} $DATA_ROOT"
echo -e "${CYAN}Setup log:${RESET}     $LOGFILE"
echo ""
