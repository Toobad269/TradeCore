# HTTP-API v1

Vollständige maschinenlesbare Beschreibung: [openapi.json](openapi.json), OpenAPI 3.1. Keine Website, kein HTML-Editor und keine API für Käufe, Geldänderungen oder Item-Ausgaben.

## Aktivierung und Vertrauensgrenze

`modules.httpApi=true` und `modules.chestShops=true` setzen, Server neu starten. Standardbasis: `http://127.0.0.1:8787/api/v1`. Die API akzeptiert ausschließlich die eigenen Shops der aus dem Token ermittelten UUID; eine vom Client eingesandte Besitzer-ID wird nicht akzeptiert. Für **jede** authentifizierte Anfrage wird auf dem Bukkit-Thread geprüft, ob der Spieler online ist und aktuell `use`, `api`, `chest` sowie gegebenenfalls die individuelle Menü-Aktivierung besitzt. Offline-Verwaltung ist in dieser Version bewusst gesperrt, weil ohne Berechtigungs-Provider sonst keine verlässliche aktuelle Rechteprüfung verfügbar wäre. Offline-Auszahlungen und serverseitiger Handel mit Angeboten bleiben davon unberührt.

Der Server stellt nur die Verwaltung bereit. Kisten werden ausschließlich im Spiel registriert. Die API kann für Angebote eine erlaubte Standardmaterial-Vorlage auswählen oder eine exakte Vorlage aus einem bestehenden eigenen Angebot übernehmen. Sie kann keine freie Binärvorlage einschleusen. Die zum Angebot passende physische Ware muss tatsächlich in der verbundenen Kiste liegen. `quantity` bezeichnet die Menge je Kauf, niemals den gesetzten Lagerbestand.

## Verknüpfungsablauf

1. Im Spiel „Website verknüpfen: Einmalcode“ wählen. Ein kryptografisch zufälliger Code mit 80 Bit Entropie wird angezeigt; neue Codes widerrufen den zuvor noch nicht eingelösten Code derselben UUID.
2. Der Spieler gibt diesen Code später in einer vertrauenswürdigen Website ein. Diese sendet `POST /link` mit JSON `{ "code": "…" }`. Gültigkeit standardmäßig 120 Sekunden.
3. Server prüft aktuellen Spielerstatus und Rechte. Innerhalb einer SQLite-Transaktion wird der Code genau einmal verbraucht und durch einen zufälligen 256-Bit-Zugangstoken ersetzt.
4. Antwort 201 enthält `accessToken`, `tokenType: "Bearer"`, `expiresAt` in Unixsekunden. Der Klartexttoken wird nur hier zurückgegeben. Codes und Tokens liegen ausschließlich als SHA-256-Digest in SQLite; die hohe Zufallsentropie verhindert praktikable Wörterbuchsuche. Kein Secret wird geloggt.
5. Weitere Aufrufe senden `Authorization: Bearer <Token>`. Gültigkeit standardmäßig eine Stunde, keine stillschweigende Verlängerung. Neue Verknüpfung ist nach Ablauf erforderlich.
6. `DELETE /tokens/current` widerruft den aktuellen Token; `DELETE /tokens` alle eigenen Tokens und Codes. Im Spiel ist ein Widerruf ebenfalls möglich; ein Administrator kann `/markt revoke <UUID>` verwenden. Deaktivieren des persönlichen Menüs widerruft ebenfalls alle Zugänge.

Ungültige, bereits verbrauchte und abgelaufene Codes liefern dieselbe Fehlermeldung. Codeversuche sind standardmäßig auf fünf pro IP/Minute begrenzt, reguläre Anfragen auf 60 pro IP und UUID/Minute. CORS-Preflights zählen zum IP-Limit. Abgelaufene Datensätze werden regelmäßig entfernt. HTTPS-Proxy und Server hosten unter derselben Vertrauensverwaltung; API-URLs, Header und Bodies dürfen dort nicht mit Zugangsdaten protokolliert werden.

## Anfragen und Antworten

Beispiele verwenden einen ausschließlich im Spiel erhaltenen Code. Die folgenden Befehle sind Dokumentation und wurden nicht gegen ein Benutzerkonto ausgeführt.

```sh
curl --request POST 'https://markt.example.org/api/v1/link' \
  --header 'Content-Type: application/json' \
  --data '{"code":"IM-SPIEL-ANGEZEIGTER-CODE"}'
```

```json
{"accessToken":"ZUFÄLLIGER-TOKEN-NUR-EINMAL-SICHTBAR","tokenType":"Bearer","expiresAt":1793106000}
```

```http
GET /api/v1/profile
Authorization: Bearer TOKEN
```

