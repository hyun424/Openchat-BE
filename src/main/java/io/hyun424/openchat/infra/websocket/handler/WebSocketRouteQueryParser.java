package io.hyun424.openchat.infra.websocket.handler;

import org.springframework.web.socket.WebSocketSession;

class WebSocketRouteQueryParser {

    Long roomId(WebSocketSession session) {
        String query = query(session);
        if (query == null) {
            throw new IllegalStateException("Missing query");
        }

        String value = findQueryValue(query, "roomId");
        if (value == null) {
            throw new IllegalStateException("Missing roomId");
        }
        return Long.parseLong(value);
    }

    Integer partitionId(WebSocketSession session) {
        String query = query(session);
        if (query == null) {
            return null;
        }
        String value = findQueryValue(query, "partitionId");
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String query(WebSocketSession session) {
        return session.getUri() != null ? session.getUri().getQuery() : null;
    }

    private String findQueryValue(String query, String key) {
        for (String kv : query.split("&")) {
            String[] parts = kv.split("=");
            if (parts.length == 2 && parts[0].equals(key)) {
                return parts[1];
            }
        }
        return null;
    }
}
