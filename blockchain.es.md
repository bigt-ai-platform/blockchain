# Bigtangle: Una Cadena de Bloques Multicapa Escalable

## Cómo Funciona Bigtangle

Bigtangle combina dos tecnologías:

- Un **DAG (Grafo Acíclico Dirigido)** que permite procesar muchas transacciones
  simultáneamente para alta velocidad.
- Una **cadena faro de Prueba de Participación (PoS)** que confirma periódicamente esas
  transacciones y las hace permanentes.

Piense en el DAG como una autopista que mueve el tráfico rápidamente, mientras que la cadena
faro actúa como una autoridad de tránsito que certifica periódicamente que todo es
correcto.

Cada 12 segundos, se elige un validador para crear un **bloque faro**. Este
faro confirma toda la actividad de transacciones reciente y la hace irreversible.

---

## Por Qué Bigtangle

| Problema | Cómo lo Soluciona Bigtangle |
|---------|------------------------------|
| **Transacciones lentas** — Ethereum Capa 1 hace ~30 tx/s, Bitcoin ~7 tx/s | Paralelismo DAG: miles de transacciones por segundo en paralelo |
| **Riesgo de líder único** — si un líder designado falla, la cadena se detiene | El procesamiento de transacciones no tiene cuello de botella de líder único porque los bloques DAG pueden ser creados en paralelo por muchos nodos. Los bloques faro son propuestos por un validador por ranura para proporcionar finalidad. |
| **Finalidad lenta** — Ethereum tarda ~6 minutos en liquidación irreversible | Finalidad Casper FFG en ~12.8 minutos (2 épocas); bloques faro confirmados en ~24 segundos |
| **Sin escalado horizontal** — La mayoría de cadenas se ejecutan en un solo libro contable | Múltiples cadenas de aplicación L1, cada una con sus propios validadores y consenso |
| **Vulnerabilidad cuántica** — Las firmas ECDSA pueden ser rotas por computadoras cuánticas | Firmas post-cuánticas duales usando dos algoritmos aprobados por NIST |

---

## Arquitectura

Bigtangle ejecuta dos capas independientes:

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

- El **DAG** maneja el rendimiento de transacciones. Debido a que muchos bloques pueden
  crearse al mismo tiempo, la red necesita una forma de decidir a qué bloques
  existentes debe conectarse un nuevo bloque. Bigtangle resuelve esto usando una
  búsqueda aleatorizada a través de bloques recientes. La búsqueda favorece naturalmente
  las ramas bien conectadas mientras aún permite que crezcan caminos alternativos.
  (Esta técnica se llama Monte Carlo de Cadena de Markov, o MCMC.)

- La **cadena faro** proporciona finalidad. Cada 12 segundos, un validador
  elegido por un proceso aleatorio imparcial produce un bloque faro que confirma
  la actividad reciente del DAG. Bigtangle usa el mismo mecanismo de finalidad que
  Ethereum (Casper FFG). Una vez que dos tercios de los validadores están de acuerdo, los bloques
  confirmados se vuelven permanentes.

Las dos capas son complementarias: el DAG proporciona velocidad y paralelismo;
la cadena faro proporciona seguridad y finalidad.

---

## DAG y Procesamiento en Paralelo

### Cómo funciona el DAG

En una cadena de bloques tradicional, los bloques se crean uno tras otro en una sola
línea. Esto crea un cuello de botella — todos deben esperar al siguiente bloque.

Bigtangle usa un DAG, donde cada bloque se conecta a dos bloques anteriores
en lugar de uno. Esto permite que múltiples bloques se produzcan simultáneamente:

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

Beneficios clave:

- **Múltiples bloques en paralelo** — sin esperar a un solo líder.
- **Sin ranuras vacías** — si un validador pierde su turno, el DAG lo absorbe.
- **Confirmaciones más rápidas** — las transacciones se confirman en segundos, no en
  minutos.

### Cómo funciona la selección de punta

Cuando se crea un nuevo bloque, necesita elegir dos bloques existentes para
conectarse. La red realiza una búsqueda aleatorizada a través de bloques recientes
que favorece naturalmente las ramas más saludables mientras aún permite que
crezcan caminos alternativos. Esto asegura que los nuevos bloques se adjunten consistentemente a las
partes mejor conectadas del DAG incluso bajo alta carga.

### Validación de transacciones

Cada transacción se verifica antes de entrar a la red:

1. **Verificación de formato** — sin entradas duplicadas, sin valores negativos.
2. **Verificación de firma** — la firma del remitente se verifica
   inmediatamente.
3. **Prevención de doble gasto** — los mismos fondos no pueden gastarse dos veces en
   la cola pendiente.
4. **Verificación de comisión** — cada transacción debe incluir una comisión mínima.

Las transacciones inválidas se rechazan al momento de envío, no cuando se produce un
bloque.

---

