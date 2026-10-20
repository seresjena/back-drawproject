package app.arquidraw.backend.config;

import app.arquidraw.backend.web.DiagramWsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Expone el editor en vivo por WebSocket en /ws.
 * Single-user: se permiten todos los origenes (endurecer al subir a la nube).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DiagramWsHandler handler;

    public WebSocketConfig(DiagramWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
    }

    /**
     * El diagrama completo viaja como un unico mensaje de texto; el buffer por
     * defecto de Tomcat (8 KB) es demasiado pequeno y cierra la conexion con 1009.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(8 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
