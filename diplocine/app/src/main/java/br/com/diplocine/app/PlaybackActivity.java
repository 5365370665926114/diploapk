package br.com.diplocine.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final Runnable periodic = this::updateProgress;
    private ExoPlayer player;
    private PlayerView surface;
    private TextView status;
    private Button retry, nextButton, rotate;
    private DiploApi api;
    private SharedPreferences preferences;
    private String type, title, profile, account, directUrl, directKind, playbackId;
    private int id, season, episode, sequence, generation;
    private int[] next;
    private long position, lastRemotePosition = -1;
    private String lastRemoteMedia = "";
    private boolean tv, fullscreen, checkedRemoteProgress;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        var input = getIntent();
        tv = input.getBooleanExtra("tv", false);
        fullscreen = tv;
        applyOrientation();
        preferences = getSharedPreferences("diplocine", MODE_PRIVATE);
        api = new DiploApi(input.getStringExtra("origin"), new SessionStore(preferences).load());
        type = input.getStringExtra("type");
        title = input.getStringExtra("title");
        profile = input.getStringExtra("profile");
        account = input.getStringExtra("account");
        directUrl = input.getStringExtra("url");
        directKind = input.getStringExtra("kind");
        id = input.getIntExtra("id", 0);
        season = saved == null ? input.getIntExtra("season", 0) : saved.getInt("season");
        episode = saved == null ? input.getIntExtra("episode", 0) : saved.getInt("episode");
        next = saved == null ? input.getIntArrayExtra("next") : saved.getIntArray("next");
        position = saved == null ? resumePosition() : saved.getLong("position");
        playbackId = UUID.randomUUID().toString();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xff050505);
        setContentView(root);
        status = new TextView(this);
        status.setPadding(dp(18), dp(12), dp(18), dp(8));
        status.setTextSize(tv ? 19 : 16);
        status.setTextColor(Color.WHITE);
        root.addView(status);
        surface = new PlayerView(this);
        surface.setControllerAutoShow(true);
        surface.setControllerShowTimeoutMs(4500);
        surface.setShowSubtitleButton(true);
        surface.setKeepScreenOn(true);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(14), 0, dp(14), dp(6));
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        var row = new LinearLayout(this);
        actions.addView(row);
        button(row, "Voltar", this::finish);
        if (!tv) rotate = button(row, "Tela cheia", () -> {
            fullscreen = !fullscreen;
            applyOrientation();
            rotate.setText(fullscreen ? "Voltar para vertical" : "Tela cheia");
        });
        retry = button(row, "Tentar novamente", this::resolve);
        retry.setVisibility(Button.GONE);
        nextButton = button(actions, "Próximo episódio", this::nextEpisode);
        int accent = input.getIntExtra("accent", 0xffe50914);
        nextButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        nextButton.setTextColor(android.graphics.Color.luminance(accent) > .35 ? Color.BLACK : Color.WHITE);
        updateNextButton();
    }

    @Override protected void onStart() {
        super.onStart();
        resolve();
        if ("tv".equals(type) && next == null) findNext();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(periodic);
        if (player != null) checkpoint(player.getPlaybackState() == Player.STATE_ENDED ? "ended" : "paused");
        generation++;
        release();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        events.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("season", season);
        out.putInt("episode", episode);
        out.putIntArray("next", next);
        out.putLong("position", player == null ? position : player.getCurrentPosition());
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (tv && surface != null && player != null && surface.hasFocus() && !surface.isControllerFullyVisible()
                && event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            surface.showController();
            surface.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applyOrientation() {
        setRequestedOrientation(fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (fullscreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void resolve() {
        handler.removeCallbacks(periodic);
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) checkpoint("paused");
        release();
        int version = ++generation;
        status.setText(label() + " · Carregando vídeo…");
        retry.setVisibility(Button.GONE);
        if (directUrl != null) {
            prepare(directUrl);
            return;
        }
        int currentSeason = Math.max(1, season), currentEpisode = Math.max(1, episode);
        boolean checkRemote = !checkedRemoteProgress;
        checkedRemoteProgress = true;
        String media = mediaKey();
        long localTime = preferences.getLong(progressKey() + ":time", 0);
        network.execute(() -> {
            try {
                String path = "/api/player/source?type=" + type + "&id=" + id
                        + "&season=" + currentSeason + "&episode=" + currentEpisode;
                String url = api.get(path).getString("url");
                long remotePosition = checkRemote ? readRemoteProgress(media, localTime) : -1;
                handler.post(() -> {
                    if (generation != version || isDestroyed()) return;
                    if (remotePosition >= 0) position = remotePosition;
                    prepare(url);
                });
            } catch (Exception error) {
                String text = error instanceof DiploApi.Failure ? error.getMessage() : "Falha ao carregar a fonte.";
                handler.post(() -> { if (generation == version && !isDestroyed()) failed(text); });
            }
        });
    }

    private void prepare(String url) {
        if (!DiploApi.secureUrl(url)) { failed("Fonte incompatível."); return; }
        String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        if ("hls".equals(directKind) || path.endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        if (path.endsWith(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        player = new ExoPlayer.Builder(this).build();
        surface.setPlayer(player);
        player.setMediaItem(item.build(), position);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) status.setText(label());
                if (state == Player.STATE_ENDED) {
                    status.setText(label() + " · Concluído");
                    checkpoint("ended");
                    if (next != null) nextButton.requestFocus();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                failed("O vídeo não pôde ser reproduzido nesta fonte.");
            }
        });
        player.prepare();
        player.play();
        if (tv) surface.requestFocus();
        handler.postDelayed(periodic, 15_000);
    }

    private void updateProgress() {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_READY) checkpoint(player.isPlaying() ? "playing" : "paused");
        if (state != Player.STATE_ENDED) handler.postDelayed(periodic, 15_000);
    }

    private void checkpoint(String state) {
        if (type == null || player == null || player.getDuration() == C.TIME_UNSET || player.getDuration() <= 0) return;
        position = player.getCurrentPosition();
        long resume = "ended".equals(state) ? 0 : position;
        long time = System.currentTimeMillis();
        preferences.edit().putLong(progressKey(), resume).putLong(progressKey() + ":time", time).apply();
        var body = DiploApi.object("profileId", profile, "sessionId", playbackId,
                "titleId", mediaKey(), "seq", sequence++, "mediaPosition", position / 1000d,
                "state", state, "pageVisible", true);
        JSONObject saved = DiploApi.object("key", mediaKey(), "type", type, "id", id, "title", title,
                "season", season, "episode", episode, "position", resume, "time", time);
        boolean syncProgress = !"playing".equals(state)
                && (!mediaKey().equals(lastRemoteMedia) || resume != lastRemotePosition);
        if (syncProgress) {
            lastRemoteMedia = mediaKey();
            lastRemotePosition = resume;
        }
        events.execute(() -> {
            try { api.post("/api/v1/playback/heartbeat", body); }
            catch (Exception ignored) { }
            if (syncProgress) writeRemoteProgress(saved);
        });
    }

    private long readRemoteProgress(String media, long localTime) {
        try {
            JSONObject state = api.get("/auth/state").getJSONObject("data");
            JSONArray entries = MainActivity.array(MainActivity.object(MainActivity.object(state, "storage"), profile),
                    "diploAndroidResume");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && media.equals(entry.optString("key")) && entry.optLong("time") > localTime) {
                    return entry.optLong("position");
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void writeRemoteProgress(JSONObject entry) {
        try {
            api.updateState(data -> {
                JSONObject storage = MainActivity.object(data, "storage");
                JSONObject document = MainActivity.object(storage, profile);
                JSONArray old = MainActivity.array(document, "diploAndroidResume");
                JSONArray updated = new JSONArray().put(entry);
                for (int i = 0; i < old.length() && updated.length() < 24; i++) {
                    JSONObject candidate = old.optJSONObject(i);
                    if (candidate == null) continue;
                    if (entry.optString("key").equals(candidate.optString("key"))) {
                        if (candidate.optLong("time") >= entry.optLong("time")) return false;
                    } else updated.put(candidate);
                }
                document.put("diploAndroidResume", updated);
                storage.put(profile, document);
                data.put("storage", storage);
                return true;
            });
        } catch (Exception ignored) { }
    }

    private String mediaKey() { return type + ":" + id + ":" + season + ":" + episode; }
    private String progressKey() { return "resume:" + account + ":" + profile + ":" + mediaKey(); }
    private long resumePosition() { return type == null ? 0 : preferences.getLong(progressKey(), 0); }

    private void nextEpisode() {
        if (next == null) return;
        checkpoint(player != null && player.getPlaybackState() == Player.STATE_ENDED ? "ended" : "paused");
        release();
        season = next[0];
        episode = next[1];
        next = null;
        position = resumePosition();
        checkedRemoteProgress = false;
        playbackId = UUID.randomUUID().toString();
        sequence = 0;
        updateNextButton();
        resolve();
        findNext();
    }

    private void findNext() {
        int version = generation;
        int currentSeason = season, currentEpisode = episode;
        network.execute(() -> {
            try {
                var seasonInfo = api.get(DiploApi.TMDB + "tv/" + id + "/season/" + currentSeason);
                var episodes = MainActivity.array(seasonInfo, "episodes");
                int[] following = null;
                for (int i = 0; i < episodes.length(); i++) {
                    var entry = episodes.optJSONObject(i);
                    if (entry == null) continue;
                    int number = entry.optInt("episode_number");
                    if (number > currentEpisode) { following = new int[]{currentSeason, number}; break; }
                }
                if (following == null) {
                    var show = api.get(DiploApi.TMDB + "tv/" + id);
                    var seasons = MainActivity.array(show, "seasons");
                    for (int i = 0; i < seasons.length(); i++) {
                        var entry = seasons.optJSONObject(i);
                        if (entry != null && entry.optInt("season_number") == currentSeason + 1
                                && entry.optInt("episode_count") > 0) {
                            following = new int[]{currentSeason + 1, 1};
                            break;
                        }
                    }
                }
                int[] result = following;
                handler.post(() -> { if (generation == version && !isDestroyed()) { next = result; updateNextButton(); } });
            } catch (Exception ignored) { }
        });
    }

    private void updateNextButton() { nextButton.setVisibility(next == null ? Button.GONE : Button.VISIBLE); }

    private String label() {
        return "tv".equals(type) ? title + " · T" + season + " E" + episode : title;
    }

    private void failed(String error) {
        status.setText(error);
        retry.setVisibility(Button.VISIBLE);
        retry.requestFocus();
    }

    private void release() {
        if (player == null) return;
        surface.setPlayer(null);
        player.release();
        player = null;
    }

    private Button button(ViewGroup parent, String title, Runnable click) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(view -> click.run());
        parent.addView(button);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
