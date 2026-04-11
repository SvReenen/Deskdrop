# Deskdrop - Volledige Referentie

---

## Instellingen

### General

**About me / Lorebook**
Vrij tekstveld waar je de AI over jezelf vertelt. Alles wat je hier invult wordt bij elk AI-verzoek meegegeven als context. Denk aan: je naam, schrijfstijl, taalvoorkeur, beroep, of andere voorkeuren.

**AI Instruction**
De standaard systeeminstructie die het AI-model krijgt. Dit bepaalt hoe het model zich gedraagt (bijv. "antwoord altijd in het Nederlands"). Custom modellen via de Ollama Model Wizard gebruiken hun eigen ingebouwde prompt en negeren dit veld.

**Allow tools that access the internet**
Schakelaar. Bepaalt of de AI tools mag gebruiken die internet nodig hebben, zoals webpagina's ophalen, weer opvragen of zoeken. Uitgeschakeld blijft alles lokaal, de AI kan dan nog wel rekenen, datum/tijd opvragen en eenheden omrekenen.

**Allow tools that perform actions**
Schakelaar. Bepaalt of de AI acties mag uitvoeren op je telefoon, zoals een timer zetten, een app openen, agenda-items aanmaken, bellen, SMS'en of navigeren. Standaard uit, omdat deze acties zichtbare gevolgen hebben.

**Cloud fallback**
Schakelaar. Wanneer je lokale server (Ollama/LM Studio) niet bereikbaar is, schakelen alle sneltoetsen en modellen automatisch over naar een cloudmodel. Zodra de server weer online is, worden je oorspronkelijke instellingen hersteld. Een rode stip op de sneltoetsen geeft aan dat cloud fallback actief is.

**Inline model**
Kies welk model gebruikt wordt voor inline AI-verzoeken (via de toolbar-sneltoetsen). Je ziet hier cloud-, Ollama- en OpenAI-compatible modellen, gefilterd op basis van de Model filter instelling.

**Conversation model**
Kies welk model gebruikt wordt in het gespreksscherm (de volledige chatweergave).

**Model filter**
Filtert welke modellen verschijnen in de modelpickers. Opties: "Local only" (alleen lokaal), "Cloud only" (alleen cloud) of "Both" (beide).

**MCP / Actions model**
Kies welk model gebruikt wordt voor tool-aanroepen en MCP-opdrachten. Sommige modellen zijn beter in het aanroepen van tools dan andere. Stel in op "Default" om het hoofdmodel te hergebruiken.

**MCP Servers**
Beheer Model Context Protocol servers die extra tools beschikbaar maken voor de AI (bijv. Home Assistant, bestandssysteem). Per server stel je in: naam, URL, bearer token (optioneel), transporttype (Streamable HTTP of Legacy SSE), en aan/uit.

#### Technisch

| Instelling | Preference key | Default |
|---|---|---|
| About me / Lorebook | `ai_lorebook` | `""` |
| AI Instruction | `ai_instruction` | `""` |
| Allow network tools | `ai_allow_network_tools` | `false` |
| Allow actions | `ai_allow_actions` | `false` |
| Cloud fallback | `ai_cloud_fallback` | `false` |
| Inline model | `ai_inline_model` | `""` |
| Conversation model | `ai_conversation_model` | `""` |
| Model filter | `ai_model_filter` | `"both"` |
| MCP / Actions model | `ai_mcp_model` | `""` |
| MCP Servers | via `McpRegistry` (JSON) | `[]` |

Cloud fallback werkt door alle modelvoorkeuren tijdelijk te overschrijven in SharedPreferences. Originele waarden worden opgeslagen met prefix `ai_cloud_fallback_backup_`. Bereikbaarheid wordt gecached voor 30 seconden. Bij een verbindingsfout wordt de cache onmiddellijk ongeldig gemaakt zodat fallback direct activeert bij het volgende verzoek.

De lorebook wordt als systeem-context meegegeven in elke API-call, ongeacht welk model of welke provider.

