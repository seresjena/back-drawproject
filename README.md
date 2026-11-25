# ArquiDraw Backend

Servidor para que **agentes de IA** lean y modifiquen el diagrama activo de ArquiDraw, con
**sincronización en vivo** hacia el editor (Angular) por WebSocket.

- **MCP** (Model Context Protocol) sobre **Streamable HTTP** en `POST /mcp`. Cualquier cliente
  compatible (Claude Desktop, Cursor, VS Code, etc.) descubre y ejecuta las operaciones.
- **WebSocket** en `/ws`: cada cambio (hecho por el agente por MCP o por el usuario en el editor)
  se difunde al instante a los demás clientes **de la misma sala**.
- **Salas (tenants) y roles**: el estado vive por sala. El creador y el agente usan el token de
  edición (`?sala=TOKEN`, también en el WS); el enlace compartido usa un token de espectador
  (`?ver=TOKEN_VISTA`) que solo permite leer. El editor registra ese token al conectar
  (`?sala=TOKEN&ver=TOKEN_VISTA`) y `RoomManager` lo resuelve a la misma sala. El backend rechaza
  `push`/`viewport` de un espectador. Sin token se usa la sala `DEFAULT`. El agente se conecta con
  el token de edición (`POST /mcp?sala=TOKEN` o `POST /mcp/TOKEN`). Todo en memoria (sin
  autenticación), pensado para uso local y configurable por variables de entorno para moverlo a la
  nube después sin tocar código.

## Requisitos

- **Java 21** (`java -version`).
- No hace falta Gradle instalado: el proyecto incluye el wrapper (`./gradlew`).

## Arrancar

```bash
cd backend

# Opción A: en caliente (desarrollo)
./gradlew bootRun

# Opción B: jar ejecutable
./gradlew bootJar
java -jar build/libs/arquidraw-backend-0.1.0.jar
```

Queda escuchando en **http://localhost:8080** (MCP en `/mcp`, WebSocket en `/ws`).
Cambiar el puerto: `PORT=9090 ./gradlew bootRun`.

## Conectar desde un cliente MCP

El endpoint es Streamable HTTP. Ejemplos de configuración:

**Claude Desktop / Cursor (vía puente `mcp-remote`):**

```json
{
  "mcpServers": {
    "arquidraw": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

**Cliente con soporte HTTP nativo:** apuntar la URL a `http://localhost:8080/mcp`
(protocolo MCP `2025-06-18`).

**Conectarse a la sesión del usuario (sala):** añadir el token de la sesión a la URL, p. ej.
`http://localhost:8080/mcp?sala=ABC123` (o `http://localhost:8080/mcp/ABC123`). El agente leerá y
editará el mismo diagrama que el usuario ve en su editor; los cambios se reflejan en vivo por
WebSocket. Sin token, opera sobre la sala `DEFAULT` (monousuario).

## Herramientas (tools)

| Área | Herramientas |
| --- | --- |
| Consultar | `get_diagram`, `get_meta`, `list_nodes`, `get_node`, `list_connections`, `list_presets` |
| Nodos | `add_node`, `update_node`, `update_nodes` (lote), `move_node`, `resize_node`, `duplicate_node`, `z_order_node`, `align_nodes`, `remove_node`, `remove_nodes` (lote) |
| Conexiones | `add_connection`, `update_connection`, `update_connections` (lote), `reconnect_connection`, `reverse_connection`, `remove_connection`, `remove_connections` (lote) |
| Diagrama | `rename_diagram`, `set_background`, `set_grid_size`, `auto_layout`, `clear_diagram`, `validate` |
| Lotes | `batch` (varias operaciones en orden, todo o nada) |
| Documentación | `get_node_doc`, `set_node_doc`, `append_node_doc`, `set_node_doc_url`, `remove_node_doc`, `get_doc`, `update_doc`, `patch_doc` |
| Niveles internos | `list_levels`, `get_level`, `create_level`, `remove_level`, `replace_level` |
| Respaldo | `snapshot`, `list_snapshots`, `restore`, `undo` |
| Ver / señalar | `focus_nodes` |
| Intercambio | `import_diagram`, `export_diagram` |

`auto_layout` ordena todo el diagrama en capas por niveles (clientes → servicios → datos) para
reducir los cruces de líneas; equivale al botón **Organizar** del editor. Son **51** herramientas.
Los niveles internos se direccionan con una `path` de ids de nodos. `get_level`/`replace_level` leen y
reemplazan un nivel; `add_node`, `update_node(s)`, `remove_node(s)`, `get_node`, las conexiones y
`focus_nodes` aceptan `path` para operar directamente dentro de un nivel. `create_level` genera
portales de entrada/salida a partir de las conexiones del nodo padre.

