# Prüfstand und Live-Abnahme

Die abschließenden automatischen Testergebnisse stehen in [TESTERGEBNIS.txt](TESTERGEBNIS.txt). Hauptquellen und Tests wurden mit JDK 21 und `mvn test` gegen Paper API 1.21.11, VaultAPI 1.7 und Floodgate API 2.2.4-SNAPSHOT kompiliert. Der Maven-Lebenszyklus wurde nicht bis `package` ausgeführt. Das Quellarchiv enthält keine Plugin-JAR, Server-JAR, Bibliotheks-JAR oder kompilierten Klassen.

## Automatische Prüfung

JUnit verwendet echte temporäre SQLite-Datenbanken, keine nachgebaute SQL-Logik. Abgedeckt sind:

* Dezimalstellen, HALF_UP für Vault, negative Werte, NaN und Zahlenüberläufe.
* Exakte rationale Anteile und Bestandserhaltung bei wiederholten anteiligen Entnahmen.
* Atomare, idempotente Überweisungen und Rollback bei unzureichendem Guthaben.
* Lieferantenbeispiel 60 von 200 Einheiten, anteilige Reduktion und Gutschrift an ausschließlich gespeicherte, nicht online befindliche Konten.
* Bonusrestakkumulation, einmalige Auszahlung, getrennte Rücklage und Abschalten des Bonus ohne rückwirkende Ansprüche oder Verlust alter Reste.
* Dauerhafte Reservierung vor physischer Entnahme, erneutes Öffnen der Datenbank nach simuliertem Prozessende, Abschluss beziehungsweise Storno mit genau einmaliger Buchung.
* Gleichzeitige Kaufversuche zweier Client-Threads über den SQLite-Dispatcher für dasselbe Auktionsangebot; genau ein Erfolg und eine Lieferung.
* Konkurrierende Lagerkäufe mit identischer Quote und nur einer verfügbaren Einheit.
* Ablauf und Rückzug von Auktionen mit genau einem dauerhaften Abholfacheintrag.
* HELD-Abholung ohne automatische erneute Ausgabe und ohne mehrfachen Abschluss.
* Kistenkauf mit Käufergeldreservierung, gesperrten parallelen Käufen, gesperrter Shop-Entfernung und gezieltem Wiederherstellungsabschluss.
* Eigentümerprüfung, gemeinsame Layoutversion, Zurückweisen nicht erlaubter Aktionen und veralteter Schreibversionen.
* Modulabschaltung ohne Löschen von Guthaben oder verwahrter Ware.
* Alle acht Kombinationen aus Seltenheit, Spielerabstimmungen und Lagerpreisen, mehrere Faktoren, große Mengen, Preisgrenzen und identische Gesamtsummen bei aufgeteilten Transaktionen.
* Ablehnen eines scheinbar sicheren Ankaufsspreads, der durch einen späteren maximalen Bonus doch profitabel werden könnte.
* Dauerhafte eindeutige Stimmen, nachträgliche Auswertung mehrerer während einer Serverpause abgeschlossener Runden.
* Verweigern eines Serverankaufs vor physischer Vorbereitung bei leerem Serverkonto.
* Vollständiger Katalogvergleich mit allen 1.505 offiziellen Registry-Namen und ausdrückliche technische Ausschlüsse.
* Konsistente SQLite-Sicherung und erneutes Öffnen der Sicherungsdatei.
* Kryptografisch zufällige Einmalcodes, nur gehashte Speicherung, Ersatz alter Codes, einmaliger Austausch, Ablauf, Tokenauflösung und Widerruf.
* Globale und bereichsspezifische Materialausschlüsse ohne Geld- oder Itemverlust.

Zusätzlich sind JSON-Dateien, lokale Dokumentationsverweise und die Struktur der OpenAPI-Spezifikation geprüft. Diese Prüfungen ersetzen keinen HTTP-Integrationstest auf einem laufenden Server.

## Noch nicht mit echtem Server oder Clients ausgeführt

Es wurde kein Paper-Server gestartet, kein Minecraft-Client verbunden und keine Server-/Plugin-JAR dafür erzeugt. Die folgenden Punkte sind deshalb eine **noch ausstehende Live-Abnahme**, keine als bestanden behaupteten Tests. Vor Produktion mit einer Kopie der Welt und zwei Java- sowie zwei Bedrock-Testkonten ausführen.

