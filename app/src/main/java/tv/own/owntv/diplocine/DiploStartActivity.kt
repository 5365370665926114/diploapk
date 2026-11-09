package tv.own.owntv.diplocine

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import tv.own.owntv.MainActivity
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.OwnTVTheme
import java.util.Locale

class DiploStartActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("diplo-entry", MODE_PRIVATE) }
    private val session by lazy { SessionStore(preferences) }
    private var selected by mutableStateOf("")
    private val isTelevision get() = packageManager.hasSystemFeature("android.software.leanback") ||
        getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = preferences.getString("mode", "").orEmpty()
        if (!intent.getBooleanExtra("choose", false) && saved == "phone") {
            openPhone()
            return
        }
        if (!intent.getBooleanExtra("choose", false) && saved == "tv") selectTv()
        setContent {
            OwnTVTheme(ThemeMode.DARK, AccentColor.TEAL, true, customAccent = "#E50914") {
                BackHandler(enabled = selected == "tv") { selected = "" }
                if (selected == "tv") PairingScreen()
                else ModeScreen(isTelevision, ::selectTv, ::openPhone)
            }
        }
    }

    private fun selectTv() {
        selected = "tv"
        if (!isTelevision) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun openPhone() {
        preferences.edit().putString("mode", "phone").apply()
        startActivity(Intent(this, DiploMobileActivity::class.java))
        finish()
    }

    private fun openTv() {
        lifecycleScope.launch {
            if (!preferences.getBoolean("appearanceInitialized", false)) {
                val settings = get<SettingsRepository>()
                settings.setThemeMode(ThemeMode.DARK)
                settings.setCustomAccent("#E50914")
                preferences.edit().putBoolean("appearanceInitialized", true).apply()
            }
            preferences.edit().putString("mode", "tv").apply()
            startActivity(Intent(this@DiploStartActivity, MainActivity::class.java))
            finish()
        }
    }

    @Composable
    private fun PairingScreen() {
        var attempt by remember { mutableIntStateOf(0) }
        var pairing by remember { mutableStateOf<TvPairing?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var remaining by remember { mutableIntStateOf(0) }
        val retryFocus = remember { FocusRequester() }
        LaunchedEffect(attempt) {
            val previous = pairing?.secret
            error = null
            pairing = null
            try {
                val saved = session.load()
                if (saved.isNotEmpty()) {
                    if (DiploSite.validate(saved)) {
                        openTv()
                        return@LaunchedEffect
                    }
                    session.clear()
                }
                val result = DiploSite.startPairing(previous)
                val code = result.getString("code")
                val secret = result.getString("deviceSecret")
                require(DiploSite.codeFormat.matches(code) && secret.matches(Regex("[a-f0-9]{64}")))
                val seconds = result.getInt("expiresIn")
                require(seconds in 1..900)
                val pending = TvPairing(code, secret, SystemClock.elapsedRealtime() + seconds * 1000L)
                pairing = pending
                var interval = result.optInt("interval", 15).coerceIn(15, 60) * 1000L
                while (SystemClock.elapsedRealtime() < pending.until) {
                    val reply = try {
                        DiploSite.pollPairing(secret)
                    } catch (failure: DiploSite.HttpFailure) {
                        if (failure.status == 404 || failure.status == 410) break
                        if (failure.status != 429) throw failure
                        interval = (interval + 15_000L).coerceAtMost(60_000L)
                        delay(interval)
                        continue
                    }
                    when (reply.optString("status")) {
                        "authorized" -> {
                            val token = reply.getString("token")
                            require(token.matches(Regex("[a-f0-9]{64}")))
                            session.save(token)
                            openTv()
                            return@LaunchedEffect
                        }
                        "rejected" -> {
                            pairing = null
                            error = "A conexão foi recusada no celular."
                            return@LaunchedEffect
                        }
                    }
                    delay(minOf(interval, (pending.until - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
                }
                attempt++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (failure is DiploSite.HttpFailure) failure.message
                    else "Confira a conexão com o DiploCine e tente de novo."
            }
        }
        LaunchedEffect(pairing) {
            val pending = pairing ?: return@LaunchedEffect
            while (true) {
                remaining = ((pending.until - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                if (remaining == 0) break
                delay(1000)
            }
        }

        DiploScaffold {
            Header("Conectar à TV", "No celular logado, abra Dispositivos para autorizar a TV.")
            val current = pairing
            if (error != null) {
                Text(error.orEmpty(), color = SoftText, fontSize = 17.sp)
                StageButton("Tentar novamente", { attempt++ }, Modifier.focusRequester(retryFocus),
                    icon = OwnTVIcon.LIVE_TV, height = 48.dp)
                StageButton("Abrir biblioteca local", ::openTv, height = 48.dp)
                LaunchedEffect(error) { retryFocus.requestFocus() }
            } else if (current != null) {
                val codeBitmap = remember(current.code) { qr(DiploSite.devicePage(current.code)) }
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(codeBitmap.asImageBitmap(), "QR para vincular a TV", Modifier.size(155.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(current.code.chunked(4).joinToString("-"), color = Color.White,
                            fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text(String.format(Locale.ROOT, "Válido por %02d:%02d", remaining / 60, remaining % 60),
                            color = SoftText, fontSize = 16.sp)
                        StageButton("Gerar outro código", { attempt++ }, height = 44.dp)
                    }
                }
            } else {
                Text("Preparando o código da TV…", color = SoftText, fontSize = 17.sp)
            }
            StageButton("Escolher outro modo", { selected = "" }, height = 44.dp)
        }
    }

    private data class TvPairing(val code: String, val secret: String, val until: Long)
}

private val SoftText = Color(0xFFB9B6BE)

@Composable
private fun ModeScreen(tv: Boolean, openTv: () -> Unit, openPhone: () -> Unit) {
    val preferred = remember { FocusRequester() }
    DiploScaffold {
        Header("Como você vai assistir?", "Escolha o modo deste aparelho.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 535.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StageButton("Modo TV", openTv, Modifier.weight(1f).then(if (tv) Modifier.focusRequester(preferred) else Modifier),
                        icon = OwnTVIcon.LIVE_TV, height = 64.dp, textSize = 24)
                    StageButton("Modo celular", openPhone, Modifier.weight(1f).then(if (!tv) Modifier.focusRequester(preferred) else Modifier),
                        icon = OwnTVIcon.PHONE, height = 64.dp, textSize = 24)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StageButton("Modo celular", openPhone, Modifier.fillMaxWidth().then(if (!tv) Modifier.focusRequester(preferred) else Modifier),
                        icon = OwnTVIcon.PHONE, height = 56.dp)
                    StageButton("Modo TV", openTv, Modifier.fillMaxWidth().then(if (tv) Modifier.focusRequester(preferred) else Modifier),
                        icon = OwnTVIcon.LIVE_TV, height = 56.dp)
                }
            }
        }
        LaunchedEffect(Unit) { if (tv) preferred.requestFocus() }
    }
}

@Composable
private fun DiploScaffold(content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF290A10), Color(0xFF050505), Color(0xFF050505)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.diplo_icon), null, Modifier.size(39.dp))
                Text("DIPLOCINE", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
            content()
        }
    }
}

@Composable
private fun Header(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text(description, color = SoftText, fontSize = 16.sp)
    }
}

private fun qr(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
    val pixels = IntArray(320 * 320) { index ->
        if (matrix[index % 320, index / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, 320, 320, Bitmap.Config.ARGB_8888)
}
