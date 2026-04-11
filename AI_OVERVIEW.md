# Deskdrop AI: Technisch Overzicht

## Architectuur

Deskdrop is een Android keyboard (fork van HeliBoard) met een volledige AI-laag. Meerdere entry points (voice, toolbar, text selectie, widget, QS tile) komen samen in een unified backend (`AiServiceSync`) die communiceert met cloud providers en lokale Ollama. Het systeem ondersteunt MCP (Model Context Protocol) voor externe tool-integraties.

```
Entry points → AiServiceSync → Provider (Gemini/Ollama/Anthropic/...) → Result UI
                    ↕
              Tool system (built-in + MCP)
```

---

## Ondersteunde AI Providers

| Provider | Auth | Endpoint | Tool calling |
|----------|------|----------|--------------|
| Gemini | API key (header) | generativelanguage.googleapis.com | Ja, native |
| Ollama | Geen (lokaal) | Configureerbaar (default localhost:11434) | Ja, native |
| Anthropic Claude | API key | api.anthropic.com | Ja, tool_use |
| OpenAI | API key | api.openai.com | Ja, native |
| Groq | API key | OpenAI-compatible | Ja |
| OpenRouter | API key | openrouter.ai | Ja |
| OpenAI-compatible | Optioneel API key | Configureerbaar | Ja |

Ollama heeft een fallback URL voor LAN-toegang (bijv. Tailscale IP). De app probeert eerst de primary URL, dan fallback, met 30s cache.

---

## Features per Entry Point

### 1. Keyboard Toolbar: AI Assist
**Trigger:** AI knop op toolbar (AI_ASSIST, AI_SLOT_1-4)
**Flow:** Geselecteerde tekst → AiServiceSync → ResultViewActivity (streaming output)
**Bestanden:** `InputLogic.java`, `ProcessTextActivity.kt`, `ResultViewActivity.kt`

De gebruiker selecteert tekst, tikt op de AI knop, en krijgt een verbeterde versie terug. Ondersteunt 4 extra model slots voor snelle model-switching. Dubbeltikken op een actieve AI knop annuleert het verzoek.

### 2. Keyboard Toolbar: AI Voice
**Trigger:** Microfoon knop op toolbar (AI_VOICE)
**Flow:** Google STT of Whisper opname → AI verwerking via voice mode → tekst invoegen
**Bestanden:** `VoiceRecordingService.kt`, `WhisperRecorder.kt`

Twee STT engines:
- **Google STT:** Android's ingebouwde SpeechRecognizer, stopt automatisch bij stilte
- **Whisper:** Opname naar WAV (16kHz mono PCM) → HTTP POST naar self-hosted Whisper server

Na transcriptie blijft een floating overlay zichtbaar. De gebruiker kiest een bestemming (chat, delen, of MCP execute) met een tweede tik.

**Voice modes** (long-press op overlay knoppen):
- Smart (auto-detect): herkent of het een antwoord op gekopieerde tekst is
- Translate to English / Dutch
- Formal
- Bullet points
- Chat message
- Custom modes (gebruiker-gedefinieerd met eigen prompt)

### 3. Conversation Activity (Deskdrop Chat)
**Trigger:** Widget chat knop, toolbar, app shortcuts, reminders
**Flow:** Multi-turn chat met streaming, attachments, model picker
**Bestanden:** `ConversationActivity.kt`, `ChatViewModel.kt`, `ConversationStore.kt`

Volledige chat-interface in Jetpack Compose:
- Multi-turn gesprekken met streaming output
- Model picker (cloud presets + Ollama modellen + custom endpoints)
- Afbeelding/PDF attachments (camera, gallery, file picker)
- Systeemprompt en temperature per gesprek
- Export naar Markdown of JSON
- Gesprekken opgeslagen als JSON in `conversations/{uuid}.json`

### 4. Widget
**Trigger:** Home screen widget knoppen
**Bestanden:** `DeskdropWidget.kt`, `VoiceTrampolineActivity.kt`

Drie knoppen:
- **Microfoon:** Start voice recording
- **Chat:** Open ConversationActivity
- **Execute:** Start MCP execute mode (voice of type)

### 5. Quick Settings Tile
**Trigger:** QS tile in notification shade
**Bestanden:** `VoiceTileService.kt`, `VoiceTrampolineActivity.kt`

