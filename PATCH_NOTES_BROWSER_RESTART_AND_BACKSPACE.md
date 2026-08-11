# Browser-Restart-Taste & Backspace-Long-Press Patch

## Neue Taste „Browser neu starten“ (⟳)
- Neuer Button in der Browser-Toolbar neben der bestehenden Reload-Taste (↻), optisch im gleichen Stil (`smallButton`, folgt dem Theme über `styleSmallButton`).
- Öffnet **niemals** eine externe Browser-App und öffnet keine zusätzliche Activity — bleibt vollständig innerhalb des `InputMethodService`.
- `restartBrowser()`:
  - entfernt die aktuelle WebView sauber aus dem Layout (an der ursprünglichen Position gemerkt),
  - lässt `createWebView()` die alte, bereits abgehängte Instanz zerstören (kein Leak) und eine neue erzeugen,
  - fügt die neue WebView wieder an derselben Stelle ein und lädt die zuletzt angezeigte Seite (`lastWebUrl`) neu,
  - aktualisiert das Adressfeld.
- Da `createWebView()` selbst vor jedem Neuaufbau eine evtl. vorhandene WebView zerstört, entstehen auch bei mehrfachem, schnellem Drücken keine doppelten WebView-Instanzen/Tabs und keine Memory-Leaks.
- Ist beim Drücken aus irgendeinem Grund keine WebView vorhanden, erzeugt derselbe Codepfad einfach eine neue — die Taste „öffnet“ den Browser in diesem Fall.

## Backspace: gedrückt halten zum wiederholten Löschen
- Die Backspace-Taste nutzt jetzt einen eigenen `OnTouchListener` (`addBackspaceKey`) statt eines einfachen Klick-Listeners:
  - `ACTION_DOWN`: löscht sofort ein Zeichen/eine Markierung (normales kurzes Tippen) und startet einen verzögerten Wiederhol-Timer.
  - Nach ca. 400 ms beginnt automatisches, kontinuierliches Löschen; das Intervall verkürzt sich schrittweise bis zu einem Minimum (leicht beschleunigend, „modernes“ Gefühl).
  - `ACTION_UP` / `ACTION_CANCEL`: stoppt das automatische Löschen sofort und entfernt alle ausstehenden Handler-Callbacks.
- Alle Handler-Callbacks werden zusätzlich in `onFinishInputView`, `onWindowHidden`, `onStartInputView` und `onDestroy` explizit gestoppt, um Leaks oder Löschen nach Tastatur-/App-Wechsel zu verhindern.
- `delete()` nutzt weiterhin die vorhandene `InputConnection` (`deleteSurroundingTextInCodePoints`/`deleteSurroundingText`) und behandelt zusätzlich:
  - eine aktive Markierung in der Ziel-App (wird zuerst über `getSelectedText`/`commitText("")` gelöscht, bevor auf zeichenweises Löschen zurückgegriffen wird),
  - eine ungültige/fehlende `InputConnection` (kein Absturz),
  - leeren Text (kein Absturz, da `deleteSurroundingText` am Textanfang ein No-Op ist).
- Das Adressfeld (`EditText url`) und der eingebettete Web-Inhalt (`injectBackspaceIntoWeb`) hatten die Markierungs-Logik bereits; daran wurde nichts geändert.

## Keine Auswirkung auf
- Gboard-ähnliches Design, QWERTZ-Layout, restliche Tasten, Emoji/Sonderzeichen/Zahlen, Zwischenablage, Einstellungen.
- Die 🌐-Taste (öffnet weiterhin nur das interne Adressfeld/den internen Browser, keine externe App).
- Bestehende WebView-Lifecycle-Fixes (Pause/Resume beim Ein-/Ausblenden der Tastatur, Wiederherstellung nach `onRenderProcessGone`).
- Gradle/Manifest/GitHub-Actions-Workflow: keine neuen Abhängigkeiten oder Berechtigungen nötig (`Handler`/`Looper` sind Standard-Android-SDK).

## Nachbesserung (Bugfixes)

### Browser-Fenster verschwindet nach „⟳“ und lässt sich nicht wieder öffnen
Ursache: Die vorherige Implementierung hat die WebView komplett aus dem Layout entfernt, zerstört und eine neue Instanz wieder eingehängt. Das Entfernen/Neu-Einhängen einer WebView innerhalb des speziellen Overlay-Fensters eines `InputMethodService` ist unzuverlässig und konnte dazu führen, dass die neue WebView nie sichtbar/aktiv wurde.

