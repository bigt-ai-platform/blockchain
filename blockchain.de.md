# Bigtangle: Eine skalierbare Multi-Layer-Blockchain

## Wie Bigtangle funktioniert

Bigtangle kombiniert zwei Technologien:

- Ein **DAG (Directed Acyclic Graph)**, der viele Transaktionen gleichzeitig
  verarbeiten kann für hohe Geschwindigkeit.
- Eine **Proof-of-Stake (PoS) Beacon Chain**, die diese Transaktionen periodisch
  bestätigt und dauerhaft macht.

Stellen Sie sich den DAG als eine Autobahn vor, die den Verkehr schnell
bewegt, während die Beacon Chain als Verkehrsbehörde fungiert, die periodisch
bestätigt, dass alles korrekt ist.

Alle 12 Sekunden wird ein Validator ausgewählt, um einen **Beacon-Block** zu
erstellen. Dieser Beacon bestätigt alle aktuellen Transaktionsaktivitäten und
macht sie unumkehrbar.

---

## Warum Bigtangle

| Problem | Wie Bigtangle es löst |
|---------|------------------------|
| **Langsame Transaktionen** — Ethereum Layer 1 schafft ~30 Tx/s, Bitcoin ~7 Tx/s | DAG-Parallelität: Tausende von Transaktionen pro Sekunde parallel |
| **Single-Leader-Risiko** — wenn ein designierter Leader ausfällt, bleibt die Chain stecken | Die Transaktionsverarbeitung hat keinen Single-Leader-Engpass, da DAG-Blöcke von vielen Knoten parallel erstellt werden können. Beacon-Blöcke werden von einem Validator pro Slot vorgeschlagen, um Finalität zu gewährleisten. |
| **Langsame Finalität** — Ethereum braucht ~6 Minuten für irreversible Abwicklung | Casper FFG-Finalität in ~12,8 Minuten (2 Epochen); Beacon-Blöcke werden in ~24 Sekunden bestätigt |
| **Keine horizontale Skalierung** — Die meisten Chains laufen auf einem einzigen Ledger | Viele L1-Anwendungschains, jede mit eigenen Validatoren und eigenem Konsens |
| **Quanten-Verwundbarkeit** — ECDSA-Signaturen können von Quantencomputern geknackt werden | Duale Post-Quanten-Signaturen mit zwei NIST-zugelassenen Algorithmen |

---

## Architektur

Bigtangle betreibt zwei unabhängige Layer:

```
              Beacon Chain (Sicherheit)

Beacon 1 -------- Beacon 2 -------- Beacon 3
     |                |                 |
     | bestätigt      | bestätigt       | bestätigt
     v                v                 v

        Transaction DAG

      A
     / \
    B   C
     \ /
      D
     / \
    E   F
```

- Der **DAG** bewältigt den Transaktionsdurchsatz. Da viele Blöcke gleichzeitig
  erstellt werden können, benötigt das Netzwerk eine Möglichkeit zu entscheiden,
  mit welchen bestehenden Blöcken ein neuer Block verbunden werden soll. Bigtangle
  löst dies mit der stake-gewichteten GHOST-Regel: Neue Blöcke hängen sich an
  den Kind-Block mit dem größten akkumulierten Stake-Gewicht, sodass der
  schwerste Zweig gewinnt, während alternative Pfade weiter wachsen können.

- Die **Beacon Chain** sorgt für Finalität. Alle 12 Sekunden erzeugt ein Validator,
  der durch einen unvoreingenommenen Zufallsprozess ausgewählt wird, einen
  Beacon-Block, der die aktuellen DAG-Aktivitäten bestätigt. Bigtangle verwendet
  denselben Finalitätsmechanismus wie Ethereum (Casper FFG). Sobald zwei Drittel
  der Validatoren zustimmen, werden die bestätigten Blöcke dauerhaft.

Die beiden Layer ergänzen sich: Der DAG bietet Geschwindigkeit und Parallelität;
die Beacon Chain bietet Sicherheit und Finalität.

---

## DAG und Parallelverarbeitung

### Wie der DAG funktioniert

In einer traditionellen Blockchain werden Blöcke nacheinander in einer einzigen
Linie erstellt. Dies erzeugt einen Engpass — jeder muss auf den nächsten Block
warten.

Bigtangle verwendet einen DAG, bei dem jeder Block mit zwei vorherigen Blöcken
statt einem verbunden wird. Dies ermöglicht die gleichzeitige Erstellung mehrerer
Blöcke:

```
    ┌───┐     ┌───┐     ┌───┐
    │ G │────►│ A │────►│ B │────► ...
    └───┘     └───┘     └───┘
       \         \
        \         └───┐     ┌───┐
         └───┐    │ C │────►│ D │
              │    └───┘     └───┘
              │
              └───┐    ┌───┐
                  │ E │────► ...
                  └───┘
```

Wichtige Vorteile:

- **Mehrere Blöcke parallel** — kein Warten auf einen einzelnen Leader.
- **Keine leeren Slots** — wenn ein Validator seine Runde verpasst, absorbiert
  der DAG dies.
- **Schnellere Bestätigungen** — Transaktionen werden in Sekunden bestätigt,
  nicht in Minuten.

### Wie die Tip-Auswahl funktioniert

Wenn ein neuer Block erstellt wird, muss er zwei bestehende Blöcke auswählen,
mit denen er verbunden wird. Das Netzwerk führt eine randomisierte Suche durch
aktuelle Blöcke durch, die auf natürliche Weise die gesündesten Zweige begünstigt,
während alternative Pfade weiter wachsen können. Dies stellt sicher, dass sich neue
Blöcke selbst unter hoher Last konsistent an die am besten verbundenen Teile des
DAG anhängen.

### Transaktionsvalidierung

Jede Transaktion wird vor dem Eintritt in das Netzwerk geprüft:

1. **Formalprüfung** — keine doppelten Eingaben, keine negativen Werte.
2. **Signaturprüfung** — die Signatur des Absenders wird sofort überprüft.
3. **Double-Spend-Prävention** — dieselben Mittel können in der Warteschlange
   nicht zweimal ausgegeben werden.
4. **Gebührenprüfung** — jede Transaktion muss eine Mindestgebühr enthalten.

Ungültige Transaktionen werden bereits bei der Einreichung abgelehnt, nicht erst
bei der Blockproduktion.

---

## Proof of Stake und Finalität

### Wie Validatoren ausgewählt werden

Alle 12 Sekunden wird ein Validator zufällig ausgewählt, um einen Beacon-Block
zu erstellen. Die Auswahl verwendet eine unvoreingenommene Zufallsquelle, die von
keinem Teilnehmer vorhergesagt oder manipuliert werden kann.

Validatoren registrieren sich, indem sie mindestens 32 Millionen BIG hinterlegen.
Sie können bestraft werden, wenn sie sich falsch verhalten (z. B. durch Signieren
widersprüchlicher Blöcke). Zu den Strafen gehört der Verlust der eingesetzten
Mittel.

### Wie Finalität funktioniert

Am Ende jeder Epoche (alle 32 Slots, etwa 6,4 Minuten) bewertet das Netzwerk,
welche Blöcke genügend Validator-Unterstützung erhalten haben:

- Ein Block wird **gerechtfertigt (justified)**, wenn zwei Drittel der
  eingesetzten Validatoren ihn bezeugen.
- Ein Block wird **finalisiert (finalized)**, wenn die Chain, die er verlängert,
  bereits finalisiert war.
- Einmal finalisiert, kann ein Block nie rückgängig gemacht werden (es sei denn,
  ein Drittel aller eingesetzten Validatoren verschwört sich, dies zu tun).

### Gebührenpool und Validator-Belohnungen

Das System hat **keine Inflation** — es werden keine neuen Token als
Block-Subventionen geprägt. Das gesamte Angebot an BIG ist bei der Genesis
festgelegt und erhöht sich nie.

Jede Transaktion zahlt eine Gebühr. Gebühren sammeln sich während jeder Epoche
an und werden an der Epochengrenze an die Validatoren verteilt. Jeder Validator
erhält Belohnungen im Verhältnis zu seinem Einsatz. Dies gleicht die Anreize aus:
Validatoren verdienen mehr, indem sie mehr Ressourcen in das Netzwerk einbringen,
und der gesamte Belohnungspool stammt ausschließlich aus der Netzwerknutzung,
nicht aus Geldmengenausweitung.

---

## Multi-Layer-Architektur

### Ein Layer 0, viele Layer 1 Chains

Es gibt **weltweit genau eine Layer 0 Chain**. Sie ist die Abwicklungskette
(Settlement Chain), auf der der native Token (BIG) erzeugt und benutzerdefinierte
Token ausgegeben werden. Layer 0 ist die Quelle der Wahrheit für das Token-Angebot
und die Wurzel aller Wertübertragungen.