Toggle voor voice recording. Op Android 14+ wordt een onzichtbare trampoline-activity gebruikt om de microphone foreground service te mogen starten.

### 6. Text Selection (Process Text)
**Trigger:** Tekst selecteren in willekeurige app → "Deskdrop" in context menu
**Flow:** Geselecteerde tekst + instructie → AiServiceSync → resultaat kopieerbaar/invoegbaar
**Bestanden:** `ProcessTextActivity.kt`

Toont model picker + instructie-veld. Twee fases: CONFIG (kies model/instructie) en RESULT (streaming output). Input gecapped op 50KB.

### 7. Share Intent
**Trigger:** Delen van tekst/afbeeldingen/PDF naar Deskdrop
**Flow:** Content → ConversationActivity als nieuw bericht
**Bestanden:** `ConversationActivity.kt`

Ondersteunt `text/plain`, `image/*`, `application/pdf`, en meerdere afbeeldingen tegelijk.

---

## Tool System

### Built-in Tools

| Tool | Gate | Beschrijving |
|------|------|-------------|
| calculator | ALWAYS | Rekenoperaties (+, -, *, /, %, ^) |
| get_datetime | ALWAYS | Huidige datum/tijd/tijdzone |
| fetch_url | NETWORK | URL ophalen (100KB cap, HTML naar plain text) |
| weather | NETWORK | Weer ophalen |
| web_search | NETWORK | Brave Search of Tavily |
| set_timer | ACTIONS | Android timer instellen |
| open_app | ACTIONS | App openen op package name |
| set_reminder | ACTIONS | Reminder aanmaken, gekoppeld aan gesprek |
| calendar | ACTIONS | CRUD op Android Calendar (list/add/update/delete) |

**Gating:** Tools zijn verdeeld in drie niveaus (ALWAYS/NETWORK/ACTIONS). Network en Actions vereisen expliciete gebruikerstoestemming in settings.

### Calendar Tool
Volledige CRUD via Android CalendarContract. Ondersteunt relatieve datums in EN en NL ("morgen 15:00", "next monday"). Slimme kalender-selectie: scoort op zichtbaarheid, account type, primary, sync status.

### Reminder Tool
Reminders worden gescheduled via AlarmManager met fallback cascade (setAlarmClock → setExactAndAllowWhileIdle → setAndAllowWhileIdle → set). Notificatie-tik opent het originele gesprek.

### MCP (Model Context Protocol)
**Bestanden:** `McpClient.kt`, `McpSseSession.kt`, `McpRegistry.kt`

Ondersteunt twee transports:
- **Streamable HTTP** (2025-03-26 spec): POST naar single endpoint
- **Legacy HTTP+SSE:** GET SSE stream + POST responses

Gebruikers configureren MCP servers (URL + bearer token) in settings. Tokens worden encrypted opgeslagen. De tool loop werkt als: model antwoordt met tool_call → dispatch naar MCP server → resultaat terug naar model → herhaal tot final answer.

---

## Data Opslag

### Gesprekken (ConversationStore)
```
conversations/
  index.json           [{id, title, updatedAt, pinned}, ...]
  {uuid}.json          Volledige StoredChat
  {uuid}/              Attachment bestanden per gesprek
```

### Reminders (ReminderStore)
```
reminders.json         [{id, fireAt, message, chatId, unread}, ...]
```

### API Keys (SecureApiKeys)
Alle API keys en MCP tokens opgeslagen in `EncryptedSharedPreferences` (AES-256-GCM). Fallback naar plaintext alleen als encryptie niet beschikbaar is (legacy devices), met error logging.

---

## Cross-Process Communicatie

ConversationActivity draait in een apart `:chat` process. Communicatie met het IME process gaat via file-based IPC:
- `pending_insert.txt`: tekst die het keyboard moet invoegen
- `pending_reopen_ai.flag`: flag om AI dialog te heropenen

Het IME process leest deze bestanden in `onStartInputView()`.

---

## On-Device Inference (ONNX)

**Bestanden:** `OnnxInferenceService.kt`, `T5Tokenizer.kt`

T5 encoder-decoder modellen via ONNX Runtime. Laadt model on-demand, unloadt na 10 minuten inactiviteit. Max 64 output tokens, 4 intra-op threads.

---

## Security