---

### Local

#### Ollama

**Ollama URL**
De URL van je Ollama-server. Meestal een Tailscale IP of lokaal adres, bijv. `http://100.x.x.x:11434`.

**LAN fallback URL (optioneel)**
Wordt automatisch gebruikt als de primaire Ollama URL niet bereikbaar is. Handig voor je thuis-LAN als Tailscale uitvalt.

**Test connection**
Knop die test of de server bereikbaar is en toont welke modellen beschikbaar zijn.

**AI Model (Ollama)**
Kies het actieve Ollama-model uit de lijst die van je server wordt opgehaald.

**Ollama Model Wizard**
Stapsgewijze wizard waarmee je een custom Ollama-model aanmaakt met een specifieke systeemprompt, temperatuur en andere parameters. Het resultaat wordt als Modelfile naar je Ollama-server gepusht.

#### OpenAI-compatible

**Server URL**
De URL voor een OpenAI-compatible server (LM Studio, vLLM, llama.cpp, KoboldCpp, Jan, Msty, etc.).

**LAN fallback URL (optioneel)**
Fallback-URL wanneer de primaire niet bereikbaar is.

**API key (optioneel)**
API-sleutel voor de OpenAI-compatible server. De meeste lokale servers vereisen er geen.

**AI Model (OpenAI-compatible)**
Kies het actieve model van je OpenAI-compatible server.

#### ONNX

**Activate ONNX / Import model files / Test load model**
Activeer on-device T5-inferentie via ONNX. Je importeert `encoder_model.onnx`, `decoder_model_merged.onnx` en `tokenizer.json` vanaf je apparaat. Alles draait dan volledig offline op je telefoon, zonder server.

#### Model Presets

**Model Presets**
Maak benoemde presets die een model koppelen aan een prompt. Elk preset heeft een naam, modelselectie en promptveld. Presets zijn toewijsbaar aan sneltoetsen.

#### Technisch

| Instelling | Preference key |
|---|---|
| Ollama URL | `ollama_url` |
| Ollama fallback URL | `ollama_url_fallback` |
| AI Model | `ai_model` (waarde met prefix `ollama:` of `openai:`) |
| OpenAI-compat URL | `openai_compat_url` |
| OpenAI-compat fallback URL | `openai_compat_url_fallback` |
| OpenAI-compat API key | `openai_compat_api_key` (versleuteld via SecureApiKeys) |
| ONNX activatie | `ai_model` gezet op `"onnx:t5"` |
| Model Presets | `ai_cloud_presets` (JSON array) |

URL-resolutie: `normalizeOllamaUrl()` voegt `http://` toe als er geen scheme is opgegeven. Fallback-URL wordt automatisch geprobeerd als de primaire URL faalt, met resultaat gecached om herhaalde netwerkcalls te voorkomen. De Ollama- en OpenAI-compatible modellijsten worden gecached in SharedPreferences als JSON.

---

### Cloud

**Gemini API key**
Je Google Gemini API-sleutel.

**Groq API key**
Je Groq API-sleutel. Groq biedt gratis snelle inference voor open-source modellen.

**OpenRouter API key**
Je OpenRouter API-sleutel. OpenRouter biedt toegang tot diverse modellen, waaronder gratis opties.

**Anthropic API key**
Je Anthropic API-sleutel (voor Claude-modellen).

**OpenAI API key**
Je OpenAI API-sleutel (voor GPT-modellen).

**Tavily API key**
Je Tavily search API-sleutel. Tavily is een zoekmachine gebouwd voor LLM's die voorverwerkte resultaten teruggeeft. Gratis tier: 1000 credits/maand.

**Brave Search API key**
Optionele fallback voor webzoeken. Als Tavily niet ingesteld of beschikbaar is, gebruikt de web_search tool Brave's API. Zonder beide keys wordt er teruggevallen op het scrapen van Brave's publieke resultaten.

**Default cloud model**
Kies het standaard cloudmodel uit de lijst van beschikbare providers.