| Test | Zu prüfendes Verhalten |
|---|---|
| Start ohne Vault/Floodgate | Plugin lädt; Java-Menüs, Konten und Handel funktionieren ohne Klassenladefehler |
| Start mit beiden Erweiterungen | UUID-Erkennung, Floodgate-Formantworten und Registrierung des Vault-Providers funktionieren mit den tatsächlich installierten Builds |
| Java/Bedrock-Parität | Überweisung, Suche, Mengen-/Betragswahl, Serververkauf, Shop-Erstellung/Änderung, Layout-Navigation, Auktionsfilter, Rückzug, Abholfach, Einnahmen, Bonusanzeige und Abstimmung jeweils ohne Befehle bedienen |
| Volles Inventar | Menü-Item überschreibt nichts und erscheint nach Freimachen eines Slots; Kauf bleibt im Abholfach; erfolglose Abholung lässt Inhalt und Konten unverändert |
| Gemischte Stapel | Teilvolle passende Stapel, andere Metadaten, Einzelitems und Mengen bis 4096 korrekt verteilen; keine versteckten Slots überschreiben |
| Item-Metadaten | Benannte/verzauberte/abgenutzte Ware mit Lore im Kisten-Shop und Auktionshaus erhalten; im Standard-Server-Shop ablehnen; PDC- und Behälterware überall ablehnen |
| Doppelklicks | Shift-, Nummern-, Offhand-, Doppelklick-, Drag- und Creative-Aktionen gegen Menüs; keine zweite Transaktion, keine herausnehmbaren GUI-Symbole |
| Menüschutz | Drop, Tod, KeepInventory an/aus, Respawn, Relog, volle Inventare, zweite gefälschte Kopie, Crafting, Amboss, Schmiedetisch, Entity-Interaktion, Munition, Armbrust und Totem-Material testen |
| Status-/Rechteänderung | Nach Öffnen, vor Bestätigung Rechte entziehen oder Item deaktivieren; neue fachliche Aktion muss scheitern; kein Secret darf gültig bleiben |
| Kistenschutz | Besitzer/fremder Spieler, vorher offene Inventaransicht, Hopper in beide Richtungen, Hopper-Minecart, Explosion, Feuer, Piston, Abbau und angrenzende zweite Kiste testen |
| Entladener Chunk | Kauf ablehnen, API `loaded:false`; keinen Chunk laden, kein Geld abbuchen; laufender Kauf hält Ticket bis Abschluss |
| Offline-Verkäufer | Auktion und Kisten-Shop kaufen, während Besitzer offline ist; UUID-Konto einmalig korrekt vergüten |
| Kontrollierter Neustart | Vorbereiteten Vorgang und normale abgeschlossene Käufe sichern, neu starten; keine automatische Wiederholung, nur offene Vorgänge sperren |
| Harte Prozessabbrüche | Jeweils nach PREPARED, nach Inventaränderung, nach Spieler-/Weltspeicherung und vor/nach DB-Abschluss abbrechen; anhand Belegen sicher auflösen und alle Geld-/Itemsummen vergleichen |
| API-Integration | Realer Codeaustausch, doppelte Anfrage, abgelaufener Code/Token, Fremdshop, Offline-Spieler, fehlendes/veraltetes If-Match, alle Offer-/Layoutfelder und gleichzeitige Änderung im Spiel |
| Proxy/Browser | HTTPS, korrekte CORS-Preflights, abgelehnte fremde Origin, keine Cookies, Rate-Limits, große/ungültige Bodies, Header-/Code-/Token-Logfreiheit |
| Kombinierte Preisfunktionen | Leeres, sehr volles und schnell wechselndes Lager; Quote vor/nach Abstimmungsende; Kaufen→Wiederverkaufen→späterer Bonus mit realen Inventaren |
| Last und Sicherung | Viele Lieferanten mit wachsenden Brüchen, Vault-Burst, DB-/Welt-Speicherlatenz, HTTP-Warteschlange und gemeinsamer Restore aus identischem Sicherungsstand |

## Bewusste Grenzen

Die Erstversion unterstützt Einzelkisten, keine Doppelkisten; keine Region-Integration; keine offline ausgeführte HTTP-Verwaltung; keinen grafischen Layout-Editor; keine Gebotsauktionen und keine dynamische Neukonfiguration während des Betriebs. Individuelle Layouts werden als validiertes JSON bearbeitet. Fremde Plugins mit direktem Inventarzugriff, administrative Teil-Rollbacks und umgangene Bukkit-Ereignisse liegen außerhalb der technischen Schutzgrenze. Bei unklaren physischen Vorgängen ist ein belegter administrativer Abgleich erforderlich. Seltenheits- und Herstellungskosten sind nachvollziehbare Modellannahmen und keine vollständige Vermessung aller Farm- oder Datapack-Rezeptökonomien.
