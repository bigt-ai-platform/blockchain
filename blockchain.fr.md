# Bigtangle : une blockchain multi-couche scalable

## Comment fonctionne Bigtangle

Bigtangle combine deux technologies :

- Un **DAG (graphe acyclique orienté)** qui permet à de nombreuses transactions d'être
  traitées simultanément pour une grande rapidité.
- Une **chaîne phare Proof-of-Stake (PoS)** qui confirme périodiquement ces
  transactions et les rend permanentes.

Pensez au DAG comme à une autoroute qui déplace le trafic rapidement, tandis que la chaîne
phare agit comme une autorité de la circulation qui certifie périodiquement que tout est
correct.

Toutes les 12 secondes, un validateur est choisi pour créer un **bloc phare**. Cette
balise confirme toute l'activité récente des transactions et la rend irréversible.

---

## Pourquoi Bigtangle

| Problème | Comment Bigtangle le résout |
|----------|-----------------------------|
| **Transactions lentes** — Ethereum Layer 1 fait ~30 tx/s, Bitcoin ~7 tx/s | Parallélisme du DAG : des milliers de transactions par seconde en parallèle |
| **Risque de leader unique** — si un leader désigné échoue, la chaîne s'arrête | Le traitement des transactions n'a pas de goulot d'étranglement de leader unique car les blocs DAG peuvent être créés en parallèle par plusieurs nœuds. Les blocs phares sont proposés par un validateur par slot pour fournir la finalité. |
| **Finalité lente** — Ethereum prend ~6 minutes pour un règlement irréversible | Finalité Casper FFG en ~12,8 minutes (2 époques) ; blocs phares confirmés en ~24 secondes |
| **Aucun passage à l'échelle horizontal** — La plupart des chaînes fonctionnent sur un seul registre | Plusieurs chaînes d'application L1, chacune avec ses propres validateurs et consensus |
| **Vulnérabilité quantique** — les signatures ECDSA peuvent être brisées par les ordinateurs quantiques | Signatures post-quantiques doubles utilisant deux algorithmes approuvés par le NIST |

---

## Architecture

Bigtangle exécute deux couches indépendantes :

```
              Beacon Chain (Security)

Beacon 1 -------- Beacon 2 -------- Beacon 3
     |                |                 |
     | confirms       | confirms        | confirms
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

- Le **DAG** gère le débit des transactions. Parce que de nombreux blocs peuvent être
  créés en même temps, le réseau a besoin d'un moyen de décider à quels blocs
  existants un nouveau bloc doit se connecter. Bigtangle résout cela en utilisant une
  recherche aléatoire parmi les blocs récents. La recherche favorise naturellement
  les branches bien connectées tout en permettant aux chemins alternatifs de se développer.
  (Cette technique s'appelle Markov Chain Monte Carlo, ou MCMC.)

- La **chaîne phare** fournit la finalité. Toutes les 12 secondes, un validateur
  choisi par un processus aléatoire impartial produit un bloc phare qui confirme
  l'activité récente du DAG. Bigtangle utilise le même mécanisme de finalité que
  Ethereum (Casper FFG). Une fois que les deux tiers des validateurs sont d'accord, les
  blocs confirmés deviennent permanents.

Les deux couches sont complémentaires : le DAG offre rapidité et parallélisme ;
la chaîne phare offre sécurité et finalité.

---

## DAG et traitement parallèle

### Comment fonctionne le DAG

Dans une blockchain traditionnelle, les blocs sont créés les uns après les autres en une seule
ligne. Cela crée un goulot d'étranglement — tout le monde doit attendre le bloc suivant.

Bigtangle utilise un DAG, où chaque bloc se connecte à deux blocs précédents
au lieu d'un. Cela permet à plusieurs blocs d'être produits simultanément :

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

Avantages clés :

- **Plusieurs blocs en parallèle** — pas d'attente pour un seul leader.
- **Aucun slot vide** — si un validateur manque son tour, le DAG l'absorbe.
- **Confirmations plus rapides** — les transactions sont confirmées en secondes, pas en
  minutes.

### Comment fonctionne la sélection des pointes

Lorsqu'un nouveau bloc est créé, il doit choisir deux blocs existants auxquels
se connecter. Le réseau effectue une recherche aléatoire parmi les blocs récents
qui favorise naturellement les branches les plus saines tout en permettant
aux chemins alternatifs de se développer. Cela garantit que les nouveaux blocs s'attachent systématiquement aux
parties les mieux connectées du DAG même sous charge élevée.

### Validation des transactions

Chaque transaction est vérifiée avant d'entrer dans le réseau :

1. **Vérification du format** — pas d'entrées en double, pas de valeurs négatives.
2. **Vérification de la signature** — la signature de l'expéditeur est vérifiée
   immédiatement.
3. **Prévention de la double dépense** — les mêmes fonds ne peuvent pas être dépensés deux fois dans
   la file d'attente en attente.
4. **Vérification des frais** — chaque transaction doit inclure des frais minimums.

Les transactions invalides sont rejetées au moment de la soumission, pas lorsqu'un bloc est
produit.

---

## Preuve d'enjeu et finalité

### Comment les validateurs sont choisis

Toutes les 12 secondes, un validateur est sélectionné aléatoirement pour produire un bloc
phare. La sélection utilise une source d'aléa impartial qui ne peut être
prédite ou manipulée par aucun participant.

Les validateurs s'inscrivent en déposant au moins 32 millions de BIG. Ils peuvent être
pénalisés s'ils se comportent mal (par exemple, en signant des blocs contradictoires). Les pénalités
incluent la perte des fonds mis en jeu.

### Comment fonctionne la finalité

À la fin de chaque époque (tous les 32 slots, environ 6,4 minutes), le
réseau évalue quels blocs ont reçu suffisamment de soutien des validateurs :

- Un bloc devient **justifié** lorsque les deux tiers des validateurs en jeu
  l'attestent.
- Un bloc devient **finalisé** lorsque la chaîne qu'il étend était déjà
  finalisée.
- Une fois finalisé, un bloc ne peut jamais être réverti (à moins qu'un tiers de tous les
  validateurs en jeu conspire pour le faire).

### Pool de frais et récompenses des validateurs

Le système a **zéro inflation** — aucun nouveau jeton n'est frappé comme
subventions de bloc. L'offre totale de BIG est fixée lors de la genèse et n'augmente jamais.

Chaque transaction paie des frais. Les frais s'accumulent tout au long de chaque époque et sont
distribués aux validateurs à la limite de l'époque. Chaque validateur reçoit
des récompenses proportionnellement au montant qu'il a mis en jeu. Cela aligne les incitations :
les validateurs gagnent plus en engageant plus de ressources dans le réseau, et le
pool de récompenses provient entièrement de l'utilisation du réseau plutôt que de l'expansion
monétaire.

---

## Architecture multi-couche

### Une couche 0, plusieurs chaînes de couche 1

Il existe **exactement une chaîne de couche 0 dans le monde**. C'est la chaîne de règlement
où le jeton natif (BIG) est créé et les jetons personnalisés sont émis. La couche 0
est la source de vérité pour l'offre de jetons et la racine de tous les transferts de valeur.

Il peut y avoir **plusieurs chaînes de couche 1**, chacune fonctionnant indépendamment avec ses propres
validateurs, base de données et consensus :

```
                    Layer 0 (settlement)
                           │
           ┌───────────────┼───────────────┐
           │               │               │
      L1-order        L1-contract       L1-payment
    (order match)    (smart contract)  (transfers only)
           │               │               │
      L1-PAI           L1-NFT           (more L1s...)
    (AI provider)    (non-fungible)