**Fase 2 (respuestas livianas).** Las mutaciones devuelven por defecto
`{ ok, id(s), revision, counts: { nodes, connections, levels }, warnings }`; con `verbose: true`
añaden el `diagram` completo. `counts` suma también los niveles internos. `list_nodes` acepta
`path`, `fields`, `includeDocs` (por defecto `false`) y `filter` (`labelContains`, `stroke`; los
filtros de metadatos llegan en la fase 6). `export_diagram` acepta `format` (`adraw`/`json`),
`includeDocs` (por defecto `true`) y `level` para exportar un solo nivel sin fichas.

**Fase 3 (consistencia y concurrencia).** Todas las mutaciones aceptan `expectedRevision`; si el
diagrama cambió desde esa revisión responden **409** con la revisión actual y no aplican el cambio.
El error MCP muestra el conflicto una sola vez y añade una pista para reintentar:

```text
Conflicto de revision (409): esperada 0, actual 1.
No se aplico ningun cambio; reintenta con get_meta.
```

Los niveles internos vacíos son válidos: `list_levels` los muestra con `nodeCount: 0` y
`replace_level` los acepta siempre. `validate({ fix? })` revisa conexiones/portales huérfanos,
niveles no referenciados, `childLevelId` sin nivel, nodos superpuestos e ids duplicados; con
`fix: true` elimina los huérfanos y corrige las referencias rotas sin tocar los solapes ni los ids
duplicados. Devuelve `{ ok, valid, fixedCount, issues, revision, counts }`.

**Fase 4 (lotes e importación parcial).** `batch({ operations, expectedRevision? })` aplica varias
operaciones en orden y **todo o nada** sobre una copia de trabajo: si una falla, el diagrama no
cambia. Cada operación usa los mismos parámetros que su tool (`add_node`, `update_node`,
`add_connection`, `remove_node`, `patch_doc`, etc.) y admite `path`; también se acepta
`{ tool, args: { ... } }`. El lote entero genera **una sola revisión** y un solo evento WebSocket.
La respuesta es `{ ok, revision, ids, results, counts, warnings }`: `ids` son los ids creados en
orden y `results` detalla cada operación. En `batch`, `add_node` y `add_connection` aceptan un `id`
explícito para poder referenciar lo creado en operaciones posteriores del mismo lote.

`import_diagram` mantiene el reemplazo completo por defecto; con `merge: true` hace upsert por id en
nodos, conexiones y niveles **sin borrar** lo que no venga en el JSON. Conserva nombre, fondo y
cuadrícula del diagrama actual y, en nodos existentes, su tamaño, nivel interno y portal si el
fragmento no los trae. La lectura del JSON tolera campos numéricos ausentes o nulos para admitir
fragmentos parciales.

**Fase 5 (respaldo y deshacer).** `snapshot({ label? })` guarda un punto de restauración y
`list_snapshots()` los lista (más reciente primero) con etiqueta, revisión, autor (`origin`),
`auto` y counts. `restore({ snapshotId })` vuelve exactamente a ese estado con una sola revisión y
evento; el propio restore se puede deshacer. `undo({ steps? = 1 })` deshace los últimos cambios
sobre una pila de estados previos y devuelve `undone` con revisión, origen y fecha. Hay snapshots
automáticos antes de `clear_diagram`, `import_diagram`, `replace_level` y lotes grandes (≥10
operaciones, operaciones destructivas o borrados masivos). Todo vive **en memoria por sala** y se
pierde al reiniciar el backend: historial de 50 estados, 20 snapshots manuales y 10 automáticos.
La primera carga que llena un diagrama vacío no se puede deshacer a la sala vacía.
`snapshot`/`restore`/`undo` no se aceptan dentro de `batch`.

