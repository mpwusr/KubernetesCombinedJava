import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.File;
import java.io.IOException;   // <‑‑ ADDED
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Unified Kubernetes client (Deployment + StatefulSet) supporting create, delete, scale.
 *
 * ┌─────────────────────────────┐
 * │  CONFIGURATION PRIORITY     │
 * ├─────────────────────────────┤
 * │ 1. ENV vars (dotenv/.env)   │
 * │ 2. CONFIG_JSON file         │
 * │ 3. Hard‑coded defaults      │
 * └─────────────────────────────┘
 *
 * Required libs (Gradle):
 *   implementation "com.squareup.okhttp3:okhttp:4.12.0"
 *   implementation "io.github.cdimascio:dotenv-java:3.0.0"
 *   implementation "com.fasterxml.jackson.core:jackson-databind:2.17.0"
 *   implementation "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0"
 */
public class KubernetesCombinedJava {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) throws Exception {
        /*─────────────────────── 1. Load configuration ───────────────────────*/
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        Map<String, Object> jsonCfg = loadJsonConfig(System.getenv("CONFIG_JSON"));

        String api       = getCfg(jsonCfg, dotenv, "K8S_API",       "https://127.0.0.1:6443");
        String ns        = getCfg(jsonCfg, dotenv, "NAMESPACE",     "default");
        String mode      = getCfg(jsonCfg, dotenv, "MODE",          "").toLowerCase();
        String name      = getCfg(jsonCfg, dotenv, "RESOURCE_NAME", null);
        String fileUri   = getCfg(jsonCfg, dotenv, "RESOURCE_URI",  null);
        String scaleCnt  = getCfg(jsonCfg, dotenv, "SCALE_COUNT",   "1");
        String token     = getCfg(jsonCfg, dotenv, "BEARER_TOKEN",  null);

        if (token == null || token.length() < 10) {
            System.err.println("❌ Valid BEARER_TOKEN required");
            return;
        }

        /*─────────────────────── 2. OkHttp client ───────────────────────────*/
        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(insecureSocketFactory(), insecureTrust())
                .hostnameVerifier((h, s) -> true)
                .build();

        /*─────────────────────── 3. Switch by MODE ───────────────────────────*/
        switch (mode) {
            case "create" -> handleCreate(api, ns, fileUri, token, client);
            case "delete" -> handleDeleteOrScale(api, ns, name, token, client, false, 0);
            case "scale"  -> handleDeleteOrScale(api, ns, name, token, client, true, Integer.parseInt(scaleCnt));
            default       -> System.out.println("Invalid MODE. Use MODE=create|delete|scale");
        }
    }

    /*───────────────────────────── HELPERS ─────────────────────────────*/
    private static void handleCreate(String api, String ns, String uri, String token, OkHttpClient client) throws Exception {
        if (uri == null) { System.out.println("RESOURCE_URI must be set for create"); return; }
        String yaml = uri.startsWith("http") ? new String(new URL(uri).openStream().readAllBytes())
                : Files.readString(Paths.get(uri));
        Map<String, Object> parsed = YAML.readValue(yaml, Map.class);
        String kind = parsed.getOrDefault("kind", "").toString();
        if (!(kind.equals("Deployment") || kind.equals("StatefulSet"))) {
            System.out.println("❌ Only Deployment or StatefulSet supported. Found kind=" + kind);
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
            System.out.println(resp.isSuccessful() ? "✅ Created " + kind : "❌ Create failed: " + resp.code() + " - " + resp.body().string());
        }
    }

    private static void handleDeleteOrScale(String api, String ns, String name, String token, OkHttpClient client, boolean scale, int replicas) throws IOException {
        if (name == null) { System.out.println("RESOURCE_NAME must be set for delete/scale"); return; }
        String[] kinds = {"deployments", "statefulsets"};
        for (String endpoint : kinds) {
            String url = api + "/apis/apps/v1/namespaces/" + ns + "/" + endpoint + "/" + name;
            Request req;
            if (scale) {
                String patch = "{\"spec\":{\"replicas\":" + replicas + "}}";
                req = new Request.Builder().url(url).patch(RequestBody.create(patch, MediaType.parse("application/strategic-merge-patch+json")))
                        .header("Authorization", "Bearer " + token).build();
            } else {
                req = new Request.Builder().url(url).delete().header("Authorization", "Bearer " + token).build();
            }
            try (Response resp = client.newCall(req).execute()) {
                if (resp.code() == 404) continue; // try the other kind
                String action = scale ? "Scale" : "Delete";
                System.out.println(resp.isSuccessful() ? "✅ " + action + " succeeded on " + endpoint.replace("s", "")
                        : "❌ " + action + " failed: " + resp.code() + " - " + resp.body().string());
                return;
            }
        }
        System.out.println("❌ Resource not found as Deployment or StatefulSet");
    }

    private static Map<String, Object> loadJsonConfig(String path) throws Exception {
        return (path != null && Files.exists(Paths.get(path))) ? JSON.readValue(new File(path), Map.class) : null;
    }

    private static String getCfg(Map<String, Object> cfg, Dotenv env, String key, String def) {
        return cfg != null && cfg.containsKey(key) ? cfg.get(key).toString() : (env.get(key) != null ? env.get(key) : def);
    }

    /*──────────────────────── SSL Helpers (dev) ─────────────────────────*/
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
