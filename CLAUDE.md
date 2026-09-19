# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Descripcion del Proyecto

Backend API para **AromaStudio** — negocio de perfumes arabes importados desde USA a Peru. Es a la vez tienda
(catalogo publico + pedidos) y **ERP de importacion**: agrupa la demanda en consolidados, importa las listas de
precios de varios proveedores, decide en que proveedor comprar cada perfume respetando sus minimos, y lleva el
inventario/ventas del canal retail. Precios en USD (costo proveedor) y PEN (venta), con tipo de cambio configurable.

## Stack Tecnico

- Java 17, Spring Boot 3.4, Spring Security + JWT (jjwt 0.12.6), Apache POI 5.3 (Excel)
- PostgreSQL en produccion (`DATABASE_URL`, Aiven) / H2 archivo en local (`./data/aromastudio`)
- `spring.jpa.hibernate.ddl-auto=update` — **no hay Flyway/Liquibase**: el esquema lo deriva Hibernate y las
  migraciones de datos son metodos "once" del seeder (ver abajo)
- Sin Lombok — getters/setters manuales en todas las entidades

## Comandos

```bash
./mvnw spring-boot:run                        # servidor en :8080 (mvnw.cmd desde PowerShell)
./mvnw test                                   # toda la suite
./mvnw test -Dtest=MatchScorerTest            # una clase
./mvnw test -Dtest=AllocationServiceTest#nombreDelMetodo   # un solo test
./mvnw package -DskipTests                    # JAR
docker build -t aromastudio .                 # imagen (multi-stage, temurin 17)
```

Consola H2 **apagada por defecto** (`spring.h2.console.enabled=${H2_CONSOLE:false}`: el jar de produccion lleva H2 y
una consola publica ejecuta SQL en el servidor). En local: `H2_CONSOLE=true ./mvnw spring-boot:run` (PowerShell:
`$env:H2_CONSOLE='true'; .\mvnw.cmd spring-boot:run`) y abrir `/h2-console`.
Los tests levantan `@SpringBootTest` contra H2 **en memoria**
(`@TestPropertySource` con `jdbc:h2:mem:...` + `ddl-auto=create-drop` + `app.keep-alive.url=` vacio); copiar ese
bloque al crear un test nuevo, si no se contamina la BD de archivo.

**Gotcha**: `SmokeRealSupplierFilesTest` lee Excels reales de `provedores/`, carpeta que esta en `.gitignore`.
En un checkout limpio ese test falla — no es una regresion.

## Arquitectura

Seis subsistemas que comparten el catalogo `Product`:

### 1. Catalogo, pedidos y consolidados

Flujo central: **Product -> Order (con OrderItems) -> Consolidado**.

- **Consolidado** = lote de importacion. Ciclo `PROGRAMADO -> ABIERTO -> CERRADO -> ENTREGADO`.
  Tiene plazo (`startAt`/`endsAt` como `Instant`) y banner (`imageMediaId`). `ConsolidadoScheduler` (@Scheduled cada
  60s) abre los PROGRAMADO vencidos y cierra los ABIERTO con plazo vencido. Un ABIERTO con `endsAt == null` (el
  consolidado historico de produccion) **nunca** se toca. `reopenTemporarily` reabre uno cerrado con plazo corto.
- **Order.channel** distingue `CONSOLIDADO` (encargo, sujeto al plazo) de `STOCK` (entrega inmediata desde tienda).
  Los `STOCK` se excluyen de demanda, compra y KPIs del consolidado. Un pedido no puede mezclar canales.
- **Estados de pago**: `PENDIENTE_SEPARACION -> SEPARADO -> PENDIENTE_RESTO -> PAGADO -> VERIFICADO` (o `RECHAZADO`).
  Solo `ACTIVE_STATUSES` (SEPARADO en adelante) cuenta para demanda/compra/faltantes: **el admin debe aceptar el
  pedido para que entre al consolidado**.
- `clientName = "COMPRA TIENDA"` (`ConsolidadoService.COMPRA_TIENDA`) marca compras internas para stock propio.
- Al pasar a ENTREGADO, `enableMerchandise` vuelca la mercaderia al inventario retail (`RetailInventory` /
  `RetailSale`, canal tienda fisica y WhatsApp).

### 2. Multi-proveedor: import e identidad de producto

`Supplier` -> `SupplierOffer` (una oferta por proveedor y producto: costo USD, stock, GTIN crudo/canonico).

Flujo de importacion (`ImportBatchService`): **subir -> preview (no toca la tienda) -> corregir columnas ->
publicar o descartar**. El Excel queda guardado en el batch (`fileBase64`) para re-parsear con otro mapeo;
estados `PENDING / PUBLISHED / DISCARDED`.