Es kann **viele Layer 1 Chains** geben, die jeweils unabhängig mit eigenen
Validatoren, eigener Datenbank und eigenem Konsens laufen:

```
                    Layer 0 (Settlement)
                           │
            ┌───────────────┼───────────────┐
            │               │               │
       L1-Order         L1-Contract       L1-Payment
     (Order-Matching)  (Smart Contract)  (nur Transfers)
            │               │               │
       L1-PAI           L1-NFT           (weitere L1s...)
     (AI-Provider)    (Non-Fungible)
```

Jede L1 Chain:
- Hat ihre eigenen Validatoren und ihren eigenen Konsens.
- Erhält BIG-Token nur per Brücke von L0 (kein natives Minting).
- Ist vollständig isoliert — ein Fehler auf einer L1 beeinträchtigt keine
  anderen.
- Verwendet ihre eigene `chainId`, um zu verhindern, dass Blöcke einer Chain von
  einer anderen akzeptiert werden.

### Horizontale Skalierbarkeit

Da jede L1 Chain vollständig isoliert und die Chain-ID konfigurierbar ist,
können Sie mehrere Instanzen desselben Typs betreiben:

```
CHAIN_ID=PAYMENT-US  →  Payment-Chain für die US-Region
CHAIN_ID=PAYMENT-EU  →  Payment-Chain für die EU-Region
```

Jede Instanz erreicht einen vergleichbaren Durchsatz wie L0. Der
Gesamtsystemdurchsatz skaliert linear mit der Anzahl der L1-Instanzen, ohne
Koordinationsaufwand zwischen den Chains.

### Verfügbare Chain-Typen

| Chain | Zweck |
|-------|---------|
| L0 Settlement | BIG-Minting, Token-Erstellung, globale Anker |
| L1 Order Match | Dezentrales Orderbuch-Matching |
| L1 Smart Contract | Allgemeine Vertragsausführung |
| L1 AI Provider | AI-Provider-Staking und Reputation |
| L1 NFT | Erstellung und Transfer von Non-Fungible Token |
| L1 Payment | Nur Transfers (minimale Angriffsfläche) |

---

## Post-Quanten-Sicherheit

Bigtangle verwendet zwei Post-Quanten-Signaturalgorithmen für jeden
Transaktionseingang. Beide sind von NIST zugelassene Endstandards:

| Algorithmus | Standard | Sicherheitsstufe |
|-----------|----------|---------------|
| ML-DSA-87 (Dilithium) | FIPS 204 | Kategorie 5 |
| SLH-DSA-SHA2-256s (SPHINCS+) | FIPS 205 | Kategorie 5 |

Die beiden Algorithmen beruhen auf mathematisch unabhängigen Annahmen
(Gitterkryptographie und Hash-Funktionen). Das Brechen des einen bricht nicht
den anderen. Ein Angreifer müsste beide gleichzeitig brechen, um eine einzige
Transaktion zu fälschen.

Ein standardmäßiger BIP39-Seed-Satz generiert deterministisch beide
Schlüsselpaare. Sollte ein Algorithmus jemals gebrochen werden, unterstützt das
System ein Upgrade auf einen Ersatz, ohne das Transaktionsformat zu ändern oder
vorhandene Guthaben ungültig zu machen.

---

## Vergleich

| Metrik | Bigtangle | Solana | Ethereum L1 | Visa |
|--------|-----------|--------|-------------|------|
| Ledger | DAG + Beacon Chain | Single Chain | Single Chain | Zentralisiert |
| Konsens | GHOST + Casper FFG | PoH + Tower BFT | Gasper | Autorität |
| Slot-Zeit | 12s | 400ms | 12s | — |
| Finalität | ~12,8 Min.* | ~12,8s | ~6,4 Min. | Sofort |
| Spitzen-Tx/s | ~4.873 | ~50.000* | ~30 | 24.000 |
| Beobachtete Tx/s | ~4.873 | ~2.000–3.000 | ~15–30 | ~1.700 |
| Parallele Ausführung | DAG-nativ | Sealevel (Analyse) | Sequentielles EVM | Sharded DB |

\*Vom Anbieter gemeldeter Laborspitzenwert; der beobachtete Mainnet-Durchsatz
ist wesentlich niedriger.

