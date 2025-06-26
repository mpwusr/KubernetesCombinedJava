
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.Map;

/** Unified Kubernetes client (Deployment + StatefulSet) supporting create, delete, scale. */
public class KubernetesCombinedJava {

    // ── Parsers ───────────────────────────────────────────────────────────────
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /*─────────────────────────── ENTRY ───────────────────────────*/
    public static void main(String[] args) throws Exception {

        /* .env beats real env on duplicates; ignoreIfMissing lets you run with only real env vars */
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        /* Optional JSON/YAML config file path – may come from .env or real env */
        Map<String, Object> fileCfg = loadJsonConfig(dotenv.get("CONFIG_JSON") != null
                ? dotenv.get("CONFIG_JSON")
                : System.getenv("CONFIG_JSON"));

        /* Pull settings (file → env) – REQUIRED fields throw if absent */
        String api     = requireCfg(fileCfg, dotenv, "K8S_API");
        String ns      = requireCfg(fileCfg, dotenv, "NAMESPACE");
        String mode    = requireCfg(fileCfg, dotenv, "MODE").toLowerCase();
        String token   = requireCfg(fileCfg, dotenv, "BEARER_TOKEN");

        /* These are only required for certain modes */
        String name    = cfg(fileCfg, dotenv, "RESOURCE_NAME");   // delete / scale
        String fileUri = cfg(fileCfg, dotenv, "RESOURCE_URI");    // create
        String scaleCt = cfg(fileCfg, dotenv, "SCALE_COUNT");     // scale

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(insecureSocketFactory(), insecureTrust())
                .hostnameVerifier((h, s) -> true)
                .build();

        switch (mode) {
            case "create" -> handleCreate(api, ns, fileUri, token, client);
            case "delete" -> handleDeleteOrScale(api, ns, name, token, client,
                    false, 0);
            case "scale"  -> {
                if (scaleCt == null) throw new IllegalStateException("SCALE_COUNT missing for scale mode");
                handleDeleteOrScale(api, ns, name, token, client,
                        true, Integer.parseInt(scaleCt));
            }
            default       -> System.out.println("Invalid MODE. Use MODE=create|delete|scale");
        }
    }

    /*──────────────────────── RESOURCE R/W ───────────────────────*/
    private static InputStream openResource(String location) throws IOException {
        try {                                         // try remote first
            URI uri = URI.create(location);
            if (uri.getScheme() != null && uri.getScheme().startsWith("http")) {
                return uri.toURL().openStream();
            }
        } catch (Exception ignored) { /* fall through */ }

        Path p = Paths.get(location);
        if (!Files.exists(p)) {
            throw new FileNotFoundException("Resource unreachable and no local file: " + location);
        }
        return Files.newInputStream(p);
    }

    /*──────────────────────── CREATE ─────────────────────────────*/
    private static void handleCreate(String api, String ns, String uri,
                                     String token, OkHttpClient client) throws Exception {

        if (uri == null) throw new IllegalStateException("RESOURCE_URI must be set for create mode");

        String yaml;
        try (InputStream in = openResource(uri)) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, Object> parsed = YAML.readValue(yaml, Map.class);
        String kind = parsed.getOrDefault("kind", "").toString();
        if (!kind.equals("Deployment") && !kind.equals("StatefulSet")) {
            System.out.println("Only Deployment or StatefulSet supported. Found kind=" + kind);
            return;
        }

        String endpoint = kind.equals("Deployment") ? "deployments" : "statefulsets";
        String url = api + "/apis/apps/v1/namespaces/" + ns + "/" + endpoint;
        String body = JSON.writeValueAsString(parsed);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .build();

        try (Response resp = client.newCall(req).execute()) {
            System.out.println(resp.isSuccessful()
                    ? "Created " + kind
                    : "Create failed: " + resp.code() + " - " + resp.body().string());
        }
    }

    /*──────────────────── DELETE & SCALE ─────────────────────────*/
    private static void handleDeleteOrScale(String api, String ns, String name,
                                            String token, OkHttpClient client,
                                            boolean scale, int replicas) throws IOException {

        if (name == null) throw new IllegalStateException("RESOURCE_NAME must be set for delete/scale mode");

        String[] kinds = {"deployments", "statefulsets"};
        for (String endpoint : kinds) {
            String url = api + "/apis/apps/v1/namespaces/" + ns + "/" + endpoint + "/" + name;
            Request req = scale
                    ? new Request.Builder().url(url)
                    .patch(RequestBody.create(
                            "{\"spec\":{\"replicas\":" + replicas + "}}",
                            MediaType.parse("application/strategic-merge-patch+json")))
                    .header("Authorization", "Bearer " + token).build()
                    : new Request.Builder().url(url).delete()
                    .header("Authorization", "Bearer " + token).build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.code() == 404) continue;              // try the other kind
                String action = scale ? "Scale" : "Delete";
                System.out.println(resp.isSuccessful()
                        ? action + " succeeded on " + endpoint.replace("s", "")
                        : action + " failed: " + resp.code() + " - " + resp.body().string());
                return;
            }
        }
        System.out.println("Resource not found as Deployment or StatefulSet");
    }

    /*──────────────────── CONFIG HELPERS ─────────────────────────*/
    /** Read a JSON or YAML file (HTTP or local). Returns null if path is null/blank. */
    private static Map<String, Object> loadJsonConfig(String path) throws Exception {
        if (path == null || path.isBlank()) return null;
        try (InputStream in = openResource(path)) {
            /* Allow either .json or .yaml/.yml */
            return (path.endsWith(".yaml") || path.endsWith(".yml"))
                    ? YAML.readValue(in, Map.class)
                    : JSON.readValue(in, Map.class);
        }
    }

    /** File-cfg → env-var → null. */
    private static String cfg(Map<String,Object> cfg, Dotenv env, String key) {
        return (cfg != null && cfg.containsKey(key)) ? cfg.get(key).toString()
                : env.get(key);
    }

    /** Same lookup order but throws if the key is missing/blank. */
    private static String requireCfg(Map<String,Object> cfg, Dotenv env, String key) {
        String val = cfg(cfg, env, key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required configuration " + key + " is missing");
        }
        return val;
    }

    /*──────────────────────── SSL (DEV-ONLY) ─────────────────────*/
    private static X509TrustManager insecureTrust() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }
    private static SSLSocketFactory insecureSocketFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{insecureTrust()}, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }
}
