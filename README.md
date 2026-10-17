# Transcribe

Android app that turns voice messages into text.

## How to use it

1. In WhatsApp, Telegram or any chat app, select a voice message and tap **Share**.
2. Choose **Transcribe**.
3. A small card opens over the chat with the text. From there you can **Copy**, **Share**, or tap **Translate** to open the text in the Google Translate app.

Close the card (or tap outside it) to return to the chat.

## Setup

Open the app once and save your [ElevenLabs](https://elevenlabs.io) API key (used for speech-to-text).

Translation works two ways:

- **Google Translate app (default, no key):** the transcript is handed to the installed Google Translate app. The app uses the language pair last chosen inside it; the target-language setting here only applies to the browser fallback when Google Translate isn't installed.
- **Cloud Translation API (optional):** turn off the Translate-app switch and add a Google Cloud API key to get the translation shown directly in the card.

