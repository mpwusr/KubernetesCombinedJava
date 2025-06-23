import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.Map;

public class KubernetesCombinedJava {
    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String configPath = System.getenv("CONFIG_JSON");
        Map<String, Object> config = null;
        if (configPath != null && Files.exists(Paths.get(configPath))) {
            ObjectMapper mapper = new ObjectMapper();
            config = mapper.readValue(new File(configPath), Map.class);
        }

        String api = getValue(config, dotenv, "K8S_API", "https://127.0.0.1:6443");
        String namespace = getValue(config, dotenv, "NAMESPACE", "default");
        String mode = getValue(config, dotenv, "MODE", "").toLowerCase();
        String deploymentName = getValue(config, dotenv, "DEPLOYMENT_NAME", null);
        String deploymentFile = getValue(config, dotenv, "DEPLOYMENT_URI", null);
        String scaleCount = getValue(config, dotenv, "SCALE_COUNT", "1");
        String token = getValue(config, dotenv, "BEARER_TOKEN", null);

        if (token == null || token.trim().length() < 10) {
            System.out.println("No valid token provided. Exiting.");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(insecureSocketFactory(), insecureTrustManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();

        System.out.println("Mode: " + mode);
        System.out.println("Deployment: " + deploymentName);
        System.out.println("Deployment File/URI: " + deploymentFile);
        System.out.println("API: " + api);

        Request request;
        Response response;

        switch (mode) {
            case "delete":
                if (deploymentName == null) {
                    System.out.println("DEPLOYMENT_NAME must be set for delete");
                    return;
                }
                String deleteUrl = api + "/apis/apps/v1/namespaces/" + namespace + "/deployments/" + deploymentName;
                request = new Request.Builder()
                        .url(deleteUrl)
                        .delete()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .build();
                response = client.newCall(request).execute();
                System.out.println(response.isSuccessful() ? "Deleted" : "Delete failed: " + response.code() + " - " + response.body().string());
                break;

            case "create":
                if (deploymentFile == null) {
                    System.out.println("DEPLOYMENT_URI must be set for create");
                    return;
                }
                String createUrl = api + "/apis/apps/v1/namespaces/" + namespace + "/deployments";
                String yamlContent = deploymentFile.startsWith("http") ?
                        new String(new URL(deploymentFile).openStream().readAllBytes()) :
                        Files.readString(Paths.get(deploymentFile));
                Map<String, Object> parsedYaml = new ObjectMapper(new YAMLFactory()).readValue(yamlContent, Map.class);
                String jsonBody = new ObjectMapper().writeValueAsString(parsedYaml);
                RequestBody createBody = RequestBody.create(jsonBody, MediaType.parse("application/json"));
                request = new Request.Builder()
                        .url(createUrl)
                        .post(createBody)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .build();
                response = client.newCall(request).execute();
                System.out.println(response.isSuccessful() ? "Created" : "Create failed: " + response.code() + " - " + response.body().string());
                break;

            case "scale":
                if (deploymentName == null) {
                    System.out.println("DEPLOYMENT_NAME must be set for scale");
                    return;
                }
                String scaleUrl = api + "/apis/apps/v1/namespaces/" + namespace + "/deployments/" + deploymentName;
                String patchBody = "{\"spec\":{\"replicas\":" + scaleCount + "}}";
                RequestBody scaleRequestBody = RequestBody.create(patchBody, MediaType.parse("application/strategic-merge-patch+json"));
                request = new Request.Builder()
                        .url(scaleUrl)
                        .patch(scaleRequestBody)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/strategic-merge-patch+json")
                        .build();
                response = client.newCall(request).execute();
                System.out.println(response.isSuccessful() ? ("Scaled to " + scaleCount) : "Scale failed: " + response.code() + " - " + response.body().string());
                break;

            default:
                System.out.println("Invalid MODE. Use MODE=create, delete, or scale.");
        }
    }

    private static String getValue(Map<String, Object> config, Dotenv dotenv, String key, String defaultVal) {
        if (config != null && config.containsKey(key)) {
            return config.get(key).toString();
        }
        String env = dotenv.get(key);
        return env != null ? env : defaultVal;
    }

    private static SSLSocketFactory insecureSocketFactory() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{insecureTrustManager()}, new java.security.SecureRandom());
        return context.getSocketFactory();
    }

    private static X509TrustManager insecureTrustManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }
}
