# Zentrale Konfiguration und Preise

`config.json` ist die einzige zentrale Modul- und Betriebskonfiguration. JSON erlaubt keine Kommentare; diese Anleitung ersetzt sie. `prices.json` ist der vollständig aufgeschlüsselte Preiskatalog und `plugin.yml` der technisch notwendige Plugin-Deskriptor. Es gibt keine konkurrierende `config.yml`.

**Alle Konfigurationsänderungen benötigen einen kontrollierten Server-Neustart.** Es gibt bewusst kein Hot-Reload: offene Angebote, Preisbestätigungen, native HTTP-Listener und ausstehende Inventarvorgänge sollen nicht während eines Vorgangs ihre Regeln wechseln. Vor einem Neustart offene Vorgänge prüfen. Keine Datenbanktabellen löschen, um ein Modul abzuschalten.

## Module

Alle Schalter stehen in `modules`:

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `serverShop` | true | Zentrales Lager mit An- und Verkauf |
| `chestShops` | true | Persönliche physische Kisten-Shops |
| `auctionHouse` | true | Globales Sofortkauf-Auktionshaus |
| `httpApi` | false | Verwaltung eigener Kisten-Shops und Layouts |
| `bedrockForms` | true | Formulare nutzen, falls Floodgate vorhanden ist |
| `menuItem` | true | Individuell aktivierbares, geschütztes PDC-Menü-Item |
| `supplierBonus` | true | Zusätzliche Lieferantenboni bei späteren Serververkäufen |
| `rarityPrices` | false | Automatische Katalogpreise als Grundlage verwenden |
| `playerVotes` | false | Experimentelle dauerhafte Artikel-Preisabstimmungen |
| `stockPrices` | false | Experimentelle Lagerfaktoren im Server-Shop |
| `vault` | true | Economy-Service registrieren, wenn Vault installiert ist |

HTTP benötigt Kisten-Shops. Bonus und die drei Preisoptionen benötigen den Server-Shop. Widersprüchliche Kombinationen werden mit verständlichem Startfehler abgelehnt. Floodgate ist keine harte Abhängigkeit: ohne es verwendet das Plugin Inventarmenüs. Vault ist ebenfalls optional.

Beim Abschalten bleiben Konten, Lager, Anteile, Bonusreste, physische Kisten, verwahrte Auktionen, Abholfächer und Journale bestehen. Der Kistenschutz bleibt auch bei deaktiviertem Handelsmodul für registrierte Kisten aktiv. Die Bereinigung abgelaufener Auktionen und das Abholfach funktionieren weiter, auch wenn das Auktionsmodul deaktiviert wurde. Der Bonusfaktor neuer Verkäufe ist bei deaktiviertem Bonus null; bereits vorhandene Bruchteilsansprüche bleiben unverändert in der Rücklage. Eine Wiederaktivierung verteilt keine rückwirkenden Boni. Das Deaktivieren des Menü-Item-Moduls entfernt die Menü-Items; `/markt` bleibt mit Berechtigungen als Einstieg erreichbar.

## Geld und Ausgabequellen

* `currency.singular`, `plural`: Währungsnamen. Anzeige hat zwei Dezimalstellen; alle Tabellen und API-Beträge verwenden ganze Untereinheiten.
* `startingMinor`: Erstausstattung eines neu angelegten UUID-Kontos, Standard 10000 = 100 Taler. Sie wird als `START` protokolliert und ist eine konfigurierte Geldschöpfung bei der Kontoanlage.
* `serverInitialMinor`: einmalige Startausstattung des Serverkontos, Standard 100000000. Änderungen wirken auf bereits bestehende Serverkonten nicht rückwirkend. Danach `/markt money server …` verwenden.
* `allowMinting`: standardmäßig false. Serverankäufe reservieren Geld des Serverkontos. Reicht es nicht, wird der Verkauf vor jeder Item-Entnahme abgelehnt. Nur bei ausdrücklich true erhalten diese Verkäufe neues Geld mit dem Buchungsgrund `MINT_SERVER_SELL`.
* Lieferantenboni stammen immer aus realen späteren Kaufzahlungen. Der volle ganzzahlige Bonusbetrag wird in eine getrennte Bonus-Rücklage umgebucht und steht dem Serverkonto nicht für Ankäufe zur Verfügung. Nur daraus werden ganze Untereinheiten ausgezahlt.
* Auktionseinstell- und Verkaufsgebühren gehen auf das Serverkonto. Kistenverkäufe und Spielerüberweisungen erzeugen kein Geld.
* Administrative `give`-/`set`-Befehle und Einzahlungen anderer Plugins über Vault sind privilegierte Geldquellen und werden getrennt protokolliert. Fremde Plugins sind Teil der Vertrauensgrenze.
* Maximaler Kontostand/Betrag: 9.000.000.000.000.000 Untereinheiten. Java prüft Additionen und Multiplikationen auf Überlauf; SQLite hat zusätzliche CHECKs. Ein vollständiger Vorgang wird bei Überschreitung zurückgerollt. Positive Lebenszeit-Buchungssummen verwenden geprüfte 64-Bit-Addition.