```json
{"id":"11111111-1111-1111-1111-111111111111","name":"Alice","balance":10000,"income":0}
```

```http
GET /api/v1/shops/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
Authorization: Bearer TOKEN
```

Die Antwort enthält `shop` einschließlich Version, Titel, Kistenposition und serialisiertem Layout sowie `offers`. Der Header `ETag: "3"` entspricht der gemeinsamen Shop-/Layoutversion. In gelesenen Angeboten heißt der gespeicherte Betrag `price`; beim Schreiben heißt das Feld zur Klarheit `priceMinor`. `visible` wird gelesen als 0/1 und geschrieben als Boolean. `item` ist ein opaker, nur lesbarer Datenbeleg.

```http
POST /api/v1/shops/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/offers
Authorization: Bearer TOKEN
Content-Type: application/json
If-Match: "3"

{"material":"DIAMOND","quantity":2,"priceMinor":1800,"visible":true}
```

```json
{"version":4}
```

Der Antwortheader ist `ETag: "4"`. Anschließend `GET /shops/{id}` lesen, um die serverseitig erzeugte Angebots-ID zu erhalten. Alternativ zu `material` kann `templateOfferId` aus einem eigenen bereits vorhandenen Angebot verwendet werden. Damit bleiben beispielsweise Name und Verzauberungen exakt erhalten. Es werden trotzdem keine Items erzeugt.

Weitere Routen:

| Methode und Pfad | Zweck |
|---|---|
| `GET /profile` | Eigene UUID, Name, Guthaben und kumulierte positive Buchungen |
| `GET /shops` | Eigene Shops |
| `GET /shops/{shopId}` | Ein Shop mit Angeboten |
| `PUT /shops/{shopId}` | Titel ändern, Body `{ "title": "…" }` |
| `GET /shops/{shopId}/layout` | Strukturiertes Layout |
| `PUT /shops/{shopId}/layout` | Validiertes vollständiges Layout ersetzen |
| `POST /shops/{shopId}/offers` | Angebot hinzufügen |
| `PUT /shops/{shopId}/offers/{offerId}` | Angebot, Preis, Menge, Sichtbarkeit ändern |
| `DELETE /shops/{shopId}/offers/{offerId}` | Angebot entfernen; Layout wird sicher auf SIMPLE gesetzt |
| `GET /shops/{shopId}/stock` | Beobachtete Bestände pro Vorlage bei geladenem Chunk |
| `GET /shops/{shopId}/income` | Letzte 200 Kistenverkaufseinnahmen dieses Shops |
| `GET /income` | Letzte 200 positive Kontobuchungen |
| `DELETE /tokens/current` | Diesen Token widerrufen |
| `DELETE /tokens` | Alle eigenen Zugänge widerrufen |

Alle Shop-Schreiboperationen benötigen `If-Match`. Fehlt es, kommt 428. Gleichzeitige Änderungen im Spiel und in der API führen bei veralteter Version zu 409; kein automatisches Überschreiben. Wiederholte Schreibrequests mit derselben Version werden nicht doppelt angewandt. Nach einem verlorenen Response zuerst den aktuellen Zustand lesen; nicht unbesehen mit einer neuen Version wiederholen. Eigentümer und Version werden in derselben Transaktion wie die Mutation geprüft. Offene oder unklare physische Shop-Käufe sperren Änderungen.

Bestandsantwort für einen ungeladenen Chunk:

```json
{"loaded":false,"observedAt":1793102400,"offers":[]}
```

Das ist keine Bestandsangabe null und keine Kaufzusage. Die API lädt keine Chunks nach. Bei geladenen Chunks gibt es `offerId` und `quantity` für jedes Angebot. Verschiedene Angebote mit gleicher Vorlage können denselben physischen Bestand anzeigen; er wird nicht mehrfach reserviert.

## Layout-Modell

```json
{
  "mode":"CUSTOM",
  "pages":[
    {
      "id":"start",
      "title":"Alices Waren",
      "fields":[
        {"slot":10,"icon":"DIAMOND","label":"2 Diamanten kaufen","action":"BUY_OFFER","offerId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"},
        {"slot":12,"icon":"BOOK","label":"Information","action":"DECORATION"},
        {"slot":40,"icon":"ARROW","label":"Mehr Waren","action":"OPEN_PAGE","targetPage":"mehr"}
      ]
    },
    {
      "id":"mehr",
      "title":"Weitere Waren",
      "fields":[
        {"slot":10,"icon":"ARROW","label":"Zurück","action":"OPEN_PAGE","targetPage":"start"},
        {"slot":40,"icon":"BARRIER","label":"Schließen","action":"CLOSE"}
      ]
    }
  ]
}
```

