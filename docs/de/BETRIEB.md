# Speicherung, Schutz und Wiederherstellung

## Threads und Grenzen

Ein dedizierter SQLite-Worker führt sämtliche JDBC-Abfragen und -Transaktionen seriell aus. HTTP-Worker und Bukkit-Menüs reichen nur Aufgaben ein. Bukkit-Inventare, Welten, Chunks, Item-Serialisierung, Formcallbacks und Rechteprüfungen werden auf den Bukkit-Hauptthread zurückgeführt. Die unveränderlichen UUID-/Namensdaten der synchronen Vault-Schnittstelle werden am Aufruf erfasst. Der Worker wartet nicht auf Bukkit.

SQLite verwendet WAL, `synchronous=FULL`, `foreign_keys=ON` und `busy_timeout=10000`. Durch den einen Worker werden parallele Käufe in eine eindeutige Reihenfolge gebracht. Alle Geldmutationen und ihre Buchungsbelege liegen innerhalb einer Transaktion. Auktionen besitzen einen dauerhaften Zustand, Kaufoperationen eindeutige IDs. Wiederholungen abgeschlossener Käufe oder Überweisungen mit gleicher Operations-ID werden nicht noch einmal ausgeführt.

**Eine Datenbanktransaktion macht Minecraft-Inventare nicht atomar.** Vor jeder Ein- oder Ausgabe wird ein PREPARED-Journal mit Belegen geschrieben. Der Spieler beziehungsweise die Shop-Kiste wird währenddessen gesperrt. Erst danach ändert der Hauptthread das Inventar und speichert den Spieler beziehungsweise die Welt; danach wird der DB-Vorgang fachlich abgeschlossen. Ein Absturz zwischen diesen Schritten lässt einen gesperrten Vorgang zurück. Diese Version entscheidet solche Fälle ausdrücklich nicht automatisch, denn aus dem fehlenden COMMITTED-Eintrag lässt sich der physische Zustand nicht sicher ableiten.

## Transaktionsabläufe

| Bereich | Ablauf und Reservierung |
|---|---|
| Überweisung | In einer DB-Transaktion Konten ändern, Ledger und eindeutige Operation schreiben |
| Server-Kauf | Quote erneut prüfen, Käufer belasten, Server-/Bonusrücklage buchen, Anteile reduzieren, Ware ins Abholfach; alles in einer DB-Transaktion |
| Server-Verkauf | Servergeld reservieren (oder expliziten Minting-Modus festhalten), PREPARED; Spielerware entfernen und Spieler speichern; Geld auszahlen, Lager und Anteile erhöhen, COMMITTED |
| Kisten-Kauf | Shop und Quote/Version prüfen, Käufergeld reservieren, PREPARED mit vollständiger Lieferware; Kisteninhalt exakt prüfen, Ware entfernen und Welt speichern; Besitzer vergüten, Käuferware ins Abholfach, COMMITTED |
| Auktions-Einstellung | Limit prüfen, Einstellgebühr reservieren, PREPARED mit kompletter Itemvorlage; Spielerware entfernen und Spieler speichern; ACTIVE-Angebot und Gebührenbuchung, COMMITTED |
| Auktions-Kauf | Nur ACTIVE und noch nicht abgelaufen, Käufer belasten, Verkäufer/Gebühr vergüten, SOLD und Abholfach in einer DB-Transaktion |
| Abgelaufen/zurückgezogen | Status ändern und einmaligen Abholfacheintrag mit eindeutiger Quellen-ID schreiben; in einer DB-Transaktion |
| Abholung | Freien Platz vorab prüfen, Mailboxeintrag HELD und PREPARED mit Inventarbeleg; Items ausgeben und Spieler speichern; Mailbox DELIVERED und COMMITTED |

Ein Inventarbeleg enthält Slot-für-Slot Vorher-/Nachherdaten und SHA-256-Fingerprints. Itemvorlagen sind nicht nur Materialnamen: Paper-Binärdaten einschließlich Verzauberungen, Namen und Lore werden gesichert. Mengen stehen zusätzlich separat und werden beim Zustellen auf reguläre Stapel verteilt.

Vor der physikalischen Änderung wird nach asynchroner Vorbereitung der aktuelle Fingerprint neu geprüft. Ist der Spieler inzwischen offline/tot oder das Inventar verändert, wird ausschließlich dann automatisch storniert, wenn noch keine physische Änderung vorgenommen wurde. Fehler nach einer möglichen Änderung bleiben gesperrt. Gelder werden dabei weder blind ausgezahlt noch blind erstattet. Deaktiviert sich das Plugin während einer Vorbereitung, bleibt der Beleg ebenfalls erhalten.