\*\*Casper FFG-Finalität (2 Epochen). Beacon-Block-Bestätigung ~24s (2 Slots).

### Wichtige Vorteile

- **Kein Single-Leader-Engpass** — Transaktionsblöcke können von jedem Knoten
  erstellt werden. Beacon-Block-Ersteller sorgen für Finalität, ohne den
  Durchsatz zu begrenzen.
- **Horizontale Skalierung** — zusätzliche L1 Chains erhöhen die Kapazität ohne
  Protokolländerungen.
- **UTXO-Modell** — Transaktionen sind von Natur aus parallel, da sie
  unterschiedliche Eingaben referenzieren.
- **Post-Quanten-Sicherheit** — duale Signaturen sind ab dem Start aktiv.

---

## Fazit

Bigtangle kombiniert einen parallelen DAG für hohen Durchsatz mit einer
Proof-of-Stake-Beacon-Chain für deterministische Finalität. Diese Architektur
beseitigt den Single-Leader-Engpass traditioneller Blockchains, während starke
Sicherheitsgarantien erhalten bleiben.

Layer 0 bietet globale Abwicklung. Unabhängige Layer 1 Chains ermöglichen
horizontale Skalierung über spezialisierte Anwendungen hinweg ohne
Chain-übergreifenden Koordinationsaufwand.

Zusammen liefern diese Designentscheidungen einen hohen Transaktionsdurchsatz,
schnelle Bestätigungen, modulare Skalierbarkeit, ein festes Token-Angebot und
Post-Quanten-Sicherheit innerhalb einer einheitlichen Architektur.

---

## Anhang: Entwicklerhandbuch

### Knotenkonfiguration

| Modul | Port | Standard-DB | Rolle |
|--------|------|------------|------|
| `layer0-server` | 8081 | `info_l0` | L0-Vollknoten |
| `l1-order-server` | 8083 | `info_order` | L1-Order-Matching |
| `l1-payment-server` | 8091 | `info_payment` | L1-Zahlungen |
| `l1-pai-server` | 8087 | `info_pai` | L1-AI-Provider |
| `l1-nft-server` | 8089 | `info_nft` | L1-NFT |

### Umgebungsvariablen

| Variable | Standard | Beschreibung |
|----------|---------|-------------|
| `CHAIN_ID` | (variiert) | L1-Chain-Kennung |
| `FEE_DEFAULT` | 1000 | Minimale Transaktionsgebühr (BIG) |
| `POS_SLOT_INTERVAL_MS` | 12000 | Slot-Dauer in ms |
| `POS_SLOTS_PER_EPOCH` | 32 | Slots pro Epoche |

### Blocktyp-Eingrenzung pro Chain

| Chain | Akzeptierte Blocktypen |
|-------|---------------------|
| L0 | Alle Typen |
| L1 Order Match | INITIAL, TRANSFER, BEACON, ORDER_OPEN, ORDER_CANCEL |
| L1 Contract | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 PAI | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 NFT | Alle Typen (verwendet L0-Parameter) |
| L1 Payment | Nur INITIAL, TRANSFER, BEACON |

### Belohnungsberechnung

An jeder Epochengrenze werden die angesammelten Gebühren verteilt:

```
Für jeden aktiven Validator:
  belohnung = validator.einsatz × pool / gesamter_aktiver_einsatz
```

### Gebührenakkumulation pro Block

```
Für jede Transaktion im Block:
  überschuss = summe_der_BIG_eingaben − summe_der_BIG_ausgaben
  wenn überschuss > 0:
    addiere überschuss zum akkumulierten_gebührenpool
```

### Post-Quanten-Schlüsselableitung

Ein 24-Wort-BIP39-Seed-Satz (256-Bit-Entropie) generiert deterministisch beide
Post-Quanten-Schlüsselpaare über HKDF-SHA256. Die ersten 32 Bytes seeden
ML-DSA-87, die zweiten 32 Bytes seeden SLH-DSA-SHA2-256s.

### Blockgrößenbudget (Post-Quanten-Signaturen)

| Komponente | Größe |
|-----------|------|
| ML-DSA-87-Signatur | 4,6 KB |
| SLH-DSA-256s-Signatur | 16 KB |
| Pro Eingabe gesamt | ~23 KB |
| 100 Tx/Block | ~3 MB (machbar) |
| 500 Tx/Block | ~12 MB (machbar) |
| 2.000 Tx/Block | ~48 MB (benötigt 100 MB Limit) |