Fix: `restartBrowser()` entfernt die WebView jetzt im Normalfall gar nicht mehr aus dem Layout. Stattdessen wird dieselbe Instanz an Ort und Stelle zurückgesetzt:
- laufendes Laden stoppen,
- Navigations-Stack (`clearHistory()`) leeren,
- die zuletzt angezeigte Seite erneut laden — eine echte Navigation erzeugt dabei ohnehin einen komplett neuen JS/DOM-Kontext, Cookies/Login bleiben erhalten.

Nur falls dieser In-Place-Reset tatsächlich eine Exception wirft (kaputter Renderer), wird als Fallback die alte destroy-and-recreate-Logik verwendet — und auch dann wird exakt eine Ersatz-Instanz an derselben Stelle eingesetzt (keine doppelten WebViews).

### Backspace-Wiederholung zu langsam
Die alte Beschleunigungskurve startete bei 400 ms pro Zeichen und brauchte mehrere Sekunden, um auf ihre schnellste Rate (40 ms) zu kommen. Jetzt:
- Auslöseverzögerung vor Beginn der Wiederholung: 350 ms (unverändert spürbar, aber kein Teil der Löschgeschwindigkeit mehr),
- Wiederholung startet bereits bei 90 ms/Zeichen,
- beschleunigt in 8-ms-Schritten bis zu einem Minimum von 20 ms/Zeichen (~50 Zeichen/Sekunde),
- die volle Geschwindigkeit wird dadurch schon nach ca. einer halben Sekunde Halten erreicht statt nach mehreren Sekunden.

## Weitere Nachbesserung: Browser bleibt nach Schließen/Wiederöffnen leer

Ursache: Beim Wiederöffnen der Tastatur (nachdem sie vorher geschlossen/das IME-Fenster ausgeblendet wurde) wurde die vorhandene WebView lediglich per `onResume()` fortgesetzt, ohne die Seite neu zu laden. Eine pausierte/im Hintergrund befindliche WebView kann ihren gerenderten Zustand verlieren (Renderer-Suspend, verworfener Tab durch das System), ohne dass dabei `onRenderProcessGone` ausgelöst wird — sie bleibt dann sichtbar, aber leer.

Fix: Ein neuer Zustand `browserNeedsRefresh` wird gesetzt, sobald die WebView pausiert wird (`onFinishInputView`, `onWindowHidden`). Sobald die Tastatur wieder erscheint (`onStartInputView`, `onWindowShown`), wird bei gesetztem Flag zwangsweise `https://www.google.com/` neu geladen (`refreshBrowserToDefault()`), statt sich auf ein sauberes automatisches Wiederaufwachen der WebView zu verlassen. Dadurch:
- ist der Browser nach jedem Schließen-und-Wiederöffnen garantiert nicht mehr leer,
- zeigt er zuverlässig wieder google.com,
- bleibt aber innerhalb einer durchgehend sichtbaren Tastatursitzung (z. B. beim Wechsel zwischen Eingabefeldern ohne dass die Tastatur wirklich verschwindet) weiterhin auf der zuletzt besuchten Seite, da `browserNeedsRefresh` nur beim tatsächlichen Pausieren/Ausblenden gesetzt wird.

## Nachbesserung: 10s Freeze / leerer Browser / Erholung nach ~1 Minute

Ursache: Der vorherige Ansatz hat die WebView beim Schließen der Tastatur nur pausiert (`onPause()`), nicht zerstört, in der Annahme, sie beim Wiederöffnen einfach fortsetzen zu können. In der Praxis ist das riskant: Während die Tastatur ausgeblendet ist, kann Android den zugehörigen WebView-Renderer-Prozess jederzeit im Hintergrund beenden, ohne dass `onRenderProcessGone` ausgelöst wird. Wird diese „zombie“-WebView dann beim Wiederöffnen erneut angefasst (`onResume()`, `loadUrl()`), muss das System erst einen komplett neuen, aber mit niedriger Priorität eingestuften Renderer-Prozess starten und dabei auf dessen Scheduling warten — das erklärt das mehrsekündige Einfrieren, die anschließend leere Seite und die späte Erholung.

Fix: Die WebView wird jetzt beim tatsächlichen Ausblenden des Tastaturfensters (`onWindowHidden()`) vollständig und sauber zerstört (`destroyBrowser()`), statt nur pausiert. Beim nächsten Anzeigen (`onStartInputView()`) wird zuverlässig eine komplett neue, frische WebView-Instanz aufgebaut und `https://www.google.com/` geladen — es wird nie versucht, eine möglicherweise bereits vom System beendete alte Instanz wiederzubeleben. Das reine Wechseln zwischen Eingabefeldern, während die Tastatur durchgehend sichtbar bleibt (`onFinishInputView()` ohne tatsächliches Ausblenden), zerstört die WebView dagegen weiterhin nicht, sodass der Browser innerhalb einer Sitzung nicht unnötig zurückgesetzt wird.

