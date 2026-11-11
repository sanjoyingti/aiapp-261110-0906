# AI App

Android AI assistant (Kotlin + Jetpack Compose). Chat with Claude, Gemini or ChatGPT using your own API keys, with a model picker and Low/Medium/High/Max effort levels.

## Install

Every push to `main` builds a debug APK in GitHub Actions and publishes it as the latest release:
**Releases → latest → `AIApp-N.apk`**. Each build installs over the previous one.

## Setup in the app

Settings → paste your API keys (stored encrypted on the phone, never in this repo).

## Status

v0.1: streaming chat, 3 providers, model picker, effort levels.
Next: conversation history, file upload, voice with interruptions, image/video generation, app-building agent.
