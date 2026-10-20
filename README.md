# ArquiDraw Backend

Servidor para que **agentes de IA** lean y modifiquen el diagrama activo de ArquiDraw, con
**sincronización en vivo** hacia el editor (Angular) por WebSocket.

- **MCP** (Model Context Protocol) sobre **Streamable HTTP** en `POST /mcp`. Cualquier cliente
  compatible (Claude Desktop, Cursor, VS Code, etc.) descubre y ejecuta las operaciones.
- **WebSocket** en `/ws`: cada cambio (hecho por el agente por MCP o por el usuario en el editor)
  se difunde al instante a los demás clientes **de la misma sala**.
- **Salas (tenants)**: el estado vive por sala. El token viaja en la URL del editor y del WS
  (`?sala=TOKEN`); sin token se usa la sala `DEFAULT`. El agente se conecta a la sala del usuario
  pasando ese token al endpoint MCP (`POST /mcp?sala=TOKEN` o `POST /mcp/TOKEN`), de modo que
  trabaja sobre **su** diagrama. Todo en memoria (sin autenticación), pensado para uso local y
  configurable por variables de entorno para moverlo a la nube después sin tocar código.

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
| Diagrama | `rename_diagram`, `set_background`, `set_grid_size`, `auto_layout`, `clear_diagram` |
| Ver / señalar | `focus_nodes` |
| Intercambio | `import_diagram`, `export_diagram` |

`auto_layout` ordena todo el diagrama en capas por niveles (clientes → servicios → datos) para
reducir los cruces de líneas; equivale al botón **Organizar** del editor. Son **31** herramientas.

`get_meta` devuelve además `contentBounds` (rectángulo que ocupan los nodos) y, si hay un editor
conectado, `viewport` (tamaño del lienzo en px, `zoom`, `panX`, `panY`) y `visibleWorld` (región del
mundo visible). Así un agente sabe **dónde colocar** el siguiente nodo para que se vea en pantalla.

Los nodos incluyen `technology` (tecnología principal) y `summary` (qué hace el componente).
`add_node`, `update_node` y `update_nodes` aceptan ambos parámetros y se devuelven en
`get_node`/`list_nodes`/`get_diagram`.

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
  service/      DiagramRepository, InMemoryDiagramRepository, DiagramService, DiagramChangedEvent
  web/          WsClientMessage, WsServerMessage, DiagramWsHandler, WebSocketConfig, WebConfig
  mcp/          DiagramMcpTools, ArquiDrawResources
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