- **Parsers** (`service/parser/`): uno afinado por proveedor (`ZimaxxParser`, `MagnetParser`, registrados por
  `supplierName()`), con `GenericSupplierParser` como fallback que auto-detecta header y columnas. Proveedor
  nuevo = una clase mas implementando `SupplierExcelParser`; se inyectan por `List<SupplierExcelParser>`.
- **Identidad L1 — GTIN** (`GtinCanonicalizer`): canonicaliza a GTIN-14 y **valida checksum GS1**. Regla dura:
  un codigo que no valida jamas define identidad; la fila se degrada a "sin GTIN" (`gtinStatus=CHECKSUM_FAIL`)
  y pasa a L2. Esto evita los "productos fantasma" por typos del proveedor.
- **Identidad L2 — nombre** (`service/matching/`): `FingerprintExtractor` arma una huella (marca + tokens +
  ml + concentracion + genero + presentacion) y `MatchScorer` decide `AUTO_MATCH / REVIEW / NEW` con **puertas
  duras**: marca, presentacion (tester vs regular), tamano (tolerancia por conversion Oz) y concentracion
  distintas => NEW. Cualquier token residual impide AUTO (trampa de flankers: "Yara" vs "Yara Candy").
  `MatchingEngine.Session` carga el catalogo una vez por import e indexa por token de marca (nunca LIKE).
- Lo dudoso no se decide solo: entra a la cola `MatchCandidate` (`PENDING`) que el admin resuelve en
  `MatchReviewController`. `ProductMergeService` fusiona re-apuntando ofertas, items, inventario, ventas y
  promociones; **el duplicado no se borra**: queda `archived` con `mergedIntoId` para trazabilidad.
- `DuplicateScanService` propone pares del catalogo historico; solo puebla la cola, jamas fusiona.

### 3. Precios

- **CostBasisService es la fuente unica del costo USD** de un producto (antes divergian import y consolidado).
  Estrategia configurable en `app_config: pricing_basis` — `CHEAPEST` (default) / `PRIORITY` / `WORST_PLAUSIBLE`.
- **PricingService** centraliza las formulas que reemplazaron al Excel (envio por peso, landed cost, precio
  sugerido, cajas, courier) leyendo todo de `app_config` en runtime. En operaciones masivas (preview/commit de un
  import) envolver en `pricing.withConfigSnapshot(...)`: cada clave se lee una vez (antes ~7 consultas por fila).
- `recomputeProductPrice` reprecia desde las ofertas activas; sin ofertas el producto se oculta
  (`available=false`). Respeta `priceLocked` (edicion manual del admin).
- **PriceRippleService** hace el recalculo masivo en background: activar/desactivar/borrar un proveedor responde
  al instante y los precios se propagan detras (antes moria por timeout contra la BD remota).

### 4. Asignacion de compra (que comprar a quien)

`AllocationOptimizer` (v2) responde: **FORZAR** el minimo de un proveedor moviendo lineas hacia el, o **SALTARLO**
comprando en otro lado con relleno de tienda / venta perdida. Metodo: baseline al mas barato elegible (respetando
el piso de margen), enumeracion exacta de 2^k subconjuntos de proveedores con minimo insatisfecho (`MAX_ENUM=4`),
y una pasada 1-opt.

- Las restricciones son **datos**, no codigo: `SupplierConstraint` (`MIN_ORDER_USD`, `MIN_UNITS`,
  `MIN_UNITS_PER_BRAND`) evaluadas por `ConstraintEvaluator` plugables via `ConstraintRegistry`. Tipo nuevo =
  un bean nuevo; el motor no cambia.
- `AllocationService` es la fachada: `computeAllocation` (advisory) y el flujo persistido
  `compute (PurchasePlan DRAFT) -> confirm (CONFIRMED, con guardia de margen)`; la ganancia del consolidado usa
  el costo REAL del plan confirmado. `consolidateToSupplier` ("comprar solo en un proveedor") reusa la asignacion
  ya calculada sin volver a correr el motor.
- `SupplierExcelFiller` + `SupplierExcelLayout` devuelven el **Excel original del proveedor** con la columna de
  cantidad llena (match por UPC, con fallback a claves exactas de `SupplierOffer`: digitos crudos, SKU, titulo).
  Se re-guarda el mismo workbook con POI para preservar estilos/formulas. Formato nuevo = un `@Component` mas.

### 5. Fotos y media

- `ApifyImageService` busca fotos por NOMBRE (actores Google Images / Bing / Fragrantica, configurables por env)
  y puntua candidatas descartando redes/noticias. `ImageEnrichService` sirve primero desde `ImageCache` (por UPC,
  con el ranking completo en `candidates_json`) y solo llama a Apify por lo que falta; `force` ignora el cache.
- `MediaImage` guarda banners **en la BD en base64** (el hosting no tiene disco persistente). Lectura publica en
  `GET /api/media/{id}` con cache inmutable; tope ~800KB de payload.