**Model Presets**
Zelfde preset-systeem als op het Local-tabblad.

#### Technisch

| Instelling | Preference key |
|---|---|
| Gemini API key | `gemini_api_key` |
| Groq API key | `groq_api_key` |
| OpenRouter API key | `openrouter_api_key` |
| Anthropic API key | `anthropic_api_key` |
| OpenAI API key | `openai_api_key` |
| Tavily API key | `tavily_api_key` |
| Brave Search API key | `brave_search_api_key` |

Alle API-sleutels worden opgeslagen via `SecureApiKeys` met AES-256-GCM encryptie (EncryptedSharedPreferences). Bij het laden worden sleutels alleen in-memory gebruikt en nooit gelogd. De beschikbare cloudmodellen worden bepaald door `CLOUD_MODELS` (een vaste lijst) gefilterd op `hasApiKey()`: alleen modellen waarvoor een geldige key is ingesteld verschijnen in de pickers.

---

### Voice

**Voice model**
Kies welk model gebruikt wordt voor het verwerken van spraaktranscripties. Laat leeg om het standaard AI-model te gebruiken.

**Speech engine**
Keuze tussen "Google" (de ingebouwde spraakherkenning van je telefoon) en "Whisper (server)" (een zelf-gehoste Whisper-compatible server zoals Speaches).

**Whisper server URL**
URL van je Whisper-compatible server. Laat leeg om automatisch de Ollama-host te gebruiken op poort 8080.

**Fallback URL (optioneel)**
Fallback-URL voor de Whisper-server.

**Whisper model**
Selecteer welk Whisper-model op je server wordt gebruikt voor transcriptie. Je kan ook modellen downloaden (tiny/base/small/medium/large-v1/v2/v3) en verwijderen.

**Voice Prompts (ingebouwde modi)**
Bewerkbare prompts voor elke ingebouwde voice-modus. Elke modus heeft een standaardprompt die je kunt aanpassen of resetten.

**Custom voice modes**
Maak eigen voice-modi aan bovenop de ingebouwde. Elke modus heeft een naam en een prompt. De prompt ondersteunt placeholders: `{voice_input}` (de transcriptie) en `{clipboard}` (klembordinhoud).

#### Technisch

| Instelling | Preference key | Default |
|---|---|---|
| Voice model | `ai_voice_model` | `""` (valt terug op `ai_model`) |
| Speech engine | `ai_voice_engine` | `"google"` |
| Whisper URL | `whisper_url` | `""` |
| Whisper fallback URL | `whisper_url_fallback` | `""` |
| Whisper model | `whisper_model` | `""` |
| Voice prompts | `ai_voice_prompt_{i}` | per modus |
| Custom voice modes | `ai_voice_custom_modes` | `[]` (JSON array) |
| Actieve voice modus | `ai_voice_mode` | `0` |

Spraakopname verloopt via `VoiceRecordingService` (een foreground service met notificatie). Bij Whisper wordt audio opgenomen als WAV, naar de server gestuurd voor transcriptie, en het bestand daarna verwijderd. De transcriptie wordt vervolgens door het gekozen AI-model verwerkt met de prompt van de actieve voice-modus.

---

## Sneltoetsen

### AI Assist
De algemene AI-knop. Pakt de geselecteerde tekst (of alle tekst als niets geselecteerd is) en stuurt het naar het standaard AI-model. Je kunt inline instructies geven door `//` te typen gevolgd door je instructie (bijv. tekst gevolgd door `//maak korter`). Opnieuw tikken terwijl AI bezig is annuleert het verzoek. Nogmaals tikken na een resultaat doet een undo (herstelt originele tekst).

### Sneltoets 1 t/m 4
Vier instelbare sneltoetsen. Elke sneltoets kan een eigen model en instructie hebben. Werkt hetzelfde als AI Assist, maar met de instellingen van die specifieke sneltoets. Als een sneltoets niet geconfigureerd is, verschijnt er een melding "Slot unconfigured".

