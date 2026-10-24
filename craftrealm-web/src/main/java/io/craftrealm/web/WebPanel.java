package io.craftrealm.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The embedded dashboard.
 *
 * <p>Design decisions that matter for a Minecraft plugin:</p>
 * <ul>
 *   <li><b>Token auth, not a password store.</b> Operators paste a token from
 *       {@code config.yml}; the panel keeps only a hashed session id in memory and
 *       expires it after the configured number of minutes. There is no way to leak a
 *       password because there isn't one.</li>
 *   <li><b>Static assets from the jar.</b> The three files in {@code /web} are served
 *       by hand so no extra Servlet configuration can silently break the panel.</li>
 *   <li><b>Console is opt-in</b> and every call is audited by the plugin bridge.</li>
 * </ul>
 */
public final class WebPanel {

    private final WebBridge bridge;
    private final WebPanelSettings settings;
    private final java.util.function.Consumer<String> log;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private Javalin app;

    public WebPanel(WebBridge bridge, WebPanelSettings settings,
                    java.util.function.Consumer<String> log) {
        this.bridge = bridge;
        this.settings = settings;
        this.log = log == null ? message -> {
        } : log;
    }

    /** Starts Javalin; returns {@code false} when the panel is disabled or misconfigured. */
    public boolean start() {
        if (!settings.enabled()) {
            return false;
        }
        if (settings.tokens().isEmpty()) {
            log.accept("Web panel is enabled but web.tokens is empty — refusing to start.");
            return false;
        }
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jetty.defaultHost = settings.host();
        });
        registerRoutes();
        app.start(settings.host(), settings.port());
        log.accept("Web panel listening on " + settings.host() + ':' + settings.port());
        return true;
    }

    public void stop() {
        if (app != null) {
            app.stop();
            app = null;
        }
        sessions.clear();
    }

    public boolean running() {
        return app != null;
    }

    // ------------------------------------------------------------------ routes

    private void registerRoutes() {
        app.get("/", ctx -> serve(ctx, "index.html", "text/html; charset=utf-8"));
        app.get("/style.css", ctx -> serve(ctx, "style.css", "text/css; charset=utf-8"));
        app.get("/app.js", ctx -> serve(ctx, "app.js", "application/javascript; charset=utf-8"));

        app.post("/api/login", ctx -> {
            Map<?, ?> body = gson.fromJson(ctx.body(), Map.class);
            String token = body == null ? "" : String.valueOf(body.get("token"));
            if (!validToken(token)) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "invalid token"));
                return;
            }
            String session = newSession();
            ctx.json(Map.of("session", session, "expiresInMinutes", settings.sessionMinutes()));
        });

        app.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/login")) {
                return;
            }
            if (!authorised(ctx)) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "not authenticated"));
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/api/status", ctx -> json(ctx, bridge::status));
        app.get("/api/players", ctx -> json(ctx, bridge::players));
        app.get("/api/economy", ctx -> json(ctx, bridge::economy));
        app.get("/api/audit", ctx -> json(ctx, () -> bridge.audit(limit(ctx))));
        app.get("/api/reports", ctx -> json(ctx, bridge::reports));
        app.get("/api/market", ctx -> json(ctx, () -> bridge.market(limit(ctx))));
        app.get("/api/console", ctx -> json(ctx, () -> bridge.console(limit(ctx))));

        app.post("/api/console", ctx -> {
            if (!settings.allowConsole()) {
                ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", "console disabled"));
                return;
            }
            Map<?, ?> body = gson.fromJson(ctx.body(), Map.class);
            String command = body == null ? "" : String.valueOf(body.get("command"));
            boolean ok = !command.isBlank() && sanitise(command) && bridge.runCommand(command);
            ctx.json(Map.of("ok", ok));
        });

        app.post("/api/broadcast", ctx -> {
            Map<?, ?> body = gson.fromJson(ctx.body(), Map.class);
            String message = body == null ? "" : String.valueOf(body.get("message"));
            ctx.json(Map.of("ok", !message.isBlank() && bridge.broadcast(message)));
        });

        app.post("/api/punish", ctx -> {
            Map<?, ?> body = gson.fromJson(ctx.body(), Map.class);
            if (body == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "missing body"));
                return;
            }
            String target = String.valueOf(body.get("target"));
            String type = String.valueOf(body.get("type"));
            String reason = body.get("reason") == null ? "dashboard" : String.valueOf(body.get("reason"));
            long duration = body.get("durationMinutes") == null ? 0
                    : (long) Double.parseDouble(String.valueOf(body.get("durationMinutes"))) * 60_000L;
            ctx.json(Map.of("ok", bridge.punish(target, type, reason, duration)));
        });
    }

    // ----------------------------------------------------------------- helpers

    private <T> void json(Context ctx, Supplier<T> supplier) {
        try {
            ctx.contentType("application/json").result(gson.toJson(supplier.get()));
        } catch (RuntimeException exception) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .json(Map.of("error", String.valueOf(exception.getMessage())));
        }
    }

    private void serve(Context ctx, String resource, String contentType) {
        try (InputStream stream = getClass().getResourceAsStream("/web/" + resource)) {
            if (stream == null) {
                ctx.status(HttpStatus.NOT_FOUND).result("not found");
                return;
            }
            ctx.contentType(contentType)
                    .result(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("read error");
        }
    }

    private boolean validToken(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        for (String candidate : settings.tokens()) {
            if (MessageDigest.isEqual(presented, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private String newSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String session = HexFormat.of().formatHex(bytes);
        sessions.put(session, System.currentTimeMillis() + settings.sessionMinutes() * 60_000L);
        return session;
    }

    private boolean authorised(Context ctx) {
        String header = ctx.header("Authorization");
        String session = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (session == null || session.isBlank()) {
            return false;
        }
        Long expires = sessions.get(session);
        if (expires == null) {
            return false;
        }
        if (expires < System.currentTimeMillis()) {
            sessions.remove(session);
            return false;
        }
        return true;
    }

    private static int limit(Context ctx) {
        try {
            String rawLimit = ctx.queryParam("limit");
            return Math.max(1, Math.min(500, Integer.parseInt(rawLimit == null ? "50" : rawLimit)));
        } catch (NumberFormatException exception) {
            return 50;
        }
    }

    /** Console input must not contain newlines or NULs, and must not chain commands. */
    private static boolean sanitise(String command) {
        return !command.contains("\n") && !command.contains("\r") && !command.contains("\0")
                && !command.contains(";") && command.length() <= 512;
    }

    /** Session count, used by tests and the status endpoint of the plugin. */
    public int sessionCount() {
        return sessions.size();
    }

}
