import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lightweight HTTP server that exposes the Rubik's Cube solver as a REST API
 * and serves the Three.js web front-end from the web/ directory.
 *
 * Endpoints:
 *   GET  /              → serves web/index.html
 *   GET  /style.css     → serves web/style.css
 *   GET  /app.js        → serves web/app.js
 *   GET  /state         → {"state":[...54 ints...]}
 *   GET  /movetables    → {"perms":[[...54 ints...] × 18]}
 *   POST /scramble?n=8  → {"moves":[...],"state":[...]}
 *   POST /solve         → {"solution":[...],"state":[...]}   (solution may be null)
 *   POST /reset         → {"state":[...]}
 *
 * Run:  java CubeServer          (listens on http://localhost:8080)
 */
public class CubeServer {

    private static final int PORT = 8081;

    private static Cube cube = new Cube();
    private static List<Cube.Move> lastScramble = new ArrayList<>();
    private static PatternDatabase pdb;

    // ---------------------------------------------------------------
    // main
    // ---------------------------------------------------------------
    public static void main(String[] args) throws IOException {
        System.out.println("Initializing Server on port 8081...");
        pdb = new PatternDatabase();
        
        // Build PDB in the background so the server can start serving the UI immediately
        new Thread(() -> {
            pdb.loadOrBuild();
            System.out.println("Pattern Database is ready for IDA* solver.");
        }).start();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/",           CubeServer::handleStatic);
        server.createContext("/state",      CubeServer::handleState);
        server.createContext("/movetables", CubeServer::handleMoveTables);
        server.createContext("/scramble",   CubeServer::handleScramble);
        server.createContext("/solve",      CubeServer::handleSolve);
        server.createContext("/reset",      CubeServer::handleReset);
        server.createContext("/move",       CubeServer::handleMove);
        server.createContext("/custom",     CubeServer::handleCustom);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("==============================================");
        System.out.println("  Rubik's Cube 3D Solver Server");
        System.out.println("  Open: http://localhost:" + PORT);
        System.out.println("==============================================");
    }

    // ---------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        // Only serve known safe files
        Path filePath = Path.of("web" + path);
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            send(ex, 404, "text/plain", "Not found: " + path);
            return;
        }

        String mime = switch (path.substring(path.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css"  -> "text/css; charset=utf-8";
            case "js"   -> "application/javascript; charset=utf-8";
            default     -> "application/octet-stream";
        };

        byte[] body = Files.readAllBytes(filePath);
        addCors(ex);
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static void handleState(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        sendJson(ex, 200, "{\"state\":" + stateJson(cube.state) + "}");
    }

    private static void handleMoveTables(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        int[][] perms = Cube.getMovePerm();
        StringBuilder sb = new StringBuilder("{\"perms\":[");
        for (int i = 0; i < perms.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            for (int j = 0; j < perms[i].length; j++) {
                if (j > 0) sb.append(',');
                sb.append(perms[i][j]);
            }
            sb.append(']');
        }
        sb.append("]}");
        sendJson(ex, 200, sb.toString());
    }

    private static void handleScramble(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        int n = queryInt(ex, "n", 8);
        n = Math.max(1, Math.min(n, 15)); // clamp 1..15

        List<Cube.Move> moves = generateScramble(n);
        lastScramble = moves;

        synchronized (cube) {
            cube.reset();
            cube.applyMoves(moves);
        }

        sendJson(ex, 200,
            "{\"moves\":" + movesJson(moves) + ",\"state\":" + stateJson(cube.state) + "}");
    }

    private static void handleSolve(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String algo = queryStr(ex, "algorithm", "bfs").trim().toLowerCase();
        int maxDepth = queryInt(ex, "depth", 5); // default BFS depth
        List<Cube.Move> solution;

        try {
            long t0 = System.currentTimeMillis();
            synchronized (cube) {
                if ("idastar".equals(algo)) {
                    if (!pdb.isReady()) {
                        sendJson(ex, 400, "{\"error\":\"Pattern Database is still building. Please wait a moment.\"}");
                        return;
                    }
                    System.out.println("Solving with Korf's IDA*...");
                    IDAStarSolver solver = new IDAStarSolver(pdb);
                    solution = solver.solve(cube);
                    if (solution == null) {
                        sendJson(ex, 400, "{\"error\":\"IDA* could not find a solution. The cube state may be invalid.\"}" );
                        return;
                    }
                } else {
                    System.out.println("Solving with Bidirectional BFS...");
                    // maxDepth=8 per side handles up to ~16-move scrambles optimally
                    solution = Solver.solve(cube, Math.max(maxDepth, 8));
                    if (solution == null) {
                        // Fallback: reverse the recorded scramble path
                        if (!lastScramble.isEmpty()) {
                            System.out.println("BFS exceeded depth limit. Using inverse-scramble fallback.");
                            solution = new ArrayList<>();
                            for (int i = lastScramble.size() - 1; i >= 0; i--) {
                                solution.add(Solver.inverseOf(lastScramble.get(i)));
                            }
                        } else {
                            sendJson(ex, 400, "{\"error\":\"BFS could not solve the cube (state may be too complex or invalid). Try IDA* instead.\"}" );
                            return;
                        }
                    }
                }
                cube.applyMoves(solution);
                lastScramble = new ArrayList<>(); // reset after successful solve
            }
            long ms = System.currentTimeMillis() - t0;
            System.out.println("Solved in " + ms + "ms using " + algo + ". Moves: " + solution.size());

            sendJson(ex, 200,
                "{\"solution\":" + movesJson(solution) + ",\"timeMs\":" + ms + ",\"algorithm\":\"" + algo + "\",\"state\":" + stateJson(cube.state) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, "{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Applies a single move INCREMENTALLY to the current cube state.
     * Used by the Manual Moves grid — does NOT reset the cube first.
     */
    private static void handleMove(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String token = queryStr(ex, "m", "").trim();
        Cube.Move m = parseMove(token);
        if (m == null) {
            sendJson(ex, 400, "{\"error\":\"Unknown move: " + token + "\"}");
            return;
        }

        synchronized (cube) {
            cube.applyMove(m);
            // Track manual moves so the BFS fallback (inverse scramble) is always correct
            lastScramble = new ArrayList<>(lastScramble);
            lastScramble.add(m);
        }

        sendJson(ex, 200, "{\"state\":" + stateJson(cube.state) + "}");
    }

    private static void handleCustom(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String raw = queryStr(ex, "moves", "").trim();
        List<Cube.Move> moves = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String token : raw.split("\\s+")) {
                Cube.Move m = parseMove(token.trim());
                if (m != null) moves.add(m);
            }
        }

        synchronized (cube) {
            cube.reset();
            cube.applyMoves(moves);
        }
        lastScramble = moves; // stored so Solve can fall back to inverse

        sendJson(ex, 200,
            "{\"moves\":" + movesJson(moves) + ",\"state\":" + stateJson(cube.state) + "}");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Parse a single move token in standard notation (R, R2, R', Ri) into a Move. */
    private static Cube.Move parseMove(String token) {
        if (token == null || token.isEmpty()) return null;
        // Normalise to uppercase, allow 'i' as alternate for prime
        token = token.toUpperCase().replace("I", "'");
        char face = token.charAt(0);
        String rest = token.length() > 1 ? token.substring(1) : "";
        // Enum group starts: U=0, D=3, F=6, R=9, L=12, B=15
        int base = switch (face) {
            case 'U' -> 0;  case 'D' -> 3;  case 'F' -> 6;
            case 'R' -> 9;  case 'L' -> 12; case 'B' -> 15;
            default  -> -1;
        };
        if (base < 0) return null;
        int mag = rest.equals("'") ? 2 : rest.equals("2") ? 1 : 0;
        return Cube.Move.values()[base + mag];
    }

    private static void handleReset(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        synchronized (cube) { cube.reset(); }
        lastScramble = Collections.emptyList();
        sendJson(ex, 200, "{\"state\":" + stateJson(cube.state) + "}");
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    private static List<Cube.Move> generateScramble(int n) {
        Random rand = new Random();
        Cube.Move[] all = Cube.Move.values();
        List<Cube.Move> moves = new ArrayList<>(n);
        int prevFace = -1;
        for (int i = 0; i < n; i++) {
            Cube.Move m;
            int face;
            do {
                m = all[rand.nextInt(all.length)];
                face = m.ordinal() / 3;
            } while (face == prevFace);
            moves.add(m);
            prevFace = face;
        }
        return moves;
    }

    private static List<Cube.Move> invertScramble(List<Cube.Move> scramble) {
        List<Cube.Move> inv = new ArrayList<>(scramble.size());
        for (int i = scramble.size() - 1; i >= 0; i--) inv.add(Solver.inverseOf(scramble.get(i)));
        return inv;
    }

    private static String stateJson(byte[] state) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < state.length; i++) { if (i > 0) sb.append(','); sb.append(state[i]); }
        return sb.append(']').toString();
    }

    private static String movesJson(List<Cube.Move> moves) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < moves.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(moves.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static String queryStr(HttpExchange ex, String name, String def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) { return kv[1]; }
            }
        }
        return def;
    }

    private static int queryInt(HttpExchange ex, String name, int def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignore) {}
            }
        }
        return def;
    }

    private static void addCors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin",  "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static void send(HttpExchange ex, int code, String mime, String body) throws IOException {
        byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }
}
