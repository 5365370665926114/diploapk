package br.com.diplocine.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.integration.android.IntentIntegrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff050505;
    private static final int SURFACE = 0xff171719;
    private static final int MUTED = 0xffb4b4bd;
    private static final int RED = 0xffe50914;
    private static final String THEME_FIELD = "diploAccent";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private SharedPreferences prefs;
    private SessionStore sessionStore;
    private LinearLayout root, body;
    private Runnable backAction;
    private int page, accent = RED;
    private boolean tv, foreground;
    private String origin, token, profile = "", accountId = "";
    private JSONObject state = new JSONObject();
    private JSONArray channels;
    private Pairing pairing;
    private TextView countdown;
    private final Runnable poll = this::pollPairing;
    private final Runnable tick = this::tickPairing;

    private record Pairing(String code, String secret, long deadline, long interval) {}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("diplocine", MODE_PRIVATE);
        sessionStore = new SessionStore(prefs);
        origin = prefs.getString("origin", "");
        if (origin.isEmpty() && !BuildConfig.SITE_URL.trim().isEmpty()) {
            try { origin = DiploApi.origin(BuildConfig.SITE_URL); }
            catch (IllegalArgumentException ignored) { }
        }
        token = sessionStore.load();
        String mode = prefs.getString("mode", "");
        if (mode.isEmpty()) chooseMode();
        else {
            useMode(mode);
            start();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (pairing != null) schedulePairing(0);
    }

    @Override protected void onStop() {
        foreground = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    private boolean hardwareTv() {
        UiModeManager manager = getSystemService(UiModeManager.class);
        return getPackageManager().hasSystemFeature("android.software.leanback")
                || (manager != null && manager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION);
    }

    private void chooseMode() {
        tv = hardwareTv();
        screen("Como você vai assistir?", "Escolha o modo deste aparelho.", null);
        Button tvButton = button("Usar na TV", tv, () -> selectMode("tv"));
        Button phoneButton = button("Usar no celular", !tv, () -> selectMode("phone"));
        (tv ? tvButton : phoneButton).requestFocus();
    }

    private void selectMode(String mode) {
        prefs.edit().putString("mode", mode).apply();
        useMode(mode);
        start();
    }

    private void useMode(String mode) {
        tv = mode.equals("tv");
        if (!hardwareTv()) {
            setRequestedOrientation(tv ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void start() {
        if (origin.isEmpty()) {
            siteSetup(this::start, this::chooseMode);
        } else if (token.isEmpty()) {
            signIn();
        } else {
            screen("Só um instante", "Verificando sua sessão…", null);
            task(() -> api().get("/auth/state"), response -> {
                setState(response);
                showProfiles();
            }, failure -> {
                if (failure instanceof DiploApi.Failure f && f.status == 401) {
                    forgetSession();
                    signIn();
                } else {
                    errorPage("Não foi possível verificar sua conta", failure, this::start, this::signIn);
                }
            });
        }
    }

    private void siteSetup(Runnable next, Runnable back) {
        screen("Conectar ao seu site", "Use o endereço HTTPS da versão Vercel do DiploCine.", back);
        EditText input = field("https://seu-diplocine.vercel.app", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(origin);
        button("Conectar", true, () -> {
            String candidate;
            try { candidate = DiploApi.origin(input.getText().toString()); }
            catch (IllegalArgumentException error) { message("Informe uma URL HTTPS válida, sem caminho."); return; }
            task(() -> new DiploApi(candidate, "").get("/api/v1/status"), response -> {
                if (!"DiploAPI".equals(response.optString("name"))) {
                    message("Este endereço não respondeu como DiploAPI.");
                    return;
                }
                if (!candidate.equals(origin)) {
                    forgetSession();
                    channels = null;
                }
                origin = candidate;
                prefs.edit().putString("origin", origin).apply();
                next.run();
            }, this::showError);
        });
    }

    private void signIn() {
        if (tv) {
            pairingScreen();
            return;
        }
        screen("Entre na sua conta", "Use a mesma conta do site DiploCine.", this::chooseMode);
        EditText email = field("E-mail", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = field("Senha", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Entrar", true, () -> {
            String address = email.getText().toString().trim(), secret = password.getText().toString();
            if (address.isEmpty() || secret.isEmpty()) { message("Preencha o e-mail e a senha."); return; }
            task(() -> api().post("/api/v1/mobile/login",
                    DiploApi.object("email", address, "password", secret, "deviceName", "DiploCine Celular")),
                    response -> acceptSession(response.optString("token")), this::showError);
        });
        button("Criar conta ou recuperar senha", false, () -> openWeb("/"));
        button("Alterar endereço do site", false, () -> siteSetup(this::start, this::signIn));
    }

    private void acceptSession(String value) {
        if (!value.matches("[a-f0-9]{64}")) {
            message("O servidor enviou uma sessão inválida.");
            return;
        }
        try { sessionStore.save(value); }
        catch (Exception error) { message("O Android não conseguiu proteger a sessão."); return; }
        token = value;
        start();
    }

    private void forgetSession() {
        sessionStore.clear();
        token = "";
        state = new JSONObject();
        profile = "";
        accountId = "";
        accent = RED;
    }

    private void pairingScreen() {
        Pairing previous = pairing;
        screen("Conectar ao DiploCine", "No celular logado, abra Perfil › Dispositivos › Conectar TV.", this::chooseMode);
        TextView status = text("Gerando QR e código…", 19, MUTED);
        button("Alterar endereço do site", false, () -> siteSetup(this::start, this::pairingScreen));
        task(() -> api().post(DiploApi.PAIR + "start",
                previous == null ? DiploApi.object() : DiploApi.object("replaceDeviceSecret", previous.secret())), result -> {
            String code = normalizeCode(result.optString("code"));
            String secret = result.optString("deviceSecret");
            if (!validCode(code) || !secret.matches("[a-f0-9]{64}")) {
                status.setText("Não foi possível gerar um código válido.");
                button("Tentar novamente", true, this::pairingScreen);
                return;
            }
            int expires = Math.max(1, result.optInt("expiresIn", 300));
            int interval = Math.max(15, result.optInt("interval", 15));
            showPairing(new Pairing(code, secret, SystemClock.elapsedRealtime() + expires * 1000L,
                    interval * 1000L));
        }, failure -> errorPage("Não foi possível gerar o código", failure, this::pairingScreen,
                () -> siteSetup(this::start, this::chooseMode)));
    }

    private void showPairing(Pairing value) {
        screen("Conectar ao DiploCine", "Escaneie o QR ou digite o código abaixo no celular logado.", this::chooseMode);
        pairing = value;
        ImageView qr = new ImageView(this);
        qr.setContentDescription("QR para conectar esta TV");
        try {
            String link = origin + "/dispositivos?pair=" + value.code();
            var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 280, 280);
            Bitmap bitmap = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[280 * 280];
            for (int y = 0; y < 280; y++) {
                for (int x = 0; x < 280; x++) pixels[y * 280 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            bitmap.setPixels(pixels, 0, 280, 0, 0, 280, 280);
            qr.setImageBitmap(bitmap);
            body.addView(qr, new LinearLayout.LayoutParams(dp(174), dp(174)));
        } catch (Exception error) {
            text("Use o código manual para conectar esta TV.", 18, MUTED);
        }
        TextView code = text(formatCode(value.code()), 36, Color.WHITE);
        code.setLetterSpacing(.12f);
        code.setTypeface(Typeface.DEFAULT_BOLD);
        countdown = text("", 18, MUTED);
        button("Gerar novo código", false, this::pairingScreen);
        button("Alterar endereço do site", false, () -> siteSetup(this::start, this::pairingScreen));
        schedulePairing(0);
    }

    private void schedulePairing(long delay) {
        if (!foreground || pairing == null) return;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);
        handler.postDelayed(poll, delay);
        handler.post(tick);
    }

    private void tickPairing() {
        if (pairing == null || !foreground) return;
        long ms = pairing.deadline() - SystemClock.elapsedRealtime();
        if (ms <= 0) { pairingScreen(); return; }
        long seconds = (ms + 999) / 1000;
        countdown.setText(String.format(Locale.ROOT, "Código válido por %02d:%02d", seconds / 60, seconds % 60));
        handler.postDelayed(tick, Math.min(ms, 1000));
    }

    private void pollPairing() {
        Pairing current = pairing;
        if (current == null || !foreground) return;
        task(() -> api().post(DiploApi.PAIR + "poll", DiploApi.object("deviceSecret", current.secret())), result -> {
            if (pairing != current) return;
            switch (result.optString("status")) {
                case "authorized" -> acceptSession(result.optString("token"));
                case "rejected" -> {
                    screen("Conexão recusada", "Gere outro código para tentar de novo.", this::chooseMode);
                    button("Gerar código", true, this::pairingScreen);
                }
                default -> schedulePairing(current.interval());
            }
        }, failure -> {
            if (pairing != current) return;
            if (failure instanceof DiploApi.Failure f && (f.status == 404 || f.status == 409 || f.status == 410)) pairingScreen();
            else schedulePairing(current.interval() * 2);
        });
    }

    private void setState(JSONObject response) {
        accountId = response.optString("accountId");
        state = response;
    }

    private void showProfiles() {
        profile = "";
        accent = RED;
        screen("Quem está assistindo?", "Escolha um perfil da sua conta DiploCine.", this::settings);
        JSONArray profiles = array(object(state, "data"), "profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.optJSONObject(i);
            if (item == null) continue;
            button(item.optString("name", "Perfil"), false, () -> selectProfile(item));
        }
        if (profiles.length() < 4) button("Criar perfil", false, this::createProfile);
        button("Conta e dispositivos", false, this::settings);
    }

    private void createProfile() {
        screen("Criar perfil", "O novo perfil também fica disponível no site.", this::showProfiles);
        EditText name = field("Nome do perfil", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        button("Salvar", true, () -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { message("Informe um nome para o perfil."); return; }
            String id = UUID.randomUUID().toString();
            updateState(data -> {
                JSONArray profiles = array(data, "profiles");
                if (profiles.length() >= 4) throw new IllegalStateException("Sua conta já tem quatro perfis.");
                profiles.put(DiploApi.object("id", id, "name", label, "avatar", ""));
                data.put("profiles", profiles);
            }, this::showProfiles);
        });
    }

    private void selectProfile(JSONObject item) {
        String id = item.optString("id");
        JSONArray locked = array(state, "pinProtected");
        for (int i = 0; i < locked.length(); i++) {
            if (id.equals(locked.optString(i))) {
                screen("Digite o PIN", item.optString("name"), this::showProfiles);
                EditText pin = field("PIN de quatro dígitos", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
                button("Abrir perfil", true, () -> unlock(id, pin.getText().toString()));
                return;
            }
        }
        unlock(id, "");
    }

    private void unlock(String id, String pin) {
        task(() -> api().post("/auth/profile/unlock", DiploApi.object("profileId", id, "pin", pin)), ignored -> {
            profile = id;
            String saved = object(object(object(state, "data"), "preferences"), id).optString(THEME_FIELD);
            accent = parseColor(saved, RED);
            catalog("Início", "trending/all/week");
        }, this::showError);
    }

    private interface EditState { void apply(JSONObject data) throws Exception; }

    private void updateState(EditState edit, Runnable after) {
        DiploApi client = api();
        task(() -> client.updateState(data -> {
            edit.apply(data);
            return true;
        }), response -> { setState(response); after.run(); }, this::showError);
    }

    private void devices() {
        screen("Dispositivos", "Gerencie as TVs conectadas à sua conta.", this::settings);
        button("Escanear QR da TV", true, () -> new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Aponte a câmera para o QR exibido na TV")
                .setBeepEnabled(false).initiateScan());
        EditText input = field("Código exibido na TV", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        button("Digitar código", false, () -> lookupPairing(input.getText().toString()));
        task(() -> api().get(DiploApi.ACCOUNT + "tvs"), result -> {
            JSONArray list = array(result, "tvs");
            for (int i = 0; i < list.length(); i++) {
                JSONObject device = list.optJSONObject(i);
                if (device == null) continue;
                String id = device.optString("id");
                button("Desconectar " + device.optString("name", "TV"), false, () ->
                        new AlertDialog.Builder(this).setMessage("Desconectar esta TV da conta?")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Desconectar", (d, w) -> task(() -> {
                                    api().delete(DiploApi.ACCOUNT + "tvs/" + DiploApi.param(id));
                                    return null;
                                }, ignored -> devices(), this::showError)).show());
            }
        }, this::showError);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        var scan = IntentIntegrator.parseActivityResult(request, result, data);
        if (scan == null) { super.onActivityResult(request, result, data); return; }
        if (scan.getContents() == null) return;
        try {
            Uri uri = Uri.parse(scan.getContents());
            String scannedOrigin = DiploApi.origin(uri.getScheme() + "://" + uri.getEncodedAuthority());
            if (!origin.equalsIgnoreCase(scannedOrigin) || !"/dispositivos".equals(uri.getPath())) {
                throw new IllegalArgumentException();
            }
            lookupPairing(uri.getQueryParameter("pair"));
        } catch (IllegalArgumentException error) {
            message("Este QR não pertence ao DiploCine conectado.");
        }
    }

    private void lookupPairing(String raw) {
        String code = normalizeCode(raw);
        if (!validCode(code)) { message("Digite os oito caracteres que aparecem na TV."); return; }
        task(() -> api().post(DiploApi.ACCOUNT + "tv-pairings/lookup", DiploApi.object("code", code)), result -> {
            String id = result.optString("pairingId");
            if (!id.matches("[a-f0-9]{64}")) { message("Pareamento inválido."); return; }
            new AlertDialog.Builder(this).setTitle("Conectar " + result.optString("deviceName", "TV"))
                    .setMessage("Confira se " + formatCode(code) + " aparece na TV à sua frente.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Autorizar", (dialog, which) -> task(() ->
                            api().post(DiploApi.ACCOUNT + "tv-pairings/" + id + "/approve", DiploApi.object()),
                            ignored -> { message("TV autorizada."); devices(); }, this::showError))
                    .show();
        }, this::showError);
    }

    private static String normalizeCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean validCode(String code) { return code.matches("[A-HJ-NP-Z2-9]{8}"); }
    private static String formatCode(String code) { return code.substring(0, 4) + "-" + code.substring(4); }

    private void settings() {
        screen("Perfil e configurações", state.optString("email"), profile.isEmpty() ? null : this::home);
        if (!token.isEmpty()) {
            button("Trocar perfil", false, this::showProfiles);
            if (!tv) button("Dispositivos · conectar TV", false, this::devices);
            if (!profile.isEmpty()) button("Aparência · mudar cores", false, this::appearance);
            button("Sair da conta", false, () -> task(
                    () -> api().post("/api/v1/mobile/logout", DiploApi.object()),
                    ignored -> { forgetSession(); signIn(); }, failure -> {
                        if (failure instanceof DiploApi.Failure f && f.status == 401) {
                            forgetSession();
                            signIn();
                        } else showError(failure);
                    }));
        }
        button("Modo de exibição", false, this::chooseMode);
        button("Alterar endereço do site", false, () -> siteSetup(this::start, this::settings));
        button("Abrir DiploCine no navegador", false, () -> openWeb("/"));
        if (!token.isEmpty()) {
            button("Estatísticas · site", false, () -> openWeb("/estatisticas"));
            button("Ranking de horas · site", false, () -> openWeb("/ranking-horas"));
            button("Doações Pix · site", false, () -> openWeb("/doacoes"));
        }
    }

    private void appearance() {
        screen("Aparência", "A cor é salva no perfil. O vermelho é o padrão do DiploCine.", this::settings);
        palette("Vermelho DiploCine", "#E50914");
        palette("Azul", "#4F80FF");
        palette("Roxo", "#A16BFF");
        palette("Verde", "#42D6A4");
        palette("Âmbar", "#F9B34D");
        EditText hex = field("#RRGGBB", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setText(String.format(Locale.ROOT, "#%06X", accent & 0xffffff));
        button("Salvar cor personalizada", true, () -> saveAccent(hex.getText().toString()));
    }

    private void palette(String name, String value) {
        Button sample = button(name, false, () -> saveAccent(value));
        sample.setCompoundDrawablePadding(dp(9));
        GradientDrawable swatch = shape(parseColor(value, RED), dp(18));
        swatch.setBounds(0, 0, dp(19), dp(19));
        sample.setCompoundDrawables(swatch, null, null, null);
    }

    private void saveAccent(String raw) {
        String color = raw.trim().toUpperCase(Locale.ROOT);
        if (!color.matches("#[0-9A-F]{6}")) { message("Use uma cor no formato #RRGGBB."); return; }
        String id = profile;
        updateState(data -> {
            JSONObject preferences = object(data, "preferences");
            JSONObject selected = object(preferences, id);
            selected.put(THEME_FIELD, color);
            preferences.put(id, selected);
            data.put("preferences", preferences);
        }, () -> { accent = Color.parseColor(color); appearance(); });
    }

    private static int parseColor(String raw, int fallback) {
        return raw.matches("#[0-9A-Fa-f]{6}") ? Color.parseColor(raw) : fallback;
    }

    private void home() { catalog("Início", "trending/all/week"); }

    private void catalog(String title, String route) {
        browse(title, "Filmes e séries do catálogo DiploCine");
        LinearLayout tabs = row(body);
        action(tabs, "Em alta", route.equals("trending/all/week"), this::home);
        action(tabs, "Filmes", route.equals("movie/popular"), () -> catalog("Filmes", "movie/popular"));
        action(tabs, "Séries", route.equals("tv/popular"), () -> catalog("Séries", "tv/popular"));
        action(tabs, "Animes", route.startsWith("discover/tv?with_genres"), () ->
                catalog("Animes", "discover/tv?with_genres=16&with_original_language=ja"));
        action(tabs, "Doramas", route.startsWith("discover/tv?with_original_language"), () ->
                catalog("Doramas", "discover/tv?with_original_language=ko&without_genres=16"));
        String defaultType = route.startsWith("movie/") ? "movie" : route.startsWith("tv/") || route.startsWith("discover/tv") ? "tv" : "";
        if (route.equals("trending/all/week")) {
            LinearLayout shelf = column();
            body.addView(shelf);
            task(() -> api().get("/auth/state"), response -> {
                setState(response);
                showResumeShelf(shelf);
            }, ignored -> showResumeShelf(shelf));
        }
        loadTitles(route, defaultType, () -> catalog(title, route));
    }

    private JSONObject profileStore() {
        return object(object(object(state, "data"), "storage"), profile);
    }

    private void showResumeShelf(LinearLayout shelf) {
        JSONArray recent = array(profileStore(), "diploAndroidResume");
        LinearLayout buttons = null;
        int shown = 0;
        for (int i = 0; i < recent.length() && shown < 8; i++) {
            JSONObject item = recent.optJSONObject(i);
            if (item == null || item.optLong("position") <= 0) continue;
            if (buttons == null) {
                shelf.addView(label("Continuar assistindo", 22, Color.WHITE));
                buttons = row(shelf);
            }
            shown++;
            int id = item.optInt("id"), season = item.optInt("season"), episode = item.optInt("episode");
            String title = item.optString("title"), type = item.optString("type");
            action(buttons, season > 0 ? title + " · T" + season + " E" + episode : title, false,
                    () -> playTitle(type, id, season, episode, title, null));
        }
    }

    private void myList() {
        browse("Minha Lista", "Títulos salvos no app com este perfil.");
        JSONArray list = array(profileStore(), "diploAndroidList");
        if (list.length() == 0) text("Adicione um filme ou uma série na página de detalhes.", 17, MUTED);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type"), title = item.optString("title");
            int id = item.optInt("id");
            button(title, false, () -> details(type, id, title, this::myList));
        }
    }

    private void setListed(String type, int id, String title, boolean listed, Runnable refresh) {
        String selected = profile;
        updateState(data -> {
            JSONObject storage = object(data, "storage");
            JSONObject saved = object(storage, selected);
            JSONArray items = array(saved, "diploAndroidList");
            int existing = findListTitle(items, type, id);
            if (!listed && existing >= 0) items.remove(existing);
            if (listed && existing < 0) {
                if (items.length() >= 60) throw new IllegalStateException("Sua lista já tem 60 títulos.");
                items.put(DiploApi.object("type", type, "id", id, "title", title));
            }
            saved.put("diploAndroidList", items);
            storage.put(selected, saved);
            data.put("storage", storage);
        }, refresh);
    }

    private static int findListTitle(JSONArray items, String type, int id) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && type.equals(item.optString("type")) && item.optInt("id") == id) return i;
        }
        return -1;
    }

    private void search() {
        browse("Buscar", "Encontre filmes e séries pelo título.");
        EditText query = field("Buscar no catálogo", InputType.TYPE_CLASS_TEXT);
        button("Buscar", true, () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) return;
            browse("Resultados", value);
            loadTitles("search/multi?query=" + DiploApi.param(value), "", this::search);
        });
    }

    private void loadTitles(String route, String defaultType, Runnable back) {
        TextView status = text("Carregando catálogo…", 17, MUTED);
        task(() -> api().get(DiploApi.TMDB + route), result -> {
            JSONArray entries = array(result, "results");
            status.setVisibility(View.GONE);
            LinearLayout currentRow = null;
            int shown = 0;
            int columns = tv ? 5 : 2;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("media_type", defaultType);
                int id = item.optInt("id");
                if (id <= 0 || !(type.equals("movie") || type.equals("tv"))) continue;
                if (shown++ % columns == 0) {
                    currentRow = new LinearLayout(this);
                    body.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
                }
                addPoster(currentRow, item, () -> details(type, id, name(item), back));
            }
            if (shown == 0) text("Nenhum título encontrado.", 18, MUTED);
        }, failure -> {
            status.setText(errorText(failure));
            button("Tentar novamente", true, back);
        });
    }

    private void details(String type, int id, String title, Runnable back) {
        screen(title, type.equals("tv") ? "Série" : "Filme", back);
        task(() -> api().get(DiploApi.TMDB + type + "/" + id), detail -> {
            text(detail.optString("overview"), 17, MUTED);
            boolean saved = findListTitle(array(profileStore(), "diploAndroidList"), type, id) >= 0;
            button(saved ? "Remover da Minha Lista" : "Adicionar à Minha Lista", false,
                    () -> setListed(type, id, title, !saved, () -> details(type, id, title, back)));
            if (type.equals("movie")) {
                button("Assistir", true, () -> playTitle(type, id, 0, 0, title, null));
            } else {
                JSONArray seasons = array(detail, "seasons");
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject item = seasons.optJSONObject(i);
                    if (item == null || item.optInt("season_number") < 1) continue;
                    int season = item.optInt("season_number");
                    boolean following = hasSeason(seasons, season + 1);
                    button("Temporada " + season, false, () -> episodes(id, season, title, following,
                            () -> details(type, id, title, back)));
                }
            }
        }, failure -> errorPage(title, failure, () -> details(type, id, title, back), back));
    }

    private static boolean hasSeason(JSONArray seasons, int number) {
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.optJSONObject(i);
            if (season != null && season.optInt("season_number") == number && season.optInt("episode_count") > 0) return true;
        }
        return false;
    }

    private void episodes(int id, int season, String title, boolean following, Runnable back) {
        screen(title, "Temporada " + season, back);
        task(() -> api().get(DiploApi.TMDB + "tv/" + id + "/season/" + season), result -> {
            JSONArray entries = array(result, "episodes");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) continue;
                int episode = item.optInt("episode_number");
                JSONObject next = entries.optJSONObject(i + 1);
                String episodeName = episode + " · " + item.optString("name");
                int nextEpisode = next != null ? next.optInt("episode_number") : following ? 1 : 0;
                int nextSeason = next != null ? season : season + 1;
                int[] nextKey = nextEpisode > 0 ? new int[]{nextSeason, nextEpisode} : null;
                button(episodeName, false, () -> playTitle("tv", id, season, episode, title, nextKey));
            }
        }, failure -> errorPage(title, failure, () -> episodes(id, season, title, following, back), back));
    }

    private void playTitle(String type, int id, int season, int episode, String title, int[] next) {
        Intent playback = playbackIntent(title);
        playback.putExtra("type", type).putExtra("id", id).putExtra("season", season).putExtra("episode", episode);
        if (next != null) playback.putExtra("next", next);
        startActivity(playback);
    }

    private Intent playbackIntent(String title) {
        return new Intent(this, PlaybackActivity.class)
                .putExtra("origin", origin).putExtra("profile", profile)
                .putExtra("account", accountId).putExtra("title", title).putExtra("tv", tv)
                .putExtra("accent", accent);
    }

    private void live() {
        browse("TV ao vivo", "Canais do catálogo DiploCine");
        EditText query = field("Nome ou categoria", InputType.TYPE_CLASS_TEXT);
        LinearLayout list = column();
        TextView status = text("Carregando canais…", 17, MUTED);
        button("Filtrar canais", true, () -> renderChannels(list, query.getText().toString()));
        body.addView(list);
        if (channels != null) {
            status.setVisibility(View.GONE);
            renderChannels(list, "");
        } else {
            task(() -> api().channels(), result -> {
                channels = result;
                status.setVisibility(View.GONE);
                renderChannels(list, query.getText().toString());
            }, failure -> { status.setText(errorText(failure)); button("Tentar novamente", true, this::live); });
        }
    }

    private void renderChannels(LinearLayout list, String query) {
        if (channels == null) return;
        list.removeAllViews();
        String filter = query.toLowerCase(Locale.ROOT).trim();
        int shown = 0;
        for (int i = 0; i < channels.length(); i++) {
            JSONObject entry = channels.optJSONObject(i);
            if (entry == null) continue;
            String label = entry.optString("name"), category = entry.optString("group");
            String url = entry.optString("url"), kind = entry.optString("kind");
            if (!DiploApi.secureUrl(url) || !(label + " " + category).toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (shown++ == 60) {
                TextView hint = label("Exibindo 60 resultados. Refine a busca para encontrar outros canais.", 16, MUTED);
                list.addView(hint);
                break;
            }
            action(list, label + (kind.equals("external") ? " · Externo" : ""), false, () -> {
                if (kind.equals("external")) launch(Uri.parse(url));
                else startActivity(playbackIntent(label).putExtra("url", url).putExtra("kind", kind));
            });
        }
        if (shown == 0) list.addView(label("Nenhum canal encontrado.", 17, MUTED));
    }

    private void browse(String title, String caption) {
        screen(title, caption, title.equals("Início") ? null : this::home);
        LinearLayout navigation = tv ? new LinearLayout(this) : row(root);
        if (tv) {
            navigation.setOrientation(LinearLayout.VERTICAL);
            navigation.setPadding(dp(10), dp(30), dp(8), dp(10));
            root.addView(navigation, 0, new LinearLayout.LayoutParams(dp(164), -1));
        } else {
            navigation.setGravity(Gravity.CENTER);
        }
        action(navigation, "Início", title.equals("Início"), this::home);
        action(navigation, "Buscar", title.equals("Buscar"), this::search);
        action(navigation, "Ao vivo", title.equals("TV ao vivo"), this::live);
        action(navigation, "Lista", title.equals("Minha Lista"), this::myList);
        action(navigation, "Perfil", false, this::settings);
        if (tv) action(navigation, "Mais no site", false, () -> openWeb("/"));
    }

    private void addPoster(LinearLayout parent, JSONObject item, Runnable click) {
        LinearLayout card = column();
        card.setFocusable(true);
        card.setBackground(focusBackground(false));
        card.setClipToOutline(true);
        card.setOnClickListener(view -> click.run());
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, -2, 1);
        placement.setMargins(0, 0, dp(8), dp(9));
        parent.addView(card, placement);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(SURFACE);
        card.addView(image, new LinearLayout.LayoutParams(-1, dp(tv ? 142 : 185)));
        TextView title = label(name(item), 16, Color.WHITE);
        title.setMaxLines(2);
        card.addView(title);
        String poster = item.optString("poster_path");
        if (!poster.matches("/[a-zA-Z0-9._-]+")) return;
        images.execute(() -> {
            try {
                var connection = new URL("https://image.tmdb.org/t/p/w342" + poster).openConnection();
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                try (var stream = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    handler.post(() -> { if (image.isAttachedToWindow()) image.setImageBitmap(bitmap); });
                }
            } catch (Exception ignored) { }
        });
    }

    private static String name(JSONObject item) { return item.optString("title", item.optString("name", "Título")); }

    private void screen(String title, String description, Runnable back) {
        page++;
        pairing = null;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);
        backAction = back;
        root = column();
        root.setOrientation(tv ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, tv ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        body = column();
        body.setPadding(dp(tv ? 34 : 20), dp(tv ? 22 : 18), dp(tv ? 34 : 20), dp(20));
        scroll.addView(body);
        setContentView(root);
        TextView logo = text("DIPLOCINE", 19, accent);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setLetterSpacing(.16f);
        TextView heading = text(title, tv ? 33 : 29, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        if (!description.trim().isEmpty()) text(description, tv ? 19 : 16, MUTED);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(LinearLayout parent) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.setFillViewport(true);
        scroll.addView(row);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(8));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = label(value, size, color);
        body.addView(view);
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(MUTED);
        input.setBackgroundTintList(ColorStateList.valueOf(accent));
        input.setMaxWidth(dp(430));
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private Button button(String caption, boolean primary, Runnable click) {
        return action(body, caption, primary, click);
    }

    private Button action(LinearLayout parent, String caption, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(caption);
        button.setAllCaps(false);
        button.setTextSize(tv ? 17 : 15);
        button.setTextColor(primary && brightness(accent) > .55 ? Color.BLACK : Color.WHITE);
        button.setMinHeight(dp(46));
        button.setPadding(dp(15), dp(4), dp(15), dp(4));
        button.setBackground(focusBackground(primary));
        button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(4), dp(7), dp(4));
        parent.addView(button, params);
        return button;
    }

    private StateListDrawable focusBackground(boolean primary) {
        int fill = primary ? accent : SURFACE;
        GradientDrawable focus = shape(fill, dp(10));
        focus.setStroke(dp(3), primary || brightness(accent) < .12 ? Color.WHITE : accent);
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, focus);
        result.addState(new int[]{}, shape(fill, dp(10)));
        return result;
    }

    private static double brightness(int color) {
        return (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114) / 255;
    }

    private static GradientDrawable shape(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private DiploApi api() { return new DiploApi(origin, token); }

    private <T> void task(Callable<T> work, Consumer<T> success, Consumer<Exception> error) {
        int issued = page;
        worker.execute(() -> {
            try {
                T result = work.call();
                handler.post(() -> { if (issued == page && !isDestroyed()) success.accept(result); });
            } catch (Exception failure) {
                handler.post(() -> { if (issued == page && !isDestroyed()) error.accept(failure); });
            }
        });
    }

    private void errorPage(String title, Exception failure, Runnable retry, Runnable back) {
        screen(title, errorText(failure), back);
        button("Tentar novamente", true, retry);
    }

    private static String errorText(Exception error) {
        if (error instanceof DiploApi.Failure || error instanceof IllegalStateException) return error.getMessage();
        return "Falha ao carregar. Confira a conexão e tente novamente.";
    }

    private void showError(Exception error) { message(errorText(error)); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private void openWeb(String path) { if (!origin.isEmpty()) launch(Uri.parse(origin + path)); }

    private void launch(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) { message("Este aparelho não tem um navegador disponível."); }
    }

    static JSONObject object(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        return child == null ? new JSONObject() : child;
    }

    static JSONArray array(JSONObject parent, String key) {
        JSONArray child = parent.optJSONArray(key);
        return child == null ? new JSONArray() : child;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