1. **API keys:** Encrypted at rest (AES-256-GCM via EncryptedSharedPreferences)
2. **Password velden:** AI functies geblokkeerd in wachtwoordvelden (TYPE_TEXT_VARIATION_PASSWORD en varianten)
3. **Input limieten:** 50KB voor ProcessTextActivity, 100KB voor fetch_url
4. **Tool gating:** Network/action tools vereisen expliciete toestemming
5. **MCP tokens:** Encrypted opgeslagen, niet in plaintext JSON
6. **URL validatie:** Ollama URL scheme validation (blokkeert file://, content://)
7. **Gemini API key:** Via header (x-goog-api-key), niet in URL query parameter
8. **Debug logging:** Alleen metadata (geen user content, geen API keys, geen prompts/responses)
9. **Permissions:** RECORD_AUDIO, READ/WRITE_CALENDAR, POST_NOTIFICATIONS, elk gecontroleerd voor gebruik
10. **Geen root:** Alles binnen normale Android app sandbox

---

## Bestandsoverzicht

| Bestand | Functie |
|---------|---------|
| `AiServiceSync.kt` | AI backend, provider abstractie, tool dispatch |
| `ConversationActivity.kt` | Chat UI, berichten, model selectie |
| `VoiceRecordingService.kt` | Voice opname, STT, floating overlay |
| `ChatViewModel.kt` | Chat business logic, model state |
| `ProcessTextActivity.kt` | Text selection handler |
| `ResultViewActivity.kt` | Output display + retry/insert |
| `McpClient.kt` | MCP JSON-RPC client |
| `McpSseSession.kt` | Legacy MCP HTTP+SSE transport |
| `McpRegistry.kt` | MCP server configuratie |
| `AiToolRegistry.kt` | Tool definities + dispatch |
| `AiToolCatalog.kt` | Plugin tool lijst |
| `CalendarTool.kt` | Calendar CRUD |
| `SetReminderTool.kt` | Reminder scheduling |
| `AiTool.kt` | Tool interface |
| `ConversationStore.kt` | Chat JSON persistentie |
| `ConversationExporter.kt` | Chat export (Markdown/JSON) |
| `ReminderStore.kt` | Reminder JSON persistentie |
| `ReminderScheduler.kt` | AlarmManager integratie |
| `ReminderReceiver.kt` | Alarm → notificatie |
| `SecureApiKeys.kt` | Encrypted API key opslag |
| `AiStreamBridge.kt` | Streaming text StateFlow |
| `AiDialogComponents.kt` | Compose UI componenten |
| `AiCancelRegistry.kt` | In-flight call annulering |
| `AiRetryRegistry.kt` | Retry action holder |
| `AiChatContextRegistry.kt` | Chat context voor tools |
| `PendingInsertBridge.kt` | Cross-process IPC |
| `DeskdropWidget.kt` | Home screen widget |
| `DeskdropShortcutManager.kt` | Dynamic app shortcuts |
| `VoiceTileService.kt` | QS tile |
| `VoiceTrampolineActivity.kt` | Android 14+ mic bridge |
| `ServiceLifecycleOwner.kt` | Compose in Service lifecycle |
| `OnnxInferenceService.kt` | On-device ONNX inference |
| `T5Tokenizer.kt` | SentencePiece tokenizer |
| `WhisperRecorder.kt` | PCM recording + WAV output |

---

## Settings (AI-gerelateerd)

| Setting | Default | Beschrijving |
|---------|---------|-------------|
| `PREF_AI_BACKEND` | `gemini` | Actieve provider |
| `PREF_AI_MODEL` | `gemini:gemini-2.5-flash` | Huidig model |
| `PREF_OLLAMA_URL` | `http://localhost:11434` | Ollama endpoint |
| `PREF_OLLAMA_URL_FALLBACK` | (leeg) | LAN fallback URL |
| `PREF_OLLAMA_MODEL` | `gemma3:4b` | Ollama model |
| `PREF_AI_INSTRUCTION` | "Improve this text..." | Default inline AI prompt |
| `PREF_AI_LOREBOOK` | (leeg) | System context over gebruiker |
| `PREF_AI_ALLOW_NETWORK_TOOLS` | `false` | fetch_url, weather, web_search |
| `PREF_AI_ALLOW_ACTIONS` | `false` | timer, open_app, calendar, reminder |
| `PREF_AI_VOICE_ENGINE` | `google` | STT engine |
| `PREF_AI_VOICE_MODE` | `0` (Smart) | Actieve voice mode |
