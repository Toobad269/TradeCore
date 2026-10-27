# Befehle und Berechtigungen

`/markt` öffnet das persönliche Menü. Alle folgenden administrativen Unterbefehle benötigen `marktwerk.admin` (standardmäßig OP), auch an der Konsole:

| Befehl | Wirkung |
|---|---|
| `/markt menu <UUID> on` | Persönliches Menü-Item dauerhaft aktivieren |
| `/markt menu <UUID> off` | Deaktivieren, Item entfernen, Website-Zugänge widerrufen |
| `/markt money <UUID\|server> set <Betrag>` | Guthaben administrativ setzen; Änderung wird gebucht |
| `/markt money <UUID\|server> give <Betrag>` | Geld administrativ ausgeben |
| `/markt money <UUID\|server> take <Betrag>` | Geld administrativ entziehen, ohne negative Konten |
| `/markt recovery` | Unklare physische Vorgänge auflisten |
| `/markt recovery inspect <ID>` | Vollständiges Journal einschließlich Inventarbelegen zeigen; vorzugsweise Konsole verwenden |
| `/markt recovery resolve <ID> applied <Prüfvermerk>` | Nachgewiesene physische Änderung fachlich abschließen |
| `/markt recovery resolve <ID> not-applied <Prüfvermerk>` | Nachgewiesen unveränderten Vorgang stornieren, Reservierungen zurückgeben |
| `/markt backup` | Konsistente SQLite-Sicherung mit Zeitstempel im Plugin-Verzeichnis |
| `/markt votes reset <MATERIAL>` | Stimmen und Ergebnis eines Artikels zurücksetzen |
| `/markt revoke <UUID>` | Alle API-Codes und Tokens dieser UUID widerrufen |

Beträge werden in der Hauptwährung eingegeben, beispielsweise `12,50` oder `12.50`. Genau zwei Nachkommastellen sind höchstens erlaubt. Negative Werte sind ungültig. `set 0` ist zulässig. Spieler-UUIDs müssen bereits ein Konto besitzen. Kontonamen werden absichtlich nicht zur administrativen Zielauflösung verwendet. Prüfvermerke werden mit dem Administratornamen dauerhaft gespeichert.

| Berechtigung | Standard | Bereich |
|---|---|---|
| `marktwerk.use` | true | Menü und Menü-Item |
| `marktwerk.transfer` | true | Überweisungen |
| `marktwerk.server` | true | Server-Shop |
| `marktwerk.chest` | true | Shops finden, kaufen und eigene Kisten-Shops verwalten |
| `marktwerk.auction` | true | Sofortkauf, Einstellung und Rückzug |
| `marktwerk.claim` | true | Dauerhaftes Abholfach |
| `marktwerk.vote` | true | Experimentelle Preisabstimmungen |
| `marktwerk.api` | true | Verknüpfung und API-Zugriff, zusätzlich `use` und `chest` |
| `marktwerk.admin` | op | Alle administrativen Unterbefehle |

Module prüfen ihre Aktivierung unabhängig von den Berechtigungen. Jeder Menüaufruf und jede Antwort wird neu auf `use` geprüft; die fachliche Aktion prüft ihre Bereichsberechtigung nochmals. Die Aktivierung des persönlichen Menü-Items ist kein Ersatz für diese Rechte. Der OP-Sonderzugang umgeht ausschließlich die persönliche Item-Aktivierung, keine entzogenen Bereichsrechte.