## Prueba de Participación y Finalidad

### Cómo se eligen los validadores

Cada 12 segundos, un validador es seleccionado aleatoriamente para producir un bloque
faro. La selección utiliza una fuente de aleatoriedad imparcial que no puede ser
predicha ni manipulada por ningún participante.

Los validadores se registran depositando al menos 32 millones de BIG. Pueden ser
penalizados si se comportan incorrectamente (ej., firmando bloques conflictivos). Las penalizaciones
incluyen la pérdida de fondos apostados.

### Cómo funciona la finalidad

Al final de cada época (cada 32 ranuras, aproximadamente 6.4 minutos), la
red evalúa qué bloques han recibido suficiente apoyo de validadores:

- Un bloque se **justifica** cuando dos tercios de los validadores apostados lo
  atestiguan.
- Un bloque se **finaliza** cuando la cadena que extiende ya estaba
  finalizada.
- Una vez finalizado, un bloque nunca puede revertirse (a menos que un tercio de todos los
  validadores apostados conspire para hacerlo).

### Pool de comisiones y recompensas a validadores

El sistema tiene **inflación cero** — no se acuñan nuevos tokens como subsidios de
bloque. La oferta total de BIG es fija en el génesis y nunca aumenta.

Cada transacción paga una comisión. Las comisiones se acumulan durante cada época y se
distribuyen a los validadores al límite de la época. Cada validador recibe
recompensas en proporción a la cantidad que ha apostado. Esto alinea los incentivos:
los validadores ganan más al comprometer más recursos a la red, y todo el
pool de recompensas proviene enteramente del uso de la red en lugar de la expansión
monetaria.

---

## Arquitectura Multicapa

### Una Capa 0, muchas cadenas Capa 1

Hay **exactamente una cadena Capa 0 en todo el mundo**. Es la cadena de liquidación
donde se crea el token nativo (BIG) y se emiten tokens personalizados. La Capa 0
es la fuente de verdad para el suministro de tokens y la raíz de todas las transferencias de valor.

Puede haber **muchas cadenas Capa 1**, cada una ejecutándose independientemente con sus propios
validadores, base de datos y consenso:

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

Cada cadena L1:
- Tiene sus propios validadores y consenso.
- Recibe tokens BIG solo mediante puente desde L0 (sin acuñación nativa).
- Está completamente aislada — una falla en una L1 no afecta a otras.
- Usa su propio `chainId` para evitar que bloques de una cadena sean aceptados por
  otra.

### Escalabilidad horizontal

Debido a que cada cadena L1 está completamente aislada y el ID de cadena es configurable,
puede ejecutar múltiples instancias del mismo tipo:

```
CHAIN_ID=PAYMENT-US  →  payment chain for US region
CHAIN_ID=PAYMENT-EU  →  payment chain for EU region
```

Cada instancia logra un rendimiento comparable al de L0. El rendimiento total del sistema
escala linealmente con el número de instancias L1, sin sobrecarga de coordinación
entre cadenas.

### Tipos de cadena disponibles

| Cadena | Propósito |
|-------|-----------|
| Liquidación L0 | Acuñación de BIG, creación de tokens, anclajes globales |
| Emparejamiento de órdenes L1 | Emparejamiento descentralizado de libro de órdenes |
| Contrato inteligente L1 | Ejecución de contratos de propósito general |
| Proveedor de IA L1 | Apuesta y reputación de proveedores de IA |
| NFT L1 | Creación y transferencia de tokens no fungibles |
| Pago L1 | Solo transferencias (superficie de ataque mínima) |

---

## Seguridad Post-Cuántica

Bigtangle usa dos algoritmos de firma post-cuántica en cada entrada de transacción.
Ambos son estándares finales aprobados por NIST:

| Algoritmo | Estándar | Nivel de Seguridad |
|-----------|----------|---------------|
| ML-DSA-87 (Dilithium) | FIPS 204 | Categoría 5 |
| SLH-DSA-SHA2-256s (SPHINCS+) | FIPS 205 | Categoría 5 |

Los dos algoritmos se basan en supuestos matemáticamente independientes (criptografía
de retículos y funciones hash). Romper uno no rompe el otro. Un
atacante necesitaría romper ambos simultáneamente para falsificar una sola
transacción.

Una frase semilla BIP39 estándar genera determinísticamente ambos pares de claves.
Si algún algoritmo llegara a romperse, el sistema soporta la actualización a un
reemplazo sin cambiar el formato de transacción ni invalidar fondos
existentes.

---

## Comparación