Die Sperren verhindern normale Inventaränderungen, Drops, Aufnahmen, Handwechsel und verursachten Schaden während eines physischen Vorgangs. Nach einem Neustart werden sie aus PREPARED-Datensätzen wieder aufgebaut. Ein betroffener Spieler darf sein Inventar bis zum Abgleich nicht verändern. Das Menü-Item wird während eines offenen Vorgangs ebenfalls nicht neu ausgegeben oder entfernt, damit die Inventarbelege nicht verfälscht werden.

## Kistenregeln

Es werden ausschließlich Einzelkisten vom Typ CHEST oder TRAPPED_CHEST verbunden. Eine zweite Kiste darf nicht daneben angebaut werden. Fremder Zugriff, Abbau auch durch den Besitzer, Explosionszerstörung, Entzündung, Pistonbewegung und Entity-Blockänderung werden für registrierte Kisten verhindert. Hopper- und andere reguläre InventoryMoveItem-Transporte sind in beide Richtungen gesperrt. Der Besitzer füllt direkt im Kisteninventar nach; während eines Kaufs wird auch er ausgeschlossen. Bereits offene Ansichten werden vor der Entnahme geschlossen.

Für einen Kauf muss der Chunk schon geladen sein. Die Transaktion hält ein Plugin-Chunk-Ticket bis zum Ende; die API lädt nichts nach. Fehlt die Kiste oder ist sie inzwischen doppelt, gibt es keinen Kauf. Ein volles Käuferinventar verhindert den Kauf nicht: die bezahlte Ware bleibt im dauerhaften Abholfach. Ein Shop kann nur ohne offenen reservierten Kauf entfernt werden; die echte Ware bleibt dann unverändert in der Kiste, der Schutz wird erst nach erfolgreicher Datenbankänderung aufgehoben.

Dieser Schutz betrifft ausschließlich Shop-Kisten, keine umgebenden Gebäude und keine Landrechte. Das Plugin kann nicht verhindern, dass ein privilegiertes anderes Plugin, Welteditor, Administrator oder externe Weltdateimanipulation direkt Inventare oder Blöcke verändert und die Bukkit-Ereignisse umgeht. Solche Werkzeuge sind Teil der Vertrauensgrenze. Shop-Kisten nicht mit anderen Automatismen bearbeiten lassen.

## Gezielte Wiederherstellung

1. Betroffenen Handel stoppen, gemeinsame Sicherung von Datenbank, Spieler- und Weltdateien erstellen. Keine leere Datenbank über die bestehende legen.
2. `/markt recovery` zeigt offene IDs. `/markt recovery inspect <ID>` an der Konsole zeigt Typ, Besitzer, reservierte Beträge, Itemvorlage und Inventarbelege. Dabei keine Konsolenausgabe öffentlich teilen; sie kann Item- und Spielerinformationen enthalten, aber keine API-Zugangsdaten.
3. Mit der tatsächlich gespeicherten Spieler- beziehungsweise Kisteninventardatei abgleichen. **Nicht nur den DB-Zustand als Beweis verwenden.** Vorher-/Nachherhash und Slotdaten helfen beim Vergleich. Bei einem gemischten Zustand zunächst die tatsächlichen Inventare unter Wartungsbedingungen eindeutig auf den gewünschten Vorher- oder Nachherzustand bringen.
4. Erst laufende Inventarvorgänge abschließen lassen; administrative Auflösung ist währenddessen gesperrt. Nur nach nachgewiesener kompletter physischer Änderung: `/markt recovery resolve <ID> applied <konkreter Prüfvermerk>`. SERVER_SELL legt den Lagerbestand an und zahlt den reservierten Erlös aus; AUCTION_LIST aktiviert das verwahrte Angebot; CHEST_BUY schreibt Besitzererlös und Käufer-Abholfach; CLAIM markiert die bereits ausgegebene Ware als zugestellt.
5. Nur bei nachgewiesenem Vorherzustand: `not-applied`. Reserviertes Geld wird genau einmal erstattet beziehungsweise zurückgebucht; eine HELD-Abholung wird wieder READY. Es werden dabei **keine** physischen Items erzeugt oder zurückgelegt.
6. Aufgelöste Vorgänge können nicht nochmals aufgelöst werden. Administratorname und Prüfvermerk bleiben im Journal. Sperren lösen sich nach erfolgreichem Abgleich.