### AI Clipboard
Verwerkt klembordinhoud via AI. Opent een dialoog die de huidige klembordtekst toont en je een instructie laat typen voor hoe het verwerkt moet worden. Het resultaat wordt ingevoegd op de cursorpositie.

### AI Voice
Spraak-naar-AI. Tik om opname te starten, tik nogmaals om te stoppen. De getranscribeerde tekst wordt door de AI verwerkt volgens de actieve voice-modus, en het resultaat wordt ingevoegd op de cursorpositie.

### AI Conversation
Opent het volledige gespreksscherm voor multi-turn chat met de AI. Een oranje stip verschijnt wanneer er ongelezen herinneringen zijn.

### AI Actions
Opent de AI Actions / MCP-dialoog. Dit is een tool-use interface waar de AI tools kan aanroepen (agenda, herinneringen, navigatie, bellen, SMS, etc.) in een meerstaps gesprek.

#### Technisch

| Sneltoets | KeyCode | Handler | Voorkeursleutels |
|---|---|---|---|
| AI Assist | -302 | `handleAiAssist()` | `ai_model`, `ai_instruction` |
| Sneltoets 1-4 | -304 t/m -307 | `handleAiSlot(n)` | `ai_slot_{n}_model`, `ai_slot_{n}_instruction` |
| AI Clipboard | -308 | `showAiClipboardDialog()` | `ai_model` |
| AI Voice | -309 | `startAiVoiceRecognition()` | `ai_voice_model`, `ai_voice_mode`, `ai_voice_engine` |
| AI Conversation | -310 | `showAiConversationActivity()` | `ai_conversation_model` |
| AI Actions | -311 | `showAiActionsDialog()` | `ai_mcp_model` |

Alle AI-sneltoetsen zijn geblokkeerd in wachtwoordvelden. Annuleren werkt via `AiCancelRegistry`: tikken op een sneltoets terwijl diezelfde sneltoets bezig is, annuleert het lopende verzoek. Undo wordt bijgehouden per sneltoets via `saveAiUndo()` / `hasAiUndo()`.

Cloud fallback badge: AI Assist, Sneltoets 1-4 en AI Voice krijgen een rode `DotBadgeDrawable` stip wanneer cloud fallback actief is.

---

## Lang indrukken

### AI Assist (lang indrukken)
Opent de AI Instruction-dialoog. Hier kun je:
- Het AI-model kiezen (cloud presets, cloudmodellen, Ollama, OpenAI-compatible)
- De systeeminstructie/prompt bewerken
- Bij preset-modellen is het instructieveld vergrendeld (alleen-lezen)

### Sneltoets 1-4 (lang indrukken)
Opent de Slot Config-dialoog voor die specifieke sneltoets. Hier kun je:
- Een model kiezen voor deze sneltoets
- Een eigen instructie instellen
- Bij preset-modellen is het instructieveld vergrendeld

### AI Voice (lang indrukken)
Opent de Voice Mode-dialoog. Hier kun je:
- Kiezen uit ingebouwde voice-modi
- Eigen voice-modi beheren
- Het voice-model selecteren (apart van het hoofd-AI-model)
- De speech engine kiezen (Whisper of Google)
- "Reply to clipboard" starten: spraakopname met een speciale antwoordprompt op basis van klembordinhoud

### AI Conversation (lang indrukken)
Opent een bevestigingsdialoog om alle herinneringen als gelezen te markeren. Toont het aantal ongelezen herinneringen met de opties "Mark as read" en "Cancel". Wist de oranje badge-stip.

### AI Clipboard (lang indrukken)
Geen speciaal lang-indruk gedrag.

### AI Actions (lang indrukken)
Geen speciaal lang-indruk gedrag.

#### Technisch

| Sneltoets | Lang-indruk functie |
|---|---|
| AI Assist | `showAiInstructionDialog()` |
| Sneltoets 1-4 | `showSlotConfigDialog(slotNumber)` |
| AI Voice | `showAiVoiceModeDialog()` |
| AI Conversation | `showMarkAllRemindersReadDialog()` |
| AI Clipboard | geen (valt door naar `KeyCode.UNSPECIFIED`) |
| AI Actions | geen (valt door naar `KeyCode.UNSPECIFIED`) |