Cada nodo puede llevar documentación híbrida: `docUrl` (enlace externo http/https) y `doc`
(Markdown interno, máximo 100 KB). El agente la gestiona con `get_node_doc`, `set_node_doc`,
`append_node_doc`, `set_node_doc_url` y `remove_node_doc`; para editar fichas de niveles internos
sin reemplazar el nivel usa `get_doc`, `update_doc` y `patch_doc` (por sección `## <section>`).
`add_node`, `update_node` y `update_nodes` también aceptan `doc` y `docUrl`, y rechazan campos
desconocidos. El editor la edita en Propiedades, la abre desde el menú contextual / modo trabajo y la
muestra en una página independiente
`/doc/{sala}/{nodeId}` con tablas, código y diagramas Mermaid. La API de solo lectura es
`GET /api/docs/{sala}/{nodeId}` (JSON) y `GET /api/docs/{sala}/{nodeId}/raw.md` (Markdown); acepta
tanto el token de edición como el de espectador (`RoomManager.forToken`).

`get_meta` devuelve además `contentBounds` (rectángulo que ocupan los nodos) y, si hay un editor
conectado, `viewport` (tamaño del lienzo en px, `zoom`, `panX`, `panY`) y `visibleWorld` (región del
mundo visible). Así un agente sabe **dónde colocar** el siguiente nodo para que se vea en pantalla.

Los nodos incluyen `technology` (tecnología principal) y `summary` (qué hace el componente).
`add_node`, `update_node` y `update_nodes` aceptan ambos parámetros y se devuelven en
`get_node`/`list_nodes`/`get_diagram`. `list_nodes` omite el Markdown por defecto para que la
consulta sea barata; pide `includeDocs: true` o `fields: ["doc"]` solo cuando lo necesites.

Cada mutación incrementa la `revision` del diagrama y se difunde por WebSocket. Las mutaciones
informan además los **ids que tocaron** (`highlight`) y `focus_nodes` señala ids sin cambiar
el diagrama: el editor los resalta un instante para que el usuario *vea* al agente trabajando.

## Recursos (resources)

| URI | Contenido |
| --- | --- |
| `arquidraw://schema` | JSON Schema del formato de proyecto (`arquidraw.schema.json`). |
| `arquidraw://diagram` | Diagrama activo serializado. |

## Sincronización en vivo (WebSocket `/ws`)

Mensajes JSON. Cliente → servidor:

```jsonc
{ "type": "push",    "origin": "<clientId>", "data": { /* diagrama */ } } // reemplaza el estado
{ "type": "request", "origin": "<clientId>" }                            // pide el estado actual
{ "type": "viewport", "origin": "<clientId>", "width": 1920, "height": 873, "zoom": 0.82, "panX": -8.2, "panY": -13.4 } // reporta el lienzo (para get_meta)
```

Servidor → clientes:

```jsonc
{ "type": "diagram",   "revision": 3, "origin": "mcp", "data": { /* diagrama */ }, "highlight": ["n-1a2b"] }
{ "type": "highlight", "origin": "mcp", "highlight": ["n-1a2b", "c-9f8e"] } // solo resaltar (focus_nodes)
{ "type": "error",     "message": "..." }
```

`origin` es `"mcp"` (cambio del agente), `"server"` o el `clientId` que lo originó; el emisor
ignora su propio eco. El editor Angular (`src/app/core/services/live-sync.service.ts`) se conecta
con el botón **en vivo** de la barra de herramientas: al abrir empuja su diagrama si tiene
contenido, o adopta el del servidor si está vacío; luego ambos sentidos quedan sincronizados.
Además envía `viewport` (con *debounce*) cuando cambian el tamaño del lienzo, el pan o el zoom,
para que `get_meta` informe la región visible.

## Estructura

```
src/main/java/app/arquidraw/backend/
  ArquiDrawBackendApplication.java      # arranque
  model/        PortRef, DiagramNode, DiagramConnection, DiagramData, Bounds, Viewport, NodePreset(s)
  service/      DiagramRepository, InMemoryDiagramRepository, DiagramService, DiagramMerger,
                DiagramValidator, DiagramChangedEvent
  web/          WsClientMessage, WsServerMessage, DiagramWsHandler, WebSocketConfig, WebConfig,
                DocController, NodeDocument
  mcp/          DiagramMcpTools, BatchOperations, DiagramJson, ArquiDrawResources
src/main/resources/
  application.properties                # puerto, MCP, log
  arquidraw.schema.json                 # contrato del formato de proyecto
```

## Configuración (variables de entorno)

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `PORT` | `8080` | Puerto HTTP. |
| `MCP_SERVER_NAME` | `arquidraw` | Nombre del servidor MCP. |
| `MCP_SERVER_VERSION` | `0.1.0` | Versión anunciada. |

## Stack

Java 21 · Spring Boot 4.1.1 · Spring AI 2.0.1 (MCP, Streamable HTTP) · Gradle (Groovy DSL).
