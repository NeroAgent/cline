#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

python - <<'PY'
import json
import socket

for port in (8767, 8768, 8769):
    with socket.create_connection(("127.0.0.1", port), timeout=3) as sock:
        sock.sendall(b'{"command":"health"}\n')
        data = sock.recv(8192).decode("utf-8").strip()
        print(json.dumps({"port": port, "response": json.loads(data)}, indent=2))
PY
