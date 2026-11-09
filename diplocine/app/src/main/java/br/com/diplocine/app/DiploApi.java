package br.com.diplocine.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class DiploApi {
    static final String PAIR = "/api/v1/tv/pairings/";
    static final String ACCOUNT = "/api/v1/account/";
    static final String TMDB = "/api/v1/tmdb/";
    final String origin;
    private final String token;

    DiploApi(String origin, String token) {
        this.origin = origin;
        this.token = token;
    }

    static String origin(String input) {
        URI uri = URI.create(input.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Informe a origem HTTPS do seu site DiploCine.");
        }
        return "https://" + uri.getRawAuthority();
    }

    static boolean secureUrl(String input) {
        try {
            URI uri = URI.create(input);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    JSONObject get(String path) throws Exception {
        return (JSONObject) request("GET", path, null);
    }

    JSONArray channels() throws Exception {
        return (JSONArray) request("GET", "/channels.json", null);
    }

    JSONObject post(String path, JSONObject body) throws Exception {
        return (JSONObject) request("POST", path, body);
    }

    interface StateEdit { boolean apply(JSONObject data) throws Exception; }

    JSONObject updateState(StateEdit edit) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject latest = get("/auth/state");
            JSONObject data = latest.getJSONObject("data");
            if (!edit.apply(data)) return latest;
            try {
                JSONObject result = post("/auth/sync", object("revision", latest.getInt("revision"), "data", data));
                latest.put("revision", result.getInt("revision"));
                return latest;
            } catch (Failure conflict) {
                if (conflict.status != 409 || attempt == 1) throw conflict;
            }
        }
        throw new IllegalStateException("Não foi possível sincronizar.");
    }

    void delete(String path) throws Exception {
        request("DELETE", path, null);
    }

    private Object request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection http = (HttpURLConnection) URI.create(origin + path).toURL().openConnection();
        http.setInstanceFollowRedirects(false);
        http.setRequestMethod(method);
        http.setConnectTimeout(8_000);
        http.setReadTimeout(18_000);
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("User-Agent", "DiploCineNative/" + BuildConfig.VERSION_NAME);
        http.setRequestProperty("X-DiploCine-Request", "1");
        if (!token.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (var output = http.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = http.getResponseCode();
            if (status == 204) return new JSONObject();
            if (status < 200 || status >= 300) {
                String message = "O servidor retornou HTTP " + status + ".";
                try {
                    JSONObject error = new JSONObject(read(http.getErrorStream()));
                    message = error.optString("error", message);
                } catch (JSONException | IOException ignored) { }
                throw new Failure(status, message);
            }
            return new JSONTokener(read(http.getInputStream())).nextValue();
        } finally {
            http.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (input; var buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            for (int size; (size = input.read(bytes)) != -1;) {
                if (buffer.size() + size > 4 * 1024 * 1024) {
                    throw new IOException("Resposta maior que o limite aceito.");
                }
                buffer.write(bytes, 0, size);
            }
            return buffer.toString("UTF-8");
        }
    }

    static JSONObject object(Object... entries) {
        JSONObject json = new JSONObject();
        try {
            for (int i = 0; i < entries.length; i += 2) {
                json.put((String) entries[i], entries[i + 1]);
            }
            return json;
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }

    static String param(String value) {
        return Uri.encode(value);
    }

    static final class Failure extends IOException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