- El token de Apify va en `APIFY_TOKEN`, nunca en el codigo.

### 6. NSO (Notificacion Sanitaria): solo se muestra y se compra lo que la tiene

Paquete `service/nso/` + `NsoAdminController` (`/api/admin/nso/**`). La lista NSO (~1,700 codigos de Aduanet) NO
esta en el repo: la admin la sube (xlsx/csv) desde `/admin/nso`. Es solo interno: el cliente nunca ve "NSO".

- **Tablas** (`Product` no cambia: los repricings hacen `productRepo.save(p)` y pisarian columnas NSO):
  `nso_records` (@Id = codigo `NSOC12345-26PE`; upsert que nunca borra), `product_nso` (@Id = productId; estado
  `CON_NSO | EN_REVISION | MARCA_CON_NSO | SIN_NSO | SIN_VERIFICAR`, sin fila = SIN_VERIFICAR; `locked` = decision
  de la admin), `nso_candidates` (cola propia, NO `match_candidates`), `nso_aliases`, `nso_events` (auditoria).
  Config `nso_gate_enabled`, `nso_accept_can_codes`, `nso_review_min_score`, `nso_catalog_version`.
- **NsoGate** (cache 30 s, `invalidate()` tras cada cambio): gate efectivo = flag `nso_gate_enabled` **y** catalogo
  con codigos activos. **Nace apagado y apagado todo es identico a antes** (`isPurchasable` true, `filterPublic`
  misma lista; los tests viejos dependen de eso). Activo: publico = `!archived && available && CON_NSO` (solo PE si
  `nso_accept_can_codes=false`). Se cambia solo por `PUT /api/admin/nso/gate`; `nso_accept_can_codes` y
  `nso_review_min_score` (0.5..0.95) por `PUT /api/admin/nso/settings` (evento + re-verificacion en segundo plano).
  `PUT /api/admin/config/{key}` rechaza `nso_*`. `NsoBlockedException` -> 400 `{message, unavailableProductIds}`, mensaje sin la palabra NSO.
- **NsoService**: unico que escribe product_nso/candidatos/alias/eventos, siempre via `write()` (un `ReentrantLock`
  envuelve la transaccion). Subir la lista dispara el rematch masivo `@Async` tras el commit (bloques de 300,
  progreso en `GET /summary`). **No inyectarlo en nada de lo que depende** (repos, `NsoGate`, parser): ciclo de beans.
  Hooks desde transacciones ajenas (merge, borrar, editar producto/GTIN): `nsoService.runAfterCommit(...)`
  (REQUIRES_NEW + lock, loguea y traga errores); nunca `write()` a mano dentro de `afterCommit` (se pierde).
  Excepcion: `ExcelImportService.commit` llama `rematchProducts` sincrono DENTRO de su transaccion (necesita los
  conteos del resumen; si NSO falla se revierte el import entero).
- **Matcher** (`NsoNormalizer` + `NsoBrandDictionary` + `NsoMatcher`, puros): decision bloqueada -> GTIN = EAN del
  registro -> alias positivos -> nombre dentro del grupo de marca. **Automatico solo con identidad exacta**: ningun
  token sobrante y ningun conflicto (concentracion, genero incl. unisex vs hombre/mujer, set/forma, nombre cortado
  por aduanas, existe version hombre y mujer). Antonimos (king/queen) y registros genericos nunca coinciden. Lo
  dudoso -> EN_REVISION con <=3 candidatos (el score solo ordena, nunca decide); la marca sin el perfume ->
  MARCA_CON_NSO. La hoja de investigacion (origin RESEARCH) solo propone, jamas asigna. El `Index` necesita
  `.siblings(...)` de todos los productos o no corren las reglas hombre/mujer.
- **Alias aprendidos**: aprobar guarda GTIN + SKU del proveedor + NAME_KEY (`marca|core|conc|genero|forma`, sin ml
  ni tester) -> cubre otros tamanos y proveedores; rechazar guarda alias NEGATIVE y no se vuelve a proponer.
  `BRAND` = "esta marca es la misma que".
- **Donde filtra** (todo chequea `isActive()` primero; con JWT el admin ve todo): tienda anonima
  (`ProductController` getAll/suggest/related/getById 404, `RecommendationService`, promos activas/detalle, banners
  de `/api/config/public`, `/api/retail/stock`); pedidos (`createOrder`; `editOrderByClient` deja mantener o bajar,
  no subir ni agregar); compras (`AllocationService`: la demanda bloqueada sale ANTES del optimizador a `nsoBlocked`,
  relleno de tienda, margin report, confirm 400 si el plan es viejo; `ExcelFillController`; faltantes; compra de
  tienda; `/retail/launch`); Apify missing/photos; dashboard; import (preview por fila + rematch al publicar).
  **No se bloquea a proposito**: ventas admin (`/retail/sales`, form-sale) ni `enableMerchandise`.
