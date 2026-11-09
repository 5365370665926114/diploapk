package tv.own.owntv.diplocine

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.get
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

    override fun onStop() {
        player.stop()
        super.onStop()
    }

    @Composable
    private fun Catalog(token: String) {
        var category by remember { mutableStateOf("Filmes") }
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
                playing != null -> { player.stop(); playing = null }
                selection != null -> { selection = null; season = 0 }
                else -> finish()
            }
        }
        BackHandler(onBack = backToBrowse)

        LaunchedEffect(category, retry) {
            if (selection != null || token.isBlank()) return@LaunchedEffect
            message = null
            titles = emptyList()
            try {
                val type = if (category == "Filmes") "movie" else "tv"
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
        LaunchedEffect(playing, retry) {
            videoReady = false
            val item = selection ?: return@LaunchedEffect
            val episode = playing ?: return@LaunchedEffect
            message = null
            try {
                val url = DiploSite.playback(token, item.type, item.id, episode.season, episode.number)
                player.play(url, title = item.name, subtitle = episode.name,
                    seasonNumber = episode.season.takeIf { it > 0 }, episodeNumber = episode.number.takeIf { it > 0 })
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
                    StageButton("Próximo episódio", { player.stop(); playing = next },
                        Modifier.align(Alignment.TopEnd).padding(22.dp), height = 44.dp)
                }
                return@Box
            }
            Column(Modifier.fillMaxSize().padding(25.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                val goBack = remember { FocusRequester() }
                val item = selection
                val video = playing
                val focused = remember(item, video) { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StageButton("Voltar", backToBrowse, Modifier.focusRequester(goBack), icon = OwnTVIcon.BACK, height = 44.dp)
                    Text(item?.name ?: "Catálogo DiploCine", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (token.isBlank()) {
                    Text("Conecte a TV à conta DiploCine para assistir.", color = Color.White)
                    StageButton("Conectar TV", {
                        startActivity(Intent(this@DiploCatalogActivity, DiploStartActivity::class.java).putExtra("choose", true))
                        finish()
                    }, Modifier.focusRequester(focused), height = 44.dp)
                } else if (message != null) {
                    Text(message.orEmpty(), color = Color.White)
                    StageButton("Tentar novamente", { if (season > 0 && video == null) season = 0; retry++ },
                        Modifier.focusRequester(focused), height = 44.dp)
                } else if (video != null) {
                    Text("Carregando vídeo…", color = Color.White)
                } else if (item == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        StageButton("Filmes", { category = "Filmes" }, Modifier.focusRequester(focused),
                            icon = OwnTVIcon.MOVIES, height = 43.dp, tinted = category == "Filmes")
                        StageButton("Séries", { category = "Séries" }, icon = OwnTVIcon.SERIES,
                            height = 43.dp, tinted = category == "Séries")
                    }
                    if (titles.isEmpty()) Text("Carregando títulos…", color = Color.White)
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
                    Text(data?.optString("overview").orEmpty().ifBlank { "Carregando detalhes…" },
                        color = Color(0xFFCAC6CE), fontSize = 16.sp, maxLines = 3)
                    if (data != null && item.type == "movie") {
                        StageButton("Assistir", { playing = Episode(0, 0, "") }, Modifier.focusRequester(focused),
                            icon = OwnTVIcon.PLAY, height = 47.dp)
                    }
                    if (data != null && item.type == "tv") {
                        val seasons = items(data.optJSONArray("seasons")).map { it.optInt("season_number") }.filter { it > 0 }.distinct()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(seasons) { number ->
                                StageButton("Temporada $number", { season = number }, height = 40.dp, tinted = season == number)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(episodes, key = Episode::number) { episode ->
                                StageButton("${episode.number}. ${episode.name}", { playing = episode }, height = 44.dp)
                            }
                        }
                    }
                }
                LaunchedEffect(item, video, message) { goBack.requestFocus() }
            }
        }
    }

    private fun userError(error: Exception): String =
        (error as? DiploSite.HttpFailure)?.message ?: "Não foi possível carregar. Confira sua conexão."

    private fun items(data: JSONArray?): List<JSONObject> =
        if (data == null) emptyList() else List(data.length()) { data.optJSONObject(it) }.filterNotNull()

    private data class Title(val id: Int, val type: String, val name: String, val poster: String)
    private data class Episode(val season: Int, val number: Int, val name: String)
}
