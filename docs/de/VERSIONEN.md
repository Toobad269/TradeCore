# Versionsprüfung – 27.10.2026

Vor der Implementierung wurden offizielle Dokumentation und Projektquellen geprüft. Die Zielversion wurde nicht auf die inzwischen neuere Paper-Reihe umgestellt.

| Komponente | Projektentscheidung | Offizielle Grundlage |
|---|---|---|
| Paper | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, provided | [1.21.11-Ankündigung](https://papermc.io/news/1-21-11/), [API 1.21.11](https://javadocs.papermc.io/paper/1.21.11/index.html) |
| Java | JDK 21, Bytecode release 21 | [Paper-Java-Tabelle](https://docs.papermc.io/paper/getting-started/): 1.20 bis 1.21.11 → Java 21; erst 26.1+ → Java 25 |
| Maven | 3.9, kein NMS oder paperweight erforderlich | [Paper-Projektsetup](https://docs.papermc.io/paper/dev/project-setup/); Versionen bis 1.21.11 verwenden `-R0.1-SNAPSHOT` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7`, provided | [VaultAPI-Projekt](https://github.com/MilkBowl/VaultAPI), optionaler Economy-Provider über ServicesManager |
| Floodgate | `org.geysermc.floodgate:api:2.2.4-SNAPSHOT`, provided | [Offizielle API-Anleitung](https://geysermc.org/wiki/floodgate/api/), [API-Quellen](https://github.com/GeyserMC/Floodgate/tree/master/api) |
| Cumulus | transitive API-Version 1.1.2 aus Floodgate | [Forms und Cumulus](https://geysermc.org/wiki/geyser/forms/): Form-Builder, validResultHandler, sendForm |
| Geyser | keine eigene API-Abhängigkeit benötigt | [Floodgate-Installation](https://geysermc.org/wiki/floodgate/setup/); Bedrock-Erkennung und Formulare laufen über Floodgate |
| SQLite | sqlite-jdbc 3.49.1.0 | [Xerial](https://github.com/xerial/sqlite-jdbc), JDBC-Treiber eingebettet, WAL und FULL |
| Gson | 2.11.0 | [Google Gson](https://github.com/google/gson), eingebettet und beim späteren Paketieren relocatiert |
| Tests | JUnit Jupiter 5.11.4 | Maven Surefire 3.5.2 |

Paper, Vault und Floodgate ließen sich in den angegebenen Versionen auflösen; Hauptquellen werden gegen diese APIs kompiliert. API-Kompilierbarkeit beweist keine vollständige Protokollkompatibilität aktueller Bedrock-Clients mit einem älteren Minecraft-Server. Ein geeigneter, tatsächlich mit 1.21.11 kompatibler Geyser-/Floodgate-Serverstand ist im Live-Test festzulegen. Es wird kein ungeprüfter Download einer aktuellen Geyser-Version als kompatibel behauptet.

Auf Proxy-Installationen benötigt die backendseitige Floodgate-API laut offizieller Anleitung aktivierte Floodgate-Datenweitergabe und die zusammenpassende Schlüsseldatei. Schlüssel gehören nicht in dieses Projekt. Ohne Floodgate oder bei deaktivierten Formularen bleibt das Java-Inventarmenü nutzbar, auch für über Geyser verbundene Spieler.

Vaults API ist synchron. Der Adapter wartet auf die Antwort des separaten SQLite-Threads. JDBC läuft dadurch niemals auf dem Hauptthread, aber ein Vault-Aufruf auf dem Hauptthread kann ihn während der Datenbankoperation blockieren. Der Worker ruft kein Bukkit auf und wartet nie auf den Hauptthread; ein gegenseitiges Warten wird so vermieden. Hohe Vault-Last gehört in die Live-Lastprüfung. Vault-Bankkonten sind ausdrücklich nicht unterstützt; UUID-Spielerkonten und globale, weltübergreifende Salden schon. Veraltete Namensmethoden lösen nur bekannte, eindeutige Kontonamen auf, ohne Online-UUIDs zu erfinden.

Der Preisgenerator verwendet die [offizielle Paper-ItemType-Liste im 1.21.11-Zweig](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-api/src/main/java/org/bukkit/inventory/ItemType.java). Die registrierten Namen stehen zusätzlich in `registry-1.21.11.txt`. Snapshot-Abhängigkeiten können sich unter unveränderter Versionsbezeichnung ändern; für reproduzierbare Produktionsbuilds den verwendeten Maven-Cache beziehungsweise Repository-Stand archivieren.
