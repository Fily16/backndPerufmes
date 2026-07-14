# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Descripcion del Proyecto

Backend API para **AromaStudio** — negocio de perfumes arabes importados desde USA (Oriental Aromas) a Peru. Maneja dos canales de venta: **consolidado** (pedidos mayoristas agrupados en lotes de importacion) y **retail** (venta unitaria local con inventario propio). Los precios se manejan en USD (costo proveedor) y PEN (venta al cliente), con tipo de cambio configurable.

## Stack Tecnico

- Java 17, Spring Boot 3.4, Spring Security + JWT (jjwt 0.12.6)
- PostgreSQL (produccion via `DATABASE_URL`) / H2 (desarrollo local, archivo `./data/aromastudio`)
- Maven wrapper (`mvnw` / `mvnw.cmd`)
- Sin Lombok — todas las entidades usan getters/setters manuales

## Comandos

```bash
./mvnw spring-boot:run          # iniciar servidor (puerto 8080)
./mvnw test                     # ejecutar tests
./mvnw package -DskipTests      # generar JAR
```

La consola H2 esta disponible en `/h2-console` en desarrollo local.

## Arquitectura

### Modelo de Dominio

El flujo central es: **Producto -> Pedido (Order) -> Consolidado**

- **Product**: catalogo maestro con precios en USD (proveedor), PEN retail, PEN mayorista, y PEN mayor. SKU unico.
- **Consolidado**: lote de importacion. Estados: `ABIERTO` -> `CERRADO` -> `ENTREGADO`. Agrupa pedidos de clientes y compras propias para tienda.
- **Order**: pedido de un cliente dentro de un consolidado. Contiene `OrderItem`s. Estados de pago: `PENDIENTE_SEPARACION` -> `SEPARADO` -> `PENDIENTE_RESTO` -> `PAGADO` -> `VERIFICADO` (o `RECHAZADO`).
- **RetailInventory / RetailSale**: inventario y ventas del canal retail (tienda fisica/WhatsApp). El stock se alimenta automaticamente cuando un consolidado pasa a `ENTREGADO` (via `enableMerchandise`).

### Logica de Negocio Critica

- **PricingService**: centraliza TODOS los calculos de precios (reemplaza formulas de Excel). Calcula costo de envio por peso, costo puesto en Peru (landed cost), precio sugerido con margen, cajas necesarias, costo courier total, etc. Los parametros se leen de `AppConfig` en BD.
- **ConsolidadoService**: orquesta la creacion de pedidos, verificacion de pagos, cierre de consolidados y calculo de totales. Un pedido puede acumularse (agregar productos a un pedido existente usando `existingOrderCode`). Las ordenes con `clientName = "COMPRA TIENDA"` son compras internas.
- **DataSeederService**: `CommandLineRunner` que inicializa admin, configuracion, productos (desde `products-seed.json`), precios mayoristas/retail calculados, y el primer consolidado ABIERTO. Solo siembra si no existen datos.

### Seguridad

- Endpoints publicos: GET productos, GET consolidado activo, POST/PUT pedidos de cliente, GET pedido por codigo, GET stock retail, POST form-sale.
- Endpoints protegidos (JWT): todo bajo `/api/admin/**`, y todos los PUT/DELETE generales.
- CORS configurado para `localhost`, `fily16.github.io`, `*.vercel.app`, y `aromastudiope.com`.

### Integraciones Externas

- **Google Apps Script**: proxy en `/api/admin/google-proxy` para evitar CORS con Google Sheets.
- **Form Sale API**: endpoint publico `/api/retail/form-sale` protegido por API key (generada automaticamente en seed), usado por Google Forms para registrar ventas retail.
- **KeepAliveService**: tarea programada (`@EnableScheduling`) que hace ping periodico para mantener vivo el servicio en hosting gratuito.

### Configuracion Dinamica

Los parametros de negocio se almacenan en la tabla `app_config` (clave-valor) y se leen en runtime via `PricingService`. Incluyen: tipo de cambio, costo courier/kg, margen objetivo, deposito por unidad, numero de Yape, etc. Se pueden modificar desde `/api/admin/config/{key}`.

## Frontend

El frontend es la app Angular en `Negocios/PERFUMES/paginaweb` (AromaStudio), desplegada en GitHub Pages (`fily16.github.io/AromaStudio`) y en `aromastudiope.com`.