Alle dialogen worden geopend via `AiDialogComponentsKt` en getoond als IME Compose-dialogen via `showImeComposeDialog()`. Modelselectie in de dialogen gebruikt dezelfde `loadCloudPresets()`, `loadCloudModels()`, `cachedOllamaModels()` en `cachedOpenAiCompatibleModels()` functies als de settings-pagina.

---

## Widget

Het homescreen-widget is een snelle toegangsbalk met drie knoppen:

**Mic (Voice)**
Start spraakopname, identiek aan de AI Voice-sneltoets.

**Chat**
Opent een nieuw gesprek in het gespreksscherm.

**Execute**
Start spraakopname in "execute/MCP-modus". Gesproken opdrachten worden uitgevoerd als acties (agenda, timer, navigatie, etc.).

Het widget heeft geen configuratie-opties. Het is horizontaal schaalbaar (minimaal 3 cellen breed, 1 cel hoog).

#### Technisch

| Component | Bestand |
|---|---|
| Widget provider | `DeskdropWidget.kt` (extends `AppWidgetProvider`) |
| Layout | `res/layout/widget_deskdrop.xml` (3 `ImageButton`s in horizontale `LinearLayout`) |
| Metadata | `res/xml/widget_deskdrop_info.xml` (180x40dp, geen auto-update) |
| Achtergrond | `res/drawable/widget_background.xml` (donker semi-transparant, 16dp ronde hoeken) |
| Execute icoon | `res/drawable/ic_widget_execute.xml` (oranje bliksemschicht) |

Elke knop stuurt een `PendingIntent.getBroadcast()` met een custom action terug naar `DeskdropWidget.onReceive()`. Daar wordt de juiste Activity gestart:
- Mic/Execute: `VoiceTrampolineActivity` (met `ACTION_TYPE_EXECUTE` extra voor execute-modus)
- Chat: `ConversationActivity` (met `ACTION_NEW_CHAT` extra)

---

## AI Tools (beschikbaar via AI Actions en conversation)

| Tool | Wat het doet | Gate |
|---|---|---|
| `calculator` | Rekensom uitvoeren | Altijd |
| `get_datetime` | Huidige datum en tijd opvragen | Altijd |
| `unit_convert` | Eenheden omrekenen (lengte, gewicht, temperatuur, etc.) | Altijd |
| `battery_info` | Batterijpercentage en oplaadstatus | Altijd |
| `device_info` | Telefoonmodel, Android-versie, vrije opslag | Altijd |
| `read_clipboard` | Klembordinhoud lezen | Acties |
| `fetch_url` | Webpagina ophalen en als tekst teruggeven | Netwerk |
| `web_search` | Zoeken via Brave/Tavily Search | Netwerk |
| `weather` | Weer opvragen via wttr.in | Netwerk |
| `set_timer` | Timer zetten in de Klok-app | Acties |
| `open_app` | App openen op naam of package name | Acties |
| `set_reminder` | Herinnering plannen (notificatie op gekozen tijdstip) | Acties |
| `calendar` | Agenda-items lezen, aanmaken, wijzigen en verwijderen | Acties |
| `navigate` | Navigatie starten naar een bestemming | Acties |
| `phone_call` | Telefoon-dialer openen met nummer | Acties |
| `send_sms` | SMS-app openen met nummer en bericht | Acties |
| `contact_lookup` | Contacten zoeken op naam | Acties |

#### Technisch

Tools met gate "Altijd" zijn altijd beschikbaar. "Netwerk" vereist dat "Allow tools that access the internet" is ingeschakeld. "Acties" vereist dat "Allow tools that perform actions" is ingeschakeld.