Spielereingaben mit mehr als zwei Nachkommastellen werden abgelehnt. Die Double-Schnittstelle von Vault rundet ausdrücklich mit HALF_UP auf zwei Nachkommastellen. Preise, Preisfaktoren und Gebühren werden zur sicheren ganzen Untereinheit abgerundet. Lieferantenboni werden dagegen einschließlich Prozentbruchteilen exakt rational berechnet: Weder ein halber Cent pro Einheit noch dessen anteilige Zuordnung verfällt. Ein artikelweiser Finanzierungsrest sammelt die Bruchteile, bis eine weitere ganze Untereinheit vom Servererlös in die Bonusrücklage umgebucht werden kann. Auszahlungen erfolgen nur in ganzen Untereinheiten; persönliche Restansprüche bleiben exakt gespeichert.

## Manuelle Preise und Seltenheit

`serverShop.manual` ist eine Map von Materialnamen zu `buyMinor`, `sellMinor`, `bonusBps`. 100 Basispunkte = 1 %, also 500 = 5 %. `buyMinor` ist der maximale Basisankauf für eine Einheit, `sellMinor` der Basisverkauf. Ein Eintrag `{ "excluded": true }` schließt einen Artikel ausdrücklich aus. Ohne Seltenheitspreise werden ausschließlich diese manuellen Artikel angeboten. Einträge müssen ein unverändertes, handelbares Standarditem bezeichnen.

Bei `rarityPrices=true` kommt der vollständige Katalog als Grundlage hinzu. Manuelle Artikel überschreiben ihn; individuelle `prices.json`-Änderungen überschreiben die generierten Katalogvorgaben. `serverShop.bonusBps` ist der Bonus für Katalogartikel; manuelle Einträge haben jeweils ihren eigenen Satz. Die maximale Bonusrate ist 50 %.

Der Generator `tools-generate-prices.py` liest die offizielle `ItemType.java` des Paper-1.21.11-Zweigs. Er versieht **jeden registrierten Typ** mit einem Eintrag, auch AIR, technische und ausgeschlossene Typen. Im gelieferten Stand sind es 1.505. Neue Servertypen ohne Datensatz verursachen einen Startfehler, keine stille Preis-Null. Ein erneutes Generieren überschreibt Anpassungen; vorher sichern. Offline kann `--source <ItemType.java>` verwendet werden.

Jeder Datensatz enthält angewandte Regel, Beschaffungsaufwand, Knappheit, Erneuerbarkeit, Farmbarkeit, modellierte Herstellungseinheiten, Score, Preise, Handelsfreigabe und Begründung. Die geordnete Regelzuordnung steht vollständig im Generator. Herstellungsannahmen umfassen beispielsweise acht Einheiten für einen Brustpanzer, drei für eine Spitzhacke, neun für einen Ressourcenblock und separate Faktoren für Platten und Treppen. Es handelt sich um geschätzte wirtschaftliche Herstellungskosten, nicht um einen vollständigen Vanilla-/Datapack-Rezeptoptimierer. Automatisierte Farmdesigns und Server-Rezepte können diese Annahmen verändern; der Administrator soll sie an die eigene Ökonomie anpassen.

```
score = max(1, 3*Aufwand + 2*Knappheit + (erneuerbar ? 0 : 10) - Farmbarkeit)
Basisverkauf = max(10, 4*score²*Herstellungseinheiten)
Platten: danach halbieren, mindestens 10; Treppen: danach ×1,5 abrunden
Basisankauf = floor(Basisverkauf / 10)
```

Die Minecraft-Seltenheitsfarbe fließt nicht als Ersatz für diese Kriterien ein. Die Farbe ist kein verlässlicher wirtschaftlicher Knappheitsindikator.

## Erlaubte Items und Identität

