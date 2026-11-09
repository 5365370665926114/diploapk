package tv.own.owntv.diplocine

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.get
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.MpvVideoSurface
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerHud
import tv.own.owntv.player.SubtitleOverlay
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.stageBackground

class DiploCatalogActivity : ComponentActivity() {
    private val player: OwnTVPlayer by lazy { get() }
    private val settings: SettingsRepository by lazy { get() }
    private var visible by mutableStateOf(false)
    private var ownsPlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session = SessionStore(getSharedPreferences("diplo-entry", MODE_PRIVATE))
        setContent {
            val color by settings.customAccent.collectAsStateWithLifecycle(initialValue = "#E50914")
            tv.own.owntv.ui.theme.OwnTVTheme(ThemeMode.DARK, AccentColor.TEAL, true, customAccent = color) {
                Catalog(session.load())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        stopPlayback()
        super.onStop()
    }

    private fun stopPlayback() {
        if (ownsPlayback) player.stop()
        ownsPlayback = false
    }

    @Composable
    private fun Catalog(token: String) {
        var movies by remember { mutableStateOf(true) }
        var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
        var selection by remember { mutableStateOf<Title?>(null) }
        var details by remember { mutableStateOf<JSONObject?>(null) }
        var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
        var season by remember { mutableIntStateOf(0) }
        var playing by remember { mutableStateOf<Episode?>(null) }
        var videoReady by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        val rowState = rememberLazyListState()
        val engine = remember { MpvPlaybackEngine(player) }
        val backToBrowse = {
            when {
                playing != null -> { stopPlayback(); playing = null }
                selection != null -> { selection = null; season = 0 }
                else -> finish()
            }
        }
        BackHandler(onBack = backToBrowse)

        LaunchedEffect(movies, retry) {
            if (selection != null || token.isBlank()) return@LaunchedEffect
            message = null
            titles = emptyList()
            try {
                val type = if (movies) "movie" else "tv"
                titles = items(DiploSite.metadata("$type/popular").optJSONArray("results")).mapNotNull { item ->
                    val id = item.optInt("id")
                    val label = item.optString(if (type == "tv") "name" else "title")
                    if (id > 0 && label.isNotBlank()) Title(id, type, label, item.optString("poster_path")) else null
                }.distinctBy(Title::id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = userError(error) }
        }
        LaunchedEffect(selection, retry) {
            val item = selection ?: return@LaunchedEffect
            if (playing != null) return@LaunchedEffect
            details = null
            message = null
            try { details = DiploSite.metadata("${item.type}/${item.id}") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = userError(error) }
        }
        LaunchedEffect(selection, season) {
            episodes = emptyList()
            val item = selection ?: return@LaunchedEffect
            if (season == 0) return@LaunchedEffect
            try {
                episodes = items(DiploSite.metadata("tv/${item.id}/season/$season").optJSONArray("episodes"))
                    .mapNotNull { entry ->
                        val number = entry.optInt("episode_number")
                        if (number > 0) Episode(season, number, entry.optString("name")) else null
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = userError(error) }
        }
        LaunchedEffect(playing, retry, visible) {
            videoReady = false
            if (!visible) return@LaunchedEffect
            val item = selection ?: return@LaunchedEffect
            val episode = playing ?: return@LaunchedEffect
            message = null
            try {
                val url = DiploSite.playback(token, item.type, item.id, episode.season, episode.number)
                player.play(url, title = item.name, subtitle = episode.name,
                    seasonNumber = episode.season.takeIf { it > 0 }, episodeNumber = episode.number.takeIf { it > 0 })
                ownsPlayback = true
                videoReady = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = userError(error) }
        }

        Box(
            Modifier.fillMaxSize().background(Color(0xFF050505))
                .stageBackground(tv.own.owntv.ui.theme.stageAccent.accent),
        ) {
            if (videoReady && playing != null) {
                MpvVideoSurface(player, Modifier.fillMaxSize())
                SubtitleOverlay(player, Modifier.fillMaxSize())
                PlayerHud(engine, onBack = backToBrowse, modifier = Modifier.fillMaxSize())
                val active = playing
                val next = episodes.firstOrNull { it.number > (active?.number ?: Int.MAX_VALUE) }
                if (next != null) {
                    StageButton(stringResource(R.string.diplo_next_episode), { stopPlayback(); playing = next },
                        Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 112.dp), height = 44.dp)
                }
                return@Box
            }
            Column(Modifier.fillMaxSize().padding(25.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                val goBack = remember { FocusRequester() }
                val item = selection
                val video = playing
                val focused = remember(item, video) { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StageButton(stringResource(R.string.diplo_back), backToBrowse, Modifier.focusRequester(goBack), icon = OwnTVIcon.BACK, height = 44.dp)
                    Text(item?.name ?: stringResource(R.string.diplo_catalog), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (token.isBlank()) {
                    Text(stringResource(R.string.diplo_login_for_catalog), color = Color.White)
                    StageButton(stringResource(R.string.diplo_connect_tv), {
                        startActivity(Intent(this@DiploCatalogActivity, DiploStartActivity::class.java).putExtra("choose", true))
                        finish()
                    }, Modifier.focusRequester(focused), height = 44.dp)
                } else if (message != null) {
                    Text(message.orEmpty(), color = Color.White)
                    StageButton(stringResource(R.string.diplo_retry), { if (season > 0 && video == null) season = 0; retry++ },
                        Modifier.focusRequester(focused), height = 44.dp)
                } else if (video != null) {
                    Text(stringResource(R.string.diplo_loading_video), color = Color.White)
                } else if (item == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        StageButton(stringResource(R.string.diplo_movies), { movies = true }, Modifier.focusRequester(focused),
                            icon = OwnTVIcon.MOVIES, height = 43.dp, tinted = movies)
                        StageButton(stringResource(R.string.diplo_series), { movies = false }, icon = OwnTVIcon.SERIES,
                            height = 43.dp, tinted = !movies)
                    }
                    if (titles.isEmpty()) Text(stringResource(R.string.diplo_loading_titles), color = Color.White)
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(titles, key = Title::id) { title ->
                            StagePoster(title.name, { selection = title }, width = 106.dp, height = 151.dp) {
                                if (title.poster.startsWith('/')) {
                                    AsyncImage(model = "https://image.tmdb.org/t/p/w342" + title.poster,
                                        contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                } else {
                    val data = details
                    Text(data?.optString("overview").orEmpty().ifBlank { stringResource(R.string.diplo_loading_details) },
                        color = Color(0xFFCAC6CE), fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (data != null && item.type == "movie") {
                        StageButton(stringResource(R.string.diplo_watch), { playing = Episode(0, 0, "") }, Modifier.focusRequester(focused),
                            icon = OwnTVIcon.PLAY, height = 47.dp)
                    }
                    if (data != null && item.type == "tv") {
                        val seasons = items(data.optJSONArray("seasons")).map { it.optInt("season_number") }.filter { it > 0 }.distinct()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(seasons) { number ->
                                StageButton(stringResource(R.string.diplo_season, number), { season = number }, height = 40.dp, tinted = season == number)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(episodes, key = Episode::number) { episode ->
                                StageButton(stringResource(R.string.diplo_episode, episode.number, episode.name), { playing = episode }, height = 44.dp)
                            }
                        }
                    }
                }
                LaunchedEffect(item, video, message) { goBack.requestFocus() }
            }
        }
    }

    private fun userError(error: Exception): String =
        (error as? DiploSite.HttpFailure)?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.diplo_catalog_failed)

    private fun items(data: JSONArray?): List<JSONObject> =
        if (data == null) emptyList() else List(data.length()) { data.optJSONObject(it) }.filterNotNull()

    private data class Title(val id: Int, val type: String, val name: String, val poster: String)
    private data class Episode(val season: Int, val number: Int, val name: String)
}