| Métrica | Bigtangle | Solana | Ethereum L1 | Visa |
|--------|-----------|--------|-------------|------|
| Libro contable | DAG + cadena faro | Cadena única | Cadena única | Centralizado |
| Consenso | MCMC + Casper FFG | PoH + Tower BFT | Gasper | Autoridad |
| Tiempo de ranura | 12s | 400ms | 12s | — |
| Finalidad | ~12.8 min* | ~12.8s | ~6.4 min | Instantánea |
| Pico tx/s | ~4,873 | ~50,000* | ~30 | 24,000 |
| Tx/s observadas | ~4,873 | ~2,000–3,000 | ~15–30 | ~1,700 |
| Ejecución en paralelo | Nativo DAG | Sealevel (análisis) | EVM secuencial | BD fragmentada |

\*Pico de laboratorio reportado por el proveedor; el rendimiento observado en mainnet es
significativamente menor.

\*\*Finalidad Casper FFG (2 épocas). Confirmación de bloque faro ~24s (2 ranuras).

### Ventajas clave

- **Sin cuello de botella de líder único** — los bloques de transacciones pueden ser creados por
  cualquier nodo. Los proponentes de bloques faro proporcionan finalidad sin limitar el
  rendimiento.
- **Escalado horizontal** — cadenas L1 adicionales aumentan la capacidad sin
  cambios de protocolo.
- **Modelo UTXO** — las transacciones son inherentemente paralelas porque
  referencian entradas distintas.
- **Seguridad post-cuántica** — firmas duales activas desde el lanzamiento.

---

## Conclusión

Bigtangle combina un DAG paralelo para alto rendimiento con una cadena faro
de Prueba de Participación para finalidad determinista. Esta arquitectura elimina el
cuello de botella de líder único encontrado en las cadenas de bloques tradicionales mientras preserva
fuertes garantías de seguridad.

La Capa 0 proporciona liquidación global. Las cadenas Capa 1 independientes permiten el
escalado horizontal a través de aplicaciones especializadas sin sobrecarga de coordinación
entre cadenas.

En conjunto, estas decisiones de diseño ofrecen alto rendimiento de transacciones, confirmación
rápida, escalabilidad modular, suministro fijo de tokens y seguridad post-cuántica
dentro de una arquitectura unificada.

---

## Apéndice: Guía del Desarrollador

### Configuración de nodo

| Módulo | Puerto | BD predeterminada | Rol |
|--------|------|------------|------|
| `layer0-server` | 8081 | `info_l0` | Nodo completo L0 |
| `layer0-mcmc` | 8082 | — | Consenso L0 |
| `l1-order-server` | 8083 | `info_order` | Emparejamiento de órdenes L1 |
| `l1-payment-server` | 8091 | `info_payment` | Pago L1 |
| `l1-pai-server` | 8087 | `info_pai` | Proveedor de IA L1 |
| `l1-nft-server` | 8089 | `info_nft` | NFT L1 |

### Variables de entorno

| Variable | Predeterminado | Descripción |
|----------|---------|-------------|
| `CHAIN_ID` | (varía) | Identificador de cadena L1 |
| `FEE_DEFAULT` | 1000 | Comisión mínima de transacción (BIG) |
| `POS_SLOT_INTERVAL_MS` | 12000 | Duración de ranura en ms |
| `POS_SLOTS_PER_EPOCH` | 32 | Ranuras por época |

### Alcance de tipos de bloque por cadena

| Cadena | Tipos de bloque aceptados |
|-------|-------------------------|
| L0 | Todos los tipos |
| L1 order match | INITIAL, TRANSFER, BEACON, ORDER_OPEN, ORDER_CANCEL |
| L1 contract | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 PAI | INITIAL, TRANSFER, BEACON, CONTRACT_EVENT, CONTRACTEVENT_CANCEL |
| L1 NFT | Todos los tipos (reutiliza parámetros L0) |
| L1 payment | Solo INITIAL, TRANSFER, BEACON |

### Cálculo de recompensas

En cada límite de época, las comisiones acumuladas se distribuyen:

```
For each active validator:
  reward = validator.stake × pool / total_active_stake
```

### Acumulación de comisiones por bloque

```
For each transaction in block:
  surplus = sum_of_BIG_inputs − sum_of_BIG_outputs
  if surplus > 0:
    add surplus to accumulated_fee_pool
```

### Derivación de claves post-cuánticas

Una frase semilla BIP39 de 24 palabras (entropía de 256 bits) genera determinísticamente
ambos pares de claves post-cuánticas mediante HKDF-SHA256. Los primeros 32 bytes siembran
ML-DSA-87, los segundos 32 bytes siembran SLH-DSA-SHA2-256s.

### Presupuesto de tamaño de bloque (firmas post-cuánticas)

| Componente | Tamaño |
|-----------|--------|
| Firma ML-DSA-87 | 4.6 KB |
| Firma SLH-DSA-256s | 16 KB |
| Total por entrada | ~23 KB |
| 100 tx/bloque | ~3 MB (factible) |
| 500 tx/bloque | ~12 MB (factible) |
| 2,000 tx/bloque | ~48 MB (necesita límite de 100 MB) |
