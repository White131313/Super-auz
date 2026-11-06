# Super-Auz — brief pentru aplicația iPhone

Citește tot fișierul înainte să scrii cod. Utilizatorul (BalasaC, vorbește română, direct, fără răbdare pentru încercări pe ghicite) vrea o aplicație iPhone care merge din prima.

## Ce vrea
Aplicație iPhone (iPhone 15 Pro Max) pentru căști (Razer Barracuda, Bluetooth, izolează bine):
1. **Ascultare continuă**: apasă o dată, ascultă non-stop, fără să mai apese.
2. **Recunoaștere vocală maghiară** (hu-HU) → **traducere în română** → **citită cu voce în căști** (ro-RO). Buton de inversare: română → maghiară.
3. **Auz amplificat**: ce aude microfonul (vocea celui din față) amplificat în căști, filtrat pe voce (bandă ~300–3800 Hz, accent ~2,2 kHz, compresor, limitator), volum 1×–10×. Scade automat (ducking ~0.12) cât vorbește traducerea.
4. **Un singur microfon** pentru amplificare + recunoaștere — exact asta nu se putea pe web/Android.

## Ce s-a încercat deja (nu repeta)
- Web (GitHub Pages, `traducere.html`, `index.html`): merge traducerea în Chrome Android, dar amplificare + recunoaștere simultan = conflict de microfon / microfonie. Pe iPhone Safari nu există recunoaștere vocală web.
- Android nativ (`android/`, build pe GitHub Actions, release `latest`): pe Motorola G07 serviciile `SpeechRecognizer` (Google) pornesc, raportează „beginning of speech”, dar nu întorc niciodată text. Abandonat.
- Google maschează înjurăturile cu `***` — motiv în plus pentru motor propriu mai târziu.

## Plan tehnic iOS (SwiftUI)
- `AVAudioSession` category `.playAndRecord`, mode `.default` (NU `.voiceChat`, ca să nu taie vocea), options `[.allowBluetoothA2DP, .allowBluetooth, .defaultToSpeaker]`. Atenție: cu microfonul căștii Bluetooth (HFP) calitatea scade; preferă microfonul iPhone-ului (`setPreferredInput` built-in mic) și ieșirea pe căști.
- `AVAudioEngine`: un singur `inputNode` →
  - tap pe input → `SFSpeechAudioBufferRecognitionRequest` (locale `hu-HU` / `ro-RO`, `shouldReportPartialResults = true`, `requiresOnDeviceRecognition` dacă e disponibil);
  - lanț de amplificare: `AVAudioUnitEQ` (highpass 300, lowpass 3800, parametric +9 dB la 2200) → `AVAudioUnitEffect` dynamics/limiter → mixer → output. Gain din slider.
- Segmentare: după ~1,2 s de liniște sau rezultat `isFinal`, trimite fraza la traducere, apoi repornește task-ul de recunoaștere (limita de ~1 min per task la Apple).
- Traducere v1: Apple Translation framework (`TranslationSession`, iOS 18+) dacă are hu↔ro offline; altfel fallback MyMemory `https://api.mymemory.translated.net/get?q=...&langpair=hu|ro` (gratuit, calitate medie).
- Voce: `AVSpeechSynthesizer` cu voce `ro-RO` / `hu-HU`; în timpul vorbirii: ducking pe amplificare și ignoră rezultatele recunoașterii (ca să nu se audă pe sine).
- Info.plist: `NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription`.
- UI simplă, întunecată, în română: direcție (Maghiară → Română), „Ai spus”, „Traducere”, buton mare pornește/oprește, comutator „Auz amplificat”, slider volum, rând de stare cu erori clare.

## Cum lucrezi
- Ești în Claude Code local pe Mac-ul lui, cu Xcode instalat. Creează proiectul (ex. cu `xcodegen` dacă e disponibil, sau un proiect Xcode generat de mână), **compilează singur cu `xcodebuild`** și repară toate erorile ÎNAINTE să-i ceri ceva.
- Instalează pe iPhone-ul conectat prin cablu (`xcodebuild ... -destination 'platform=iOS,id=<udid>'` + `xcrun devicectl device install app`). Semnare: Apple ID gratuit (Personal Team) — dacă lipsește, ghidează-l pas cu pas în Xcode → Settings → Accounts. Aplicația expiră după 7 zile cu cont gratuit.
- Doar partea cu vocea o testează el. Cere-i feedback concret (ce scrie pe ecran, ce aude).

## Pe viitor (nu acum)
- Whisper on-device (offline, fără cenzură, același motor și pe Android), traducere offline, model pe dialectul maghiar local, vânzare cu preț unic fără abonament.