Legacy tools staan in `AiToolRegistry.kt`. Nieuwe tools implementeren de `AiTool` interface en worden geregistreerd in `AiToolCatalog.kt`. De tool-calling loop ondersteunt maximaal 5 rounds per beurt, zodat de AI meerdere tools achter elkaar kan aanroepen (bijv. contact opzoeken en dan bellen).

Tools die Android-data benaderen (agenda, contacten) vereisen de bijbehorende runtime permissions (`READ_CALENDAR`, `WRITE_CALENDAR`, `READ_CONTACTS`). Als de permissie niet is gegeven, opent de tool automatisch de app-instellingen en geeft een foutmelding.

`fetch_url` blokkeert interne/privé IP-adressen (loopback, link-local, site-local) ter bescherming tegen SSRF-aanvallen. `phone_call` en `send_sms` openen alleen de dialer/SMS-app; de gebruiker moet zelf op bellen/versturen drukken.

---

## Beveiliging

### API-key opslag
Alle API-sleutels (Gemini, Groq, OpenRouter, Anthropic, OpenAI, Tavily, Brave Search, OpenAI-compatible, MCP bearer tokens) worden versleuteld opgeslagen via Android's `EncryptedSharedPreferences` met AES-256-GCM encryptie. Sleutels worden nooit gelogd en bestaan alleen in-memory tijdens gebruik. MCP bearer tokens die eerder als plaintext in de MCP-configuratie stonden, worden automatisch gemigreerd naar versleutelde opslag.

### SSRF-bescherming (Server-Side Request Forgery)
De `fetch_url` tool, waarmee de AI webpagina's kan ophalen, blokkeert verzoeken naar privé en interne IP-adressen. Loopback (127.0.0.0/8), link-local (169.254.0.0/16) en site-local (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) adressen worden geweigerd. Dit voorkomt dat een prompt injection het model kan misbruiken om je lokale netwerk te scannen of cloud-metadata endpoints te benaderen.