Maximal 20 Seiten, 45 Felder je Seite. Die Plätze 0–44 sind je Seite eindeutig. Die untere Java-Menüzeile bleibt für systemeigene Navigation reserviert. Bedrock zeigt dieselben Felder in der Modellreihenfolge als Formularbuttons. Materialsymbole müssen echte Item-Materialien sein. Beschriftungen sind 1–80 Zeichen lang, ohne Steuerzeichen. Seiten-IDs: 1–40 alphanumerische Zeichen, Bindestrich oder Unterstrich. Referenzen müssen im selben Shop existieren. Zulässige Aktionen sind ausschließlich `BUY_OFFER`, `OPEN_PAGE`, `HOME`, `CLOSE`, `DECORATION`. Es gibt keine Befehle, Skripte, URLs oder Programme als Aktionen. Ein unsichtbares Angebot bleibt unverkäuflich, auch wenn ein altes Feld darauf verweist. Jede Kaufbestätigung zeigt die serverseitig ermittelte Ware, Menge und den Gesamtpreis, unabhängig von einer frei gewählten Button-Beschriftung.

Seiten, einzelne Felder, Symbole und Knöpfe werden durch Bearbeiten dieses Modells und anschließendes versioniertes PUT verwaltet. Ein einfacher Shop lautet `{ "mode": "SIMPLE", "pages": [] }`. Layouts können vollständig im Spiel über die JSON-Eingabe bearbeitet werden; dies ist absichtlich kein eigener grafischer Editor.

## Fehler und Grenzen

Antworten folgen `{ "error": "CODE", "message": "Beschreibung" }`. Relevante Status: 400 Syntax, 401 Authentifizierung, 403 Origin/Spielerstatus/Rechte, 404 unbekannte Route, 409 Version, 413 Body zu groß, 415 Inhaltstyp, 422 Werte/Eigentümerschaft/fehlender Eintrag, 428 Version fehlt, 429 Limit, 500 interner Fehler, 503 Hauptthread beschäftigt. Fehlende oder fremde Daten werden nicht durch eine separate öffentliche Besitzabfrage offengelegt. Die OpenAPI-Datei dokumentiert alle Routen, Modelle und Fehler.

Bodies sind auf 32768 Bytes begrenzt; nur JSON, keine Query-Parameter, keine Zugangsdaten in URLs, keine frei gesetzten Bestände. Es gibt vier HTTP-Worker mit begrenzter Warteschlange. Eingehende Verbindungen und langsame Bodies müssen zusätzlich am Reverse-Proxy begrenzt werden.

## HTTPS und Browser

Beispiel für einen **zu installierenden Reverse-Proxy**, nicht für eine mitgelieferte Website:

```nginx
server {
    listen 443 ssl;
    server_name markt.example.org;
    # Zertifikat/Schlüssel über die eigene Serververwaltung einrichten.
    ssl_certificate /etc/letsencrypt/live/markt.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/markt.example.org/privkey.pem;
    client_max_body_size 32k;
    client_body_timeout 10s;
    location /api/v1/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_read_timeout 15s;
        proxy_send_timeout 10s;
        proxy_set_header Host $host;
        access_log off;
    }
}
```

Den API-Port nicht öffentlich öffnen. Firewall, TLS-Erneuerung, Verbindungs- und zusätzliche IP-Limits sind Betriebspflichten. Forwarded-IP-Header werden vom Plugin absichtlich nicht zur Limit-Umgehung vertraut; hinter einem Proxy ist das gemeinsame IP-Limit entsprechend zu dimensionieren. Die Fehlerantworten enthalten keine Datenbankdetails und das Plugin loggt keine Codes, Tokens, Authorization-Header oder HTTP-Bodies.

`allowedOrigins` muss beispielsweise ausdrücklich `["https://verwaltung.example.org"]` enthalten. Keine Wildcard, keine implizite Freigabe. Ohne Origin-Header bleiben authentifizierte Server-zu-Server-Aufrufe erlaubt. Browser erhalten CORS nur für konfigurierte Origins; keine Cookie-Credentials. Die spätere Website sollte Tokens möglichst nur im Arbeitsspeicher halten oder einen abgesicherten eigenen Backenddienst mit HttpOnly/Secure/SameSite-Sitzung verwenden. Sie darf Tokens nicht in URLs, Analytics, unsicheren Local-Storage oder fremde Skripte geben. Bei Cookie-basierten Backend-Sitzungen dort CSRF-Schutz ergänzen. CORS ersetzt keine Tokenprüfung und schützt nicht gegen XSS im erlaubten Frontend.
