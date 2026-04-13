# Deskdrop

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android 9.0+](https://img.shields.io/badge/Android-9.0%2B-brightgreen.svg)]()
[![Release](https://img.shields.io/badge/Release-v1.0.2-orange.svg)](https://github.com/SvReenen/Deskdrop/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/SvReenen/Deskdrop/total?color=blue)](https://github.com/SvReenen/Deskdrop/releases/latest)

An Android keyboard with built-in AI powered by your own local server. Connect to Ollama, LM Studio, or any OpenAI-compatible backend over Tailscale or LAN. Cloud providers available as fallback or standalone.

## Demo

**AI Assist** - Rewrite selected text with one tap
<video src="docs/ai-quickbutton-demo.mp4" width="300"></video>

**Inline // syntax** - Type instructions directly after your text
<video src="docs/syntax-demo.mp4" width="300"></video>

**Clipboard AI** - Process clipboard content through AI
<video src="docs/clipboard-demo.mp4" width="300"></video>

**Conversation + Tools** - Ask about the weather, it calls the tool automatically
<video src="docs/conversation-weather-demo.mp4" width="300"></video>

**Calendar** - Create appointments through natural language
<video src="docs/calendar-demo.mp4" width="300"></video>

**MCP (Home Assistant)** - Control your smart home from your keyboard
<video src="docs/mcp-lights-demo.mp4" width="300"></video>

## Features

**Local AI integration**
- Ollama, LM Studio, vLLM, llama.cpp, KoboldCpp, Jan, Msty, or any OpenAI-compatible server
- Primary + LAN fallback URL for seamless connectivity
- On-device ONNX inference (T5) for fully offline use
- Ollama Model Wizard: create custom models with tailored system prompts

**Cloud providers**
- Gemini, Groq, OpenRouter, Anthropic, OpenAI
- Cloud fallback: when your local server goes down, all shortcuts automatically switch to a cloud model and revert when it's back (red dot indicator on toolbar keys)

**AI shortcuts**
- AI Assist: rewrite, translate, or transform selected text with a single tap
- 4 configurable shortcut slots, each with its own model and instruction
- Inline instructions with `//` syntax (e.g. type text followed by `//make shorter`)
- AI Clipboard: process clipboard content through AI
- Undo: tap again after AI processing to restore original text

**Voice**
- Self-hosted Whisper transcription (via Speaches or any Whisper-compatible server)
- Google Speech Recognition as alternative engine
- Configurable voice modes with custom prompts
- Supports `{voice_input}` and `{clipboard}` placeholders

**Conversation**
- Full multi-turn chat interface
- Model picker per conversation
- Reminder system with notifications that reopen the exact conversation

**17 built-in AI tools**

| Tool | What it does |
|---|---|
| `calculator` | Evaluate arithmetic expressions |
| `get_datetime` | Current date, time, timezone |
| `unit_convert` | Convert between units (length, mass, temperature, etc.) |
| `battery_info` | Battery percentage and charging state |
| `device_info` | Phone model, Android version, free storage |
| `read_clipboard` | Read clipboard contents |
| `fetch_url` | Fetch and read a web page |
| `web_search` | Search via Brave/Tavily |
| `weather` | Current weather via wttr.in |
| `set_timer` | Start a countdown timer |
| `open_app` | Launch an app by name |
| `set_reminder` | Schedule a notification reminder |
| `calendar` | Read, add, update, delete calendar events |
| `navigate` | Open turn-by-turn navigation |
| `phone_call` | Open dialer with number |
| `send_sms` | Open SMS app with message |
| `contact_lookup` | Search contacts by name |

**MCP (Model Context Protocol)**
- Connect external tool servers (Home Assistant, filesystem, custom APIs)
- Streamable HTTP and Legacy SSE transport
- Per-server bearer token authentication

**Home screen widget**
- Quick access bar with Voice, Chat, and Execute buttons

## Getting started

Install the APK, open Deskdrop, and the setup wizard walks you through everything.

### Quick Start (no setup needed)

1. Install the APK and open Deskdrop
2. Tap **Try it now**
3. Choose **Quick Start**
4. Turn on Deskdrop in your keyboard settings
5. Switch to Deskdrop as your active keyboard
6. Done. You can start using AI right away

### Advanced Setup

Choose **Advanced Setup** if you want to connect your own models or use a specific cloud provider.

**Cloud path (Groq / Gemini)**

1. Choose **Cloud** on the setup screen
2. Get a free API key from Groq or Gemini
3. Paste your key and continue
4. The AI demo lets you test your connection before finishing

**Local path (Ollama)**

1. Make sure Ollama is running on your computer
2. Choose **Local** on the setup screen
3. Enter your Ollama URL (default: `http://localhost:11434`)
4. Tap **Test connection** to verify
5. Pick a model from the list
6. Optional: set an alternate connection for Tailscale or LAN access

After setup, the wizard lets you personalize your AI and test it live before you start.

## Supported backends

| Backend | Type | Setup |
|---|---|---|
| Ollama | Local | Server URL |
| LM Studio | Local | Server URL (OpenAI-compatible) |
| vLLM / llama.cpp / KoboldCpp | Local | Server URL (OpenAI-compatible) |
| ONNX (T5) | On-device | Import model files |
| Gemini | Cloud | API key (free tier available) |
| Groq | Cloud | API key (free tier available) |
| OpenRouter | Cloud | API key (free models available) |
| Anthropic | Cloud | API key |
| OpenAI | Cloud | API key |

## Security

Deskdrop takes security seriously. A keyboard has access to everything you type, so trust matters.

- **API key encryption** - All keys stored with AES-256-GCM via Android EncryptedSharedPreferences. Never logged, only held in memory during use.
- **SSRF protection** - `fetch_url` blocks requests to private/internal IP ranges (loopback, link-local, site-local), preventing prompt injection attacks from scanning your local network.
- **Prompt injection mitigation** - Clipboard reading and device actions (calendar, calls, SMS, navigation) are gated behind explicit user opt-in. Both off by default.
- **No data backup** - `allowBackup="false"` prevents ADB or cloud export of app data.
- **Audio cleanup** - Whisper WAV files are deleted immediately after transcription.
- **Streaming OOM prevention** - Per-line 1MB character limit on streaming responses. Non-streaming responses capped at 10MB download, 20,000 characters to model context.
- **Password field protection** - All AI shortcuts are blocked in password fields.
- **Destructive action confirmation** - Calendar update/delete requires two-step confirmation: the AI first shows a preview, asks the user to confirm, then executes only after explicit approval.
- **Input validation** - Phone numbers validated against format regex. All URI parameters encoded. All ContentResolver queries use parameterized selection arguments (no SQL injection).
- **Contact data minimization** - Contact lookups return max 3 contacts with max 2 phone numbers and 1 email each.
- **Tool loop cap** - Maximum 5 tool calls per AI turn, preventing runaway tool execution.
- **Exported component protection** - All exported Android services require system-level bind permissions (`BIND_INPUT_METHOD`, `BIND_TEXT_SERVICE`, `BIND_QUICK_SETTINGS_TILE`).

For a complete technical reference of all settings, shortcuts, and internals, see [DESKDROP_REFERENCE.md](DESKDROP_REFERENCE.md).

## Requirements

- Android 9.0 (API 28) or higher
- For local AI: an Ollama or OpenAI-compatible server reachable from your phone (Tailscale recommended)
- For cloud AI: an API key from any supported provider
- For voice (Whisper): a Whisper-compatible server (e.g. Speaches)

## Based on

Deskdrop is built on [HeliBoard](https://github.com/Helium314/HeliBoard), an open-source privacy-focused keyboard for Android. All original HeliBoard features (themes, layouts, dictionaries, clipboard history, glide typing, one-handed mode, split keyboard) are fully preserved.

## License

Deskdrop is licensed under [GNU General Public License v3.0](LICENSE), as a fork of HeliBoard/OpenBoard.

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is also provided.

## Credits

- [HeliBoard](https://github.com/Helium314/HeliBoard) by Helium314
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