* **Server-Shop:** vollständiger Materialtyp, ausschließlich unveränderte Vanilla-Standardware. Benennung, Verzauberung, Schaden, Inhalt und andere individuelle Daten führen zur Ablehnung beim Verkauf. Käufe erzeugen genau diese Standardware aus dem gebuchten Lager.
* **Kisten-Shop:** Item-Vorlage einschließlich Metadaten; `ItemStack.isSimilar` prüft Gleichheit ohne Menge. Mengen kommen ausschließlich aus der echten Kiste. API-Vorlagen erzeugen keine Ware.
* **Auktion:** komplettes Item beziehungsweise Stapel, originale unterstützte Metadaten bleiben über Papers `serializeAsBytes` erhalten. Suchfilter verwenden Material und vorhandene Meta-Beschreibung.
* **Alle Bereiche:** keine Menü-Items, keine Items mit Plugin-PDC-Daten, keine technischen Operator-Items oder Spawn-Eier; keine BlockStateMeta-Items und keine Bündel. Behälter einschließlich Schildern mit Blockzustandsdaten sind konservativ ausgeschlossen, damit keine versteckten geschützten Items oder fremden Inventare eingeschleust werden. Die vollständige Ausschlussregel steht in `Items.allowed`; der Katalog übernimmt die BlockStateMeta-Liste aus der offiziellen API.
* Itemdaten speichern eine kanonische Menge-1-Binärvorlage und eine separat geprüfte Menge. Dadurch werden auch große Käufe nicht durch eine binäre Stapelgrößenbegrenzung abgeschnitten. Bei der Ausgabe werden sie auf reguläre Stapel verteilt.

## Preisreihenfolge und Lagerformel

Die Berechnung betrifft nur den Server-Shop. Kisten und Auktionen behalten ihre Besitzerpreise.

1. Manuelle Grundlage oder zugeschaltete Seltenheitsgrundlage, danach manuelle Überschreibung.
2. Begrenzter Abstimmungsfaktor; aus, wenn `playerVotes=false`.
3. Begrenzter Lagerfaktor; aus, wenn `stockPrices=false`.
4. Globale Preisgrenzen und ganzzahlige Abrundung, anschließend sichere Ankaufskappung.

`pricing.referenceStock` ist der Referenzbestand R; `strengthBps` die Änderungsstärke A. Pro Einheit an Bestandskante E:

```
E' = min(max(E, 0), 100*R)
FaktorBps(E) = clamp(10000 + trunc(A*(R-E')/max(R,E')), minFactorBps, maxFactorBps)
Einheitsverkauf = clamp(floor(floor(Basisverkauf*Abstimmungsfaktor/10000)*Lagerfaktor/10000), minMinor, maxMinor)
```

Für einen Kauf aus Bestand S werden die Kanten S, S−1, …, S−q+1 addiert. Für eine Einlieferung in S die Kanten S+1, …, S+q. Das verhindert systematische Vorteile durch Aufteilen bei gleicher Marktlage. Alle Summen werden geprüft. Leeres Lager bleibt ausverkauft. Die Quote enthält Bestand, Faktor, Menge, Ankauf, Verkauf und Bonus; bei Bestätigung wird die komplette Quote erneut berechnet und muss übereinstimmen.

`maxQuantity` ist auf 4096 begrenzt. Standard: R=1024, A=5000, Lagerfaktor zwischen 0,5 und 2,0, Einheitsverkauf zwischen 1 und 100000000 Untereinheiten.

## Schutz gegen Kaufen und Wiederverkaufen

Für jeden Artikel ermittelt das Plugin den niedrigstmöglichen Verkaufspreis über alle aktivierten Preisfaktoren und den höchstmöglichen Bonus pro später verkaufter Einheit, zur Sicherheitsgrenze auf eine ganze Untereinheit aufgerundet. Es verlangt:

```
Basisankauf <= Minimalverkauf - Maximalbonus - 1 Untereinheit
Tatsächlicher Ankauf je Einheit = max(0, min(obige Grenze, floor(aktueller Einheitsverkauf*Basisankauf/Basisverkauf)))
```

Ungültige Kombinationen werden beim Start abgelehnt. Diese konservative Grenze berücksichtigt spätere maximale Bonusauszahlungen und gilt über alle Abstimmungs-/Lagerfaktoren einer unveränderten Konfiguration. Bonusanspruch pro zugerechneter Einheit kann insgesamt höchstens den konfigurierten Maximalbonus erhalten; Auszahlung an andere Lieferanten schafft keinen weiteren Anspruch für den Wiedereinlieferer. Ein garantierter Kreislaufgewinn wird dadurch auch bei nachfolgenden Preisfaktoren verhindert. Administratorische Änderungen an Basispreisen oder Preisgrenzen über Neustarts hinweg sind ein neuer Markt und können historische Spekulationsgewinne ermöglichen; das lässt sich ohne Enteignung alter Ware nicht pauschal ausschließen.

