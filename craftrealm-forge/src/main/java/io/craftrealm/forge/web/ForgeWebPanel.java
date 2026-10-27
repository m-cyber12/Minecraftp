package io.craftrealm.forge.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.craftrealm.forge.storage.ForgeDatabase;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Token-protected, read-only HTTP dashboard. Database waits occur only on HTTP workers. */
public final class ForgeWebPanel implements AutoCloseable {
    private static final String DASHBOARD = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>CraftRealm</title><style>
            body{margin:0;background:#151a18;color:#e9e8dc;font:16px system-ui;max-width:900px;padding:28px;margin:auto}
            h1{color:#b6d493}section{background:#222a26;padding:18px;border-radius:10px;margin:16px 0}
            input,button{background:#151a18;color:#eee;border:1px solid #52624e;padding:10px;border-radius:6px}
            button{cursor:pointer}pre{white-space:pre-wrap;color:#c8d7bc}
            </style><h1>CraftRealm dashboard</h1><section><label>Access token <input id="token" type="password" autocomplete="current-password"></label>
            <button onclick="loadData()">Connect</button><p id="message">Enter the web token configured by the server operator.</p></section>
            <section><h2>Server</h2><pre id="status">—</pre></section><section><h2>Market</h2><pre id="market">—</pre></section>
            <section><h2>Top balances</h2><pre id="top">—</pre></section><script>
            async function api(path,t){let r=await fetch(path,{headers:{Authorization:'Bearer '+t}});if(!r.ok)throw Error('HTTP '+r.status);return r.json()}
            async function loadData(){let t=document.querySelector('#token').value;try{
            let [s,m,b]=await Promise.all([api('/api/status',t),api('/api/market',t),api('/api/top',t)]);
            document.querySelector('#status').textContent=JSON.stringify(s,null,2);document.querySelector('#market').textContent=JSON.stringify(m,null,2);
            document.querySelector('#top').textContent=JSON.stringify(b,null,2);document.querySelector('#message').textContent='Connected.';
            }catch(e){document.querySelector('#message').textContent='Could not connect: '+e.message}}
            </script></html>
            """;

    private final Supplier<ForgeDatabase> database;
    private final String token;
    private final Logger logger;
    private final AtomicInteger online = new AtomicInteger();
    private volatile String serverName = "Minecraft server";
    private volatile HttpServer http;
    private volatile ExecutorService executor;

    public ForgeWebPanel(Supplier<ForgeDatabase> database, String token, Logger logger) {
        this.database = database;
        this.token = token == null ? "" : token;
        this.logger = logger;
    }

    public boolean start(String host, int port) throws IOException {
        if (token.length() < 32) {
            logger.warn("Web dashboard not started: configure a random web token of at least 32 characters.");
            return false;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(host, port), 16);
        ExecutorService workers = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "CraftRealm-Web");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(workers);
        created.createContext("/", this::handle);
        this.executor = workers;
        this.http = created;
        created.start();
        logger.info("CraftRealm read-only dashboard listening at http://{}:{}", host, port);
        return true;
    }

    public void updateStatus(String name, int playerCount) {
        serverName = name == null ? "Minecraft server" : name;
        online.set(Math.max(0, playerCount));
    }

    public boolean running() {
        return http != null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
            send(exchange, 200, "text/html; charset=utf-8", DASHBOARD);
            return;
        }
        if (!path.startsWith("/api/")) {
            send(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"not found\"}");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method not allowed\"}");
            return;
        }
        if (!authorized(exchange.getRequestHeaders())) {
            send(exchange, 401, "application/json; charset=utf-8", "{\"error\":\"unauthorized\"}");
            return;
        }
        try {
            String json = switch (path) {
                case "/api/status" -> "{\"server\":" + quote(serverName) + ",\"online\":" + online.get() + "}";
                case "/api/market" -> marketJson();
                case "/api/top" -> topJson();
                default -> "{\"error\":\"not found\"}";
            };
            send(exchange, path.equals("/api/status") || path.equals("/api/market") || path.equals("/api/top")
                    ? 200 : 404, "application/json; charset=utf-8", json);
        } catch (Exception failure) {
            logger.warn("Web dashboard request failed: {}", path, failure);
            send(exchange, 503, "application/json; charset=utf-8", "{\"error\":\"temporarily unavailable\"}");
        }
    }

    private String marketJson() throws Exception {
        ForgeDatabase store = requireDatabase();
        List<ForgeDatabase.MarketQuote> items = store.marketQuotes().get(5, TimeUnit.SECONDS);
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < items.size(); index++) {
            ForgeDatabase.MarketQuote item = items.get(index);
            if (index > 0) json.append(',');
            json.append("{\"item\":").append(quote(item.itemId()))
                    .append(",\"priceCents\":").append(item.priceCents())
                    .append(",\"basePriceCents\":").append(item.basePriceCents())
                    .append(",\"tradedUnits\":").append(item.tradedUnits()).append('}');
        }
        return json.append(']').toString();
    }

    private String topJson() throws Exception {
        List<ForgeDatabase.AccountBalance> accounts = requireDatabase().top(10).get(5, TimeUnit.SECONDS);
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < accounts.size(); index++) {
            ForgeDatabase.AccountBalance account = accounts.get(index);
            if (index > 0) json.append(',');
            json.append("{\"name\":").append(quote(account.name()))
                    .append(",\"balanceCents\":").append(account.cents()).append('}');
        }
        return json.append(']').toString();
    }

    private ForgeDatabase requireDatabase() {
        ForgeDatabase store = database.get();
        if (store == null) throw new IllegalStateException("database is offline");
        return store;
    }

    private boolean authorized(Headers headers) {
        String header = headers.getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] presented = header.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        HttpServer current = http;
        http = null;
        if (current != null) current.stop(1);
        ExecutorService workers = executor;
        executor = null;
        if (workers != null) workers.shutdownNow();
    }
}