### Prompt injection mitigatie
Tools die acties uitvoeren op het apparaat (agenda schrijven, timer zetten, bellen, SMS'en, navigeren, klembord lezen) zijn afgeschermd achter de "Allow tools that perform actions" schakelaar. Deze staat standaard uit. Zonder deze instelling kan een kwaadaardige prompt het model niet instrueren om acties uit te voeren.

Specifiek is `read_clipboard` achter de acties-gate geplaatst om een exfiltratieketen te voorkomen: zonder deze gate zou een prompt injection het model kunnen instrueren om het klembord te lezen en de inhoud via `fetch_url` naar een externe server te sturen.

### Geen data-backup
`android:allowBackup="false"` is ingesteld in het Android Manifest. Dit voorkomt dat app-data (inclusief API-sleutels, gesprekken en instellingen) via ADB-backup of cloudback-up wordt geëxporteerd.

### Gesprekken en herinneringen
Gesprekgeschiedenis wordt opgeslagen in `noBackupFilesDir`, een app-privé directory die niet toegankelijk is voor andere apps en uitgesloten is van cloudback-ups. Herinneringen worden op dezelfde manier opgeslagen.

### Audiobestanden
Na spraaktranscriptie via Whisper wordt het WAV-audiobestand direct verwijderd van het apparaat. Er blijven geen audiobestanden rondslingeren tussen opnames.

### Streaming-bescherming (OOM-preventie)
Alle streaming AI-responses worden gelezen met een per-regel limiet van 1 MB. Als een model (met name kleinere lokale modellen) hallucineert en een ononderbroken stroom tekst zonder regeleindes stuurt, wordt de regel afgekapt in plaats van het geheugen vol te pompen. Niet-streaming responses zijn begrensd tot maximaal 10 MB.

### Invoervalidatie
- Telefoonnummers worden gevalideerd tegen het patroon `^\+?[0-9()\- ]{3,20}$` voordat de dialer of SMS-app wordt geopend
- URI-parameters worden gecodeerd via `Uri.encode()` in alle tools die intents aanmaken (navigatie, bellen, SMS)
- Alle ContentResolver-queries gebruiken geparametriseerde selectie-argumenten (geen SQL-injectie mogelijk)
- Contact-lookups zijn beperkt tot maximaal 3 resultaten met maximaal 2 telefoonnummers en 1 e-mailadres per contact, om onnodige datalekken naar het LLM-context te minimaliseren

### Wachtwoordvelden
Alle AI-sneltoetsen zijn geblokkeerd in wachtwoordvelden. Wanneer een tekstveld als wachtwoordveld is gemarkeerd, wordt er een melding getoond en weigert de sneltoets te werken. Dit voorkomt dat wachtwoorden naar een AI-model worden gestuurd.

### Acties vereisen gebruikersbevestiging
Tools die zichtbare gevolgen hebben op het apparaat zijn zo ontworpen dat de gebruiker de laatste stap zelf moet bevestigen:
- `phone_call` opent de dialer, de gebruiker drukt zelf op bellen
- `send_sms` opent de SMS-app met vooringevuld bericht, de gebruiker drukt zelf op versturen
- `navigate` opent de navigatie-app, de gebruiker start zelf de route
- `open_app` opent de app direct (geen bevestiging, maar geen destructieve actie)
- `set_timer` opent de Klok-app met de timer ingesteld

Agenda-bewerkingen (`calendar` met actie add/update/delete) en herinneringen (`set_reminder`) worden direct uitgevoerd na tool-aanroep, zonder tussenliggende bevestiging.

### Exported components
Alle exported Android-services zijn beschermd met systeempermissies:
- `LatinIME` vereist `BIND_INPUT_METHOD` (alleen het systeem kan binden)
- `SpellCheckerService` vereist `BIND_TEXT_SERVICE`
- `VoiceTileService` vereist `BIND_QUICK_SETTINGS_TILE`

### Tool-calling limieten
De tool-calling loop is beperkt tot maximaal 5 rounds per AI-beurt. Dit voorkomt dat een model in een oneindige lus tools blijft aanroepen.

### URL-scheme validatie
Alleen `http://` en `https://` URL-schemes worden geaccepteerd voor `fetch_url`. Schemes zoals `file://`, `content://` en `javascript:` worden geweigerd.

#### Technisch overzicht

| Maatregel | Implementatie | Bestand |
|---|---|---|
| API-key encryptie | `EncryptedSharedPreferences` (AES-256-GCM) | `SecureApiKeys.kt` |
| SSRF-blokkade | `InetAddress.isLoopback/isLinkLocal/isSiteLocal` check | `AiToolRegistry.kt` |
| Clipboard gating | `read_clipboard` in `ACTIONS` gate | `AiToolRegistry.kt` |
| Backup uitgeschakeld | `allowBackup="false"` | `AndroidManifest.xml` |
| WAV cleanup | `wavFile.delete()` na transcriptie | `VoiceRecordingService.kt` |
| Streaming OOM-preventie | `boundedLines()` met 1MB per-regel limiet | `AiServiceSync.kt` |
| Response limiet | `readBounded()` met 10MB max | `AiServiceSync.kt` |
| Telefoonnummer-validatie | Regex `^\+?[0-9()\- ]{3,20}$` | `PhoneCallTool.kt`, `SendSmsTool.kt` |
| SQL-injectie preventie | Geparametriseerde `selectionArgs` | `CalendarTool.kt`, `ContactLookupTool.kt` |
| URI-injectie preventie | `Uri.encode()` op alle parameters | Alle tool-bestanden |
| Wachtwoordveld blokkade | Check op `inputType` flags | `InputLogic.java` |
| Tool-loop limiet | `maxRounds = 5` | `AiServiceSync.kt` |
| Contact data minimalisatie | Max 3 contacten, 2 nummers, 1 e-mail | `ContactLookupTool.kt` |
| Exported service bescherming | `BIND_INPUT_METHOD` etc. | `AndroidManifest.xml` |
| MCP token migratie | Plaintext naar `SecureApiKeys` bij laden | `McpRegistry.kt` |