Die Summe der Lieferantenbestände entspricht immer dem Lager. Bei Kauf q aus S werden alle Bestände mit `(S-q)/S` multipliziert; neue Einlieferungen werden erst danach addiert. Bonus wird mit dem aktuellen Verhältnis verteilt und Restbruchteile bleiben dauerhaft erhalten. Es gibt keine Zuordnung nach einer abweichenden FIFO-Reihenfolge. Exakte Brüche können sich bei häufig wechselnden Lieferungen vergrößern; für sehr große, hochfrequente Server ist eine gesonderte Lastprüfung erforderlich.

## Spielerabstimmungen

`votes.windowSeconds`, `cooldownSeconds`: weltweite UTC-Runden mit einer festen Abstimmungsphase und Abklingzeit. `minParticipants`: Mindestzahl. `stepBps`: maximale Änderung des vorhandenen Faktors je abgeschlossener Runde. Die Mehrheit „teurer“ erhöht, „billiger“ senkt um einen Schritt; Gleichstand oder zu wenige Stimmen verändern nichts. `minFactorBps`, `maxFactorBps`: harte Grenzen. `excluded`: Liste nicht abstimmbarer Materialien.

Ergebnisse werden beim nächsten Artikelzugriff oder Preisangebot ausgewertet; Runden ohne Stimmen erzeugen keine nachträglichen Änderungen. Stimmen haben den Primärschlüssel Artikel/UUID/Runde, sind dauerhaft gespeichert und können nicht innerhalb der Runde ersetzt werden. Ein Reset ist eine ausdrücklich administrative Ausnahme. UUID-Eindeutigkeit schützt nicht vollständig gegen Mehrfachkonten. Diese Funktion ist experimentell und standardmäßig aus.

## Menü, Auktionen, API und Nachrichten

* `menu.material`, `name`, `lore`, `preferredSlot` (0–35) bestimmen das PDC-Item. Es wird stets auf ein Exemplar mit Menge 1 normalisiert. Ist der bevorzugte Platz belegt, wird ein freier Platz genutzt. Es wird nicht gedroppt. Unzulässige Materialnamen verhindern den Start.
* `auctions.durationSeconds`: vorgeschlagene Laufzeit; der Spieler darf 60 Sekunden bis `maxDurationSeconds` (höchstens 30 Tage) wählen. `listingFeeMinor`, `saleFeeBps` und `limit` steuern Gebühren und aktive Angebote.
* `api.host`: Standard 127.0.0.1; `port`: 8787. `allowedOrigins` enthält ausdrücklich zugelassene HTTPS-Origins ohne Pfad oder Wildcards; für lokale Entwicklung ist `http://localhost[:port]` zulässig. `codeSeconds`: 30–600; `tokenSeconds`: 60–86400; beide sind begrenzt. `requestsPerMinute`, `codeAttemptsPerMinute` begrenzen Anfragen pro IP/Spieler und Codeversuche pro IP. Hinter einem Proxy teilen dessen Clients standardmäßig das IP-Limit; Forwarded-Header werden nicht blind vertraut.
* `messages` enthält die anpassbaren allgemeinen deutschen Erfolg-, Fehler-, Sperr-, Vollinventar-, Wiederherstellungs- und Verfügbarkeitsnachrichten. Konkrete Menübezeichnungen und Validierungsdiagnosen sind deutsch im Quellcode enthalten.

## Zusätzliche Item-Ausschlüsse

`items.excludedMaterials` sperrt Materialtypen für neue Handelsvorgänge in allen Bereichen. `serverExcluded`, `chestExcluded`, `auctionExcluded` erlauben zusätzlich getrennte Ausschlüsse. Werte sind exakte Materialnamen wie `DIAMOND`. Unbekannte Itemnamen werden beim Start abgelehnt. Vorhandene verwahrte Items werden durch Ausschlüsse nicht gelöscht: Rückzug und Abholung bleiben möglich. Ein vorhandenes ausgeschlossenes Angebot kann nicht mehr gekauft werden. Die harten technischen Ausschlüsse und die Metadatenregeln bleiben darüber hinaus wirksam; sie können nicht versehentlich durch eine Preisüberschreibung aufgehoben werden.

Bei Menü-Materialien, die sonst passiv verbraucht werden könnten, blockiert der Schutz zusätzlich Munitionsverbrauch, Armbrustladen, Totemverbrauch sowie Verwendung an Entities und Rüstungsständern. Ein gewöhnlicher Kompass vermeidet diese Einschränkungen anderer Spielmechaniken.
