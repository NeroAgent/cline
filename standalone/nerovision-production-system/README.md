# NeroVision Production System

Standalone Android + Termux implementation of a crash-resistant on-device assistant.

## Layout

- `android/` - Android app with accessibility, overlay, bridge, screen capture, audio capture, and watchdog services.
- `termux/` - Python runtime with persistent localhost services, llama.cpp integration, Whisper STT, vector memory, and deterministic operator.
- `scripts/` - Termux setup, startup, and healthcheck scripts.

## Localhost Ports

- `8766` - Android bridge
- `8767` - Vision service
- `8768` - Voice service
- `8769` - Production operator

## Android setup

1. Import `android/` into Android Studio.
2. Build and install the app.
3. Grant overlay, microphone, accessibility, and media projection permissions from `MainActivity`.

## Termux setup

```bash
cd standalone/nerovision-production-system
bash scripts/setup_termux.sh
bash scripts/start_nerovision.sh
```

## Health check

```bash
bash scripts/healthcheck.sh
```