## Nachbesserung: Browser funktioniert nur beim allerersten Öffnen

Ursache: Die Wiederaufbau-Logik für den Browser steckte ausschließlich in `onStartInputView()`. Android ruft aber nicht bei jedem Schließen/Wiederöffnen-Zyklus zuverlässig erneut `onStartInputView()` auf — je nach Android-Version/Gerät kann es sein, dass bei fortgesetzter Eingabesitzung nur `onWindowHidden()`/`onWindowShown()` feuern, ohne dass `onStartInputView()` dazwischen erneut aufgerufen wird. Da `onWindowHidden()` den Browser zerstört, aber nur `onStartInputView()` ihn wieder aufgebaut hat, blieb er nach dem ersten Zyklus dauerhaft leer.

Fix: Die Wiederaufbau-Logik wurde in eine gemeinsame Methode `ensureBrowserActive()` ausgelagert, die sowohl aus `onStartInputView()` als auch aus `onWindowShown()` aufgerufen wird. Damit wird der Browser zuverlässig neu aufgebaut, egal welcher der beiden Callbacks beim Wiederöffnen tatsächlich ausgelöst wird.

## Nachbesserung: Freeze/leerer Browser tritt schon bei normalem Fokuswechsel auf (nicht nur beim Schließen)

Klarstellung des Nutzers: Das Problem tritt nicht erst beim bewussten Schließen der Tastatur auf, sondern schon bei jedem kurzen Verlassen eines Textfelds (Tastatur fährt ein) und Zurückkehren (Tastatur fährt wieder aus) — ein sehr häufiger, alltäglicher Vorgang.

Der bisherige Ansatz („WebView bei jedem Verstecken komplett zerstören und beim Wiederzeigen komplett neu aufbauen“) war dafür ungeeignet: Ein voller WebView-Neuaufbau bei jedem einzelnen Fokuswechsel ist spürbar teuer und hat genau das Einfrieren/die leere Seite verursacht, die eigentlich behoben werden sollte.

Neuer, zweistufiger Ansatz:
1. **Normalfall (kurzes Verstecken/Wiederzeigen):** Die WebView wird nur noch pausiert (`onPause()`) und beim Wiederzeigen fortgesetzt (`onResume()`) — keine Zerstörung, kein Neuladen, kein spürbarer Overhead. Zusätzlich wurde die Renderer-Prioritätsrichtlinie von einer dauerhaft niedrigen Priorität (`RENDERER_PRIORITY_BOUND`, gar nicht an Sichtbarkeit gekoppelt) auf eine adaptive Richtlinie umgestellt: `RENDERER_PRIORITY_IMPORTANT` mit `waivedWhenNotVisible=true` — der Renderer ist geschützt, solange die Tastatur sichtbar ist, und wird von Android selbst automatisch heruntergestuft, sobald sie es nicht ist, ganz ohne dass die App das manuell nachhalten muss.
2. **Fehlerfall (Renderer tatsächlich vom System beendet):** `onRenderProcessGone()` setzt jetzt zuverlässig ein Flag (`browserRendererDead`), auch wenn das während des Verstecktseins passiert — vorher wurde die Reparatur in genau diesem Fall (Absturz während die Tastatur unsichtbar war) stillschweigend übersprungen, was die eigentliche Ursache für die dauerhaft leere Seite war. Beim nächsten Anzeigen prüft `ensureBrowserActive()` dieses Flag und repariert die WebView gezielt nur dann per vollständigem Neuaufbau (`recoverDeadBrowser()`).

Ergebnis: Normales Fokuswechseln zwischen Feldern ist wieder schnell und ohne Aussetzer, während ein tatsächlich abgestürzter Renderer trotzdem zuverlässig repariert wird, statt dauerhaft leer zu bleiben.

Hinweis: Der Browser lädt beim Wiederzeigen jetzt nicht mehr zwangsweise google.com neu, sondern behält die zuletzt besuchte Seite bei (wie bei einem normalen Browser-Tab) — außer im Reparaturfall, dort wird ebenfalls die zuletzt besuchte Seite (nicht zwingend google.com) wiederhergestellt. Falls stattdessen weiterhin gewünscht ist, dass beim (bewussten) Schließen/Wiederöffnen immer explizit google.com erscheint, bitte kurz Bescheid geben.