Bei Unklarheit nicht raten: gesperrt lassen und Belege sowie gemeinsame Sicherung untersuchen. Das ist ein bewusstes Sicherheitsverhalten mit notwendigem administrativem Aufwand und keine automatische verlustfreie Reparatur beliebiger Teilzustände.

## Datenbankschema und Migration

Schema-Version 1 ist in `PRAGMA user_version` gespeichert. Autoritativ ist `Store.migrate`; ein lesbarer Auszug liegt in [schema.sql](schema.sql). Eine neuere unbekannte Version wird abgelehnt. Migration 0→1 legt die Tabellen atomar an; weitere Migrationen existieren in dieser Erstversion nicht.

| Tabelle | Inhalt und Schlüssel |
|---|---|
| `accounts` | UUID-PK, letzter Name, Kontostand, Menüstatus, kumulierte positive Buchungen |
| `ledger` | Append-only Buchungs-ID, Operation, Konto, Delta, Grund und Unixsekunden |
| `operations` | Operations-ID-PK, Art, Besitzer, Zustand, JSON-Beleg, Zeit und Prüfvermerk |
| `stock` | Material-PK, gesamte serverseitige Lagermenge und rationaler Finanzierungsrest für den Bonus |
| `suppliers` | Material/UUID-PK, exakter rationaler Bestand und Bonusrest |
| `shops` | Shop-UUID, Besitzer, Welt-UUID, Blockposition (eindeutig), Titel, gemeinsame Version und Layout |
| `offers` | Angebots-UUID, Shop-FK mit CASCADE, exakte Vorlage, Menge, Preis, Sichtbarkeit |
| `auctions` | Angebots-/Operations-ID, Besitzer, komplette Ware, Preis, Ablauf und Zustand |
| `mailbox` | Abhol-ID, Besitzer, komplette Ware, READY/HELD/DELIVERED und eindeutige Quellenoperation |
| `votes` | Material/UUID/Runde-PK, Richtung ±1 |
| `vote_results` | Artikel-PK, begrenzter Faktor und letztes Ergebnis |
| `credentials` | SHA-256-PK, Besitzer, CODE/TOKEN und Ablauf; niemals Klartext |

Serverkonto: UUID `00000000-0000-0000-0000-000000000000`; Bonusrücklage: `00000000-0000-0000-0000-000000000001`. Beide sind interne Konten und keine Überweisungsempfänger im Menü. Die Bonusrücklage nicht administrativ entleeren, solange Bruchteilsansprüche bestehen. Ein Konto kann nie negativ werden. Die Summe der exakten Lieferantenbestände muss jeweils dem Server-Lagerbestand entsprechen; die Bonusrestsumme abzüglich des artikelweisen Finanzierungsrests entspricht der ganzzahligen Bonusrücklage. Auch Bonusbruchteile unter einer Untereinheit bleiben nachvollziehbar gespeichert.

## Sicherung und Restore

`/markt backup` verwendet SQLite `VACUUM INTO` auf dem DB-Worker und liefert eine konsistente eigenständige Datenbankdatei. Das ist **keine** atomare Sicherung gleichzeitig laufender Minecraft-Welten. Für eine vollständige Wiederherstellung Server kontrolliert stoppen und `plugins/MarktWerk/`, Weltordner und `playerdata/` gemeinsam kopieren. SQLite bei gestopptem Server einschließlich gegebenenfalls vorhandener WAL-/SHM-Dateien sichern oder die konsistente Backup-Datei verwenden.

Restore nur bei gestopptem Server aus einem zusammenpassenden Sicherungsstand. Danach zuerst offene Journale abgleichen und Geld-/Lager-/Abholfachsummen prüfen. Alte Datenbank zusammen mit neuer Welt oder umgekehrt wiederherzustellen kann Zustellungen beziehungsweise Itementnahmen zurückrollen und Duplizierung ermöglichen. Diese Art administrativer Teil-Rollbacks kann das Plugin nicht zuverlässig erkennen. Geheimnisse und Dateirechte der Sicherung so schützen wie die produktive Datenbank; gespeicherte Token-Hashes sind keine Klartexttokens, aber personenbezogene Kontodaten bleiben sensibel.

Langfristig Ledger, Auktionen, Mailboxhistorie und Stimmen archivieren, ausschließlich nach gemeinsamem Backup und ohne offene Vorgänge oder Bonusreste zu entfernen. Die erste Version enthält absichtlich keine automatische Historienlöschung, die Nachweise oder Ansprüche vernichten könnte. Für große Server Datenwachstum und die synchrone Welt-Speicherung nach Kistenentnahmen messen.