- **Tests**: `NsoGateIntegrationTest` y `NsoWiring*Test` (catalogo sintetico; tras escribir `product_nso` a mano,
  `gate.invalidate()`). `NsoCalibrationRealFilesTest` usa los archivos reales (lista NSO + Zimaxx/Oasis/
  FragranceSense; rutas por `-Dnso.catalog=`/`NSO_CATALOG`, etc.) y **se salta si faltan**; escribe
  `target/nso-auto-pairs.csv` para revisar a mano. Falso positivo = regla + test con esas palabras, no bajar umbral.
  `NsoPostgresModeTest` (H2 en modo PostgreSQL) ejercita todas las consultas NSO + un flujo completo y mide
  rendimiento con el tamano real (lineas `[NSO-PERF]`; afirma cantidad de sentencias, no tiempos).

## Convenciones importantes

### Migraciones de datos = metodos "once" en DataSeederService

Como no hay herramienta de migraciones, `DataSeederService` (`CommandLineRunner`) corre en cada arranque y cada
migracion se protege con una clave-bandera en `app_config` (`gtin_canonical_v1`, `prices_normalized_v3`,
`dedup_scan_v1`, `price_usd_resync_v1`, `supplier_constraints_v1`). **Al agregar una migracion nueva, seguir ese
patron**: chequear la bandera al entrar, escribirla al salir, y versionar la clave (`_v2`, `_v3`) si hay que
volver a correrla. El seed de productos/proveedores solo actua si no existen datos.

### Seguridad

- Publico: `POST /api/auth/login`, `GET /api/products/**`, `GET /api/consolidados/active|current`,
  `GET /api/media/*`, `POST /api/orders`, `GET /api/orders/code/**`, `PUT /api/orders/edit-by-client`,
  `GET /api/config/public`, `GET /api/retail/stock`, `POST /api/retail/form-sale`, `GET /api/promotions/active|{id}`
  (este ultimo cae en el `anyRequest().permitAll()` final). **Lo publico no expone datos internos**:
  `/consolidados/active` devuelve `ConsolidadoActiveDTO` (id, estado, fechas; sin pedidos ni costos) y
  `Consolidado.orders` es `@JsonIgnore`; las promos publicas salen sin `profitPen` (solo `/api/admin/promotions`).
- Al arrancar contra una BD que no es H2, `DefaultSecretsWarning` deja un WARN `[SEGURIDAD]` si `JWT_SECRET` es el de
  ejemplo o las cuentas admin/socio siguen con su clave de ejemplo (solo avisa: nunca impide arrancar).
- Autenticado (JWT): **todo el resto de `/api/consolidados/**`** (trae ganancias y datos de clientes),
  `/api/admin/**`, cualquier PUT/DELETE bajo `/api/**`, `POST /api/products/**`, `GET /api/orders` y
  `/api/orders/{id}`, `/api/retail/inventory|sales` (cualquier metodo). Sin token responde 403 (no 401).
- CORS: `localhost:*`, `fily16.github.io`, `*.vercel.app`, `aromastudiope.com` (con y sin www).

### Integraciones externas

- **Resend (HTTP)** para correo: Render bloquea el SMTP saliente, asi que si `RESEND_API_KEY` esta puesta se usa
  Resend y el SMTP queda como respaldo local. Diagnostico en `GET /api/admin/mail-test`.
- **Google Apps Script**: proxy en `/api/admin/google-proxy` para evitar CORS con Sheets; el Sheet es fuente de
  verdad para las ventas por formulario (`adjustStockToMatch`).
- **Form Sale API**: `POST /api/retail/form-sale` publico, protegido por API key generada en el seed.
- **KeepAliveService**: ping periodico para que el hosting gratuito no duerma el servicio.
- `H2ToPostgresMigration`: migracion de **un solo uso**, apagada por defecto (`app.migrate.h2=true`). No corre en
  despliegues normales y al activarse **salta el seed entero**. Su `TABLES` es una lista fija en orden de FK:
  **cada entidad nueva se agrega ahi** o sus datos no se copian.

### Higiene del repo

La raiz contiene material de trabajo local (Excels de proveedores en `provedores/`, scrapers en `tools/`,
capturas, datasets de Apify). Todo esta en `.gitignore`, pero **revisar `git status` sin filtros antes de
commitear**: el staging puede arrastrar listas de precios de proveedores. Commits en espanol, sin acentos.

## Frontend

App Angular en `Negocios/PERFUMES/paginaweb` (repo AromaStudio). `aromastudiope.com` se despliega solo en Vercel
al pushear a `master`; el `npm run deploy` a GitHub Pages es legacy.