```

Chaque chaîne L1 :
- A ses propres validateurs et consensus.
- Reçoit des jetons BIG uniquement via un pont depuis L0 (pas de frappe native).
- Est totalement isolée — une défaillance sur une L1 n'affecte pas les autres.
- Utilise son propre `chainId` pour empêcher les blocs d'une chaîne d'être acceptés par
  une autre.

### Passage à l'échelle horizontal

Parce que chaque chaîne L1 est totalement isolée et que l'identifiant de chaîne est configurable,
vous pouvez exécuter plusieurs instances du même type :

```
CHAIN_ID=PAYMENT-US  →  payment chain for US region
CHAIN_ID=PAYMENT-EU  →  payment chain for EU region
```

Chaque instance atteint un débit comparable à L0. Le débit total du système
augmente linéairement avec le nombre d'instances L1, sans frais de coordination
inter-chaînes.

### Types de chaînes disponibles

| Chaîne | Objectif |
|--------|----------|
| L0 settlement | Frappe de BIG, création de jetons, ancres globales |
| L1 order match | Correspondance décentralisée de carnet d'ordres |
| L1 smart contract | Exécution de contrats à usage général |
| L1 AI provider | Mise en jeu et réputation des fournisseurs d'IA |
| L1 NFT | Création et transfert de jetons non fongibles |
| L1 payment | Transfert uniquement (surface d'attaque minimale) |

---

## Sécurité post-quantique

Bigtangle utilise deux algorithmes de signature post-quantique sur chaque entrée
de transaction. Les deux sont des normes finales approuvées par le NIST :

| Algorithme | Norme | Niveau de sécurité |
|------------|-------|--------------------|
| ML-DSA-87 (Dilithium) | FIPS 204 | Catégorie 5 |
| SLH-DSA-SHA2-256s (SPHINCS+) | FIPS 205 | Catégorie 5 |

Les deux algorithmes reposent sur des hypothèses mathématiquement indépendantes (cryptographie
sur réseaux et fonctions de hachage). Briser l'un ne brise pas l'autre. Un
attaquant devrait briser les deux simultanément pour falsifier une seule
transaction.

Une phrase de départ BIP39 standard génère déterministiquement les deux paires de clés.
Si un algorithme est un jour brisé, le système prend en charge la mise à niveau vers un
remplacement sans changer le format de transaction ni invalider les
fonds existants.

---

## Comparaison

| Métrique | Bigtangle | Solana | Ethereum L1 | Visa |
|----------|-----------|--------|-------------|------|
| Registre | DAG + beacon chain | Single chain | Single chain | Centralisé |
| Consensus | MCMC + Casper FFG | PoH + Tower BFT | Gasper | Autorité |
| Temps de slot | 12s | 400ms | 12s | — |
| Finalité | ~12,8 min* | ~12,8s | ~6,4 min | Instantané |
| Tx/s de pointe | ~4 873 | ~50 000* | ~30 | 24 000 |
| Tx/s observées | ~4 873 | ~2 000–3 000 | ~15–30 | ~1 700 |
| Exécution parallèle | DAG-natif | Sealevel (analyse) | EVM séquentiel | DB fragmentée |

\*Pointe déclarée en laboratoire ; le débit observé sur le mainnet est
significativement inférieur.

\*\*Finalité Casper FFG (2 époques). Confirmation du bloc phare ~24s (2 slots).

### Avantages clés

- **Pas de goulot d'étranglement de leader unique** — les blocs de transactions peuvent être créés par
  n'importe quel nœud. Les proposants de blocs phares fournissent la finalité sans limiter
  le débit.
- **Passage à l'échelle horizontal** — des chaînes L1 supplémentaires augmentent la capacité sans
  changements de protocole.
- **Modèle UTXO** — les transactions sont intrinsèquement parallèles car elles
  référencent des entrées distinctes.
- **Sécurité post-quantique** — les signatures doubles sont actives dès le lancement.

---

## Conclusion

Bigtangle combine un DAG parallèle pour un débit élevé avec une chaîne
phare Proof-of-Stake pour une finalité déterministe. Cette architecture supprime le
goulot d'étranglement de leader unique présent dans les blockchains traditionnelles tout en préservant
de fortes garanties de sécurité.

La couche 0 fournit un règlement global. Les chaînes de couche 1 indépendantes permettent un
passage à l'échelle horizontal entre des applications spécialisées sans frais de coordination
inter-chaînes.

Ensemble, ces choix de conception offrent un débit de transactions élevé, une confirmation
rapide, une évolutivité modulaire, une offre de jetons fixe et une sécurité
post-quantique dans une architecture unifiée.

---

## Annexe : Guide du développeur

### Configuration des nœuds

| Module | Port | DB par défaut | Rôle |
|--------|------|---------------|------|
| `layer0-server` | 8081 | `info_l0` | Nœud complet L0 |
| `layer0-mcmc` | 8082 | — | Consensus L0 |
| `l1-order-server` | 8083 | `info_order` | Correspondance d'ordres L1 |
| `l1-payment-server` | 8091 | `info_payment` | Paiement L1 |
| `l1-pai-server` | 8087 | `info_pai` | Fournisseur d'IA L1 |
| `l1-nft-server` | 8089 | `info_nft` | NFT L1 |

### Variables d'environnement

| Variable | Défaut | Description |
|----------|--------|-------------|
| `CHAIN_ID` | (varie) | Identifiant de chaîne L1 |
| `FEE_DEFAULT` | 1000 | Frais de transaction minimum (BIG) |
| `POS_SLOT_INTERVAL_MS` | 12000 | Durée du slot en ms |
| `POS_SLOTS_PER_EPOCH` | 32 | Slots par époque |

### Portée des types de blocs par chaîne

| Chaîne | Types de blocs acceptés |
|--------|------------------------|
| L0 | Tous les types |
| L1 order match | INITIAL, TRANSFER, BEACON, ORDER_OPEN, ORDER_CANCEL |
| L1 contract | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 PAI | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 NFT | Tous les types (réutilise les paramètres L0) |
| L1 payment | INITIAL, TRANSFER, BEACON uniquement |

### Calcul des récompenses

À chaque limite d'époque, les frais accumulés sont distribués :

```
For each active validator:
  reward = validator.stake × pool / total_active_stake
```

### Accumulation des frais par bloc

```
For each transaction in block:
  surplus = sum_of_BIG_inputs − sum_of_BIG_outputs
  if surplus > 0:
    add surplus to accumulated_fee_pool
```

### Dérivation des clés post-quantique

Une phrase de départ BIP39 de 24 mots (entropie 256 bits) génère déterministiquement
les deux paires de clés post-quantiques via HKDF-SHA256. Les 32 premiers octets
alimentent ML-DSA-87, les 32 seconds octets alimentent SLH-DSA-SHA2-256s.

### Budget de taille de bloc (signatures post-quantique)

| Composant | Taille |
|-----------|--------|
| Signature ML-DSA-87 | 4,6 Ko |
| Signature SLH-DSA-256s | 16 Ko |
| Total par entrée | ~23 Ko |
| 100 tx/bloc | ~3 Mo (faisable) |
| 500 tx/bloc | ~12 Mo (faisable) |
| 2 000 tx/bloc | ~48 Mo (nécessite limite de 100 Mo) |
