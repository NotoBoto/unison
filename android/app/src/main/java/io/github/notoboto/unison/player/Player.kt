package io.github.notoboto.unison.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionToken
import io.github.notoboto.unison.MainActivity
import io.github.notoboto.unison.UnisonApp
import io.github.notoboto.unison.data.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.sqrt

/** Background playback: MediaSession + notification + lock screen + headset buttons. */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as UnisonApp
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("openPlayer", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, app.player.sessionPlayer, AutoLibrary(app)).setSessionActivity(open)
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(SessionArtLoader(this))).build()
        app.player.session = session
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        (application as UnisonApp).player.saveNow()
        runCatching { (application as UnisonApp).store.flush() }    // the position may only be held for a lazy write
        session?.release(); session = null
        super.onDestroy()
    }
}

/**
 * Covers for the media session (notification, system media controls, Android Auto). Live subtitles re-send the
 * metadata every sentence, and the system scaled the full-size cover and copied it through binder each time.
 * The cover is now scaled once, exactly as the system would (same limit, same arithmetic, filtered), and on
 * Android 12+ kept in shared memory so passing it on copies no pixels.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SessionArtLoader(ctx: Context) : androidx.media3.common.util.BitmapLoader {
    private val inner = androidx.media3.datasource.DataSourceBitmapLoader(ctx)
    private val maxPx = android.content.res.Resources.getSystem().let { r ->
        r.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android").takeIf { it != 0 }?.let(r::getDimensionPixelSize) ?: 0
    }
    override fun supportsMimeType(mimeType: String) = inner.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray) = prepared(inner.decodeBitmap(data))
    override fun loadBitmap(uri: Uri) = prepared(inner.loadBitmap(uri))
    private fun prepared(f: com.google.common.util.concurrent.ListenableFuture<android.graphics.Bitmap>) =
        com.google.common.util.concurrent.Futures.transform(f, ::prepare, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    private fun prepare(b: android.graphics.Bitmap): android.graphics.Bitmap {
        var r = b
        // android.media.MediaMetadata.Builder#scaleBitmap: the same limit and arithmetic, so the system then leaves it alone
        if (maxPx > 0 && (b.width > maxPx || b.height > maxPx)) {
            val s = minOf(maxPx.toFloat() / b.width, maxPx.toFloat() / b.height)
            r = android.graphics.Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        }
        return if (android.os.Build.VERSION.SDK_INT >= 31) r.asShared() else r
    }
}

private const val TICK_MS = 200L
private const val WORD_TICK_MIN_MS = 100L
private const val SAVE_MS = 5000L

data class NowPlaying(val book: Book, val n: Int, val title: String, val duration: Double)

/** App-wide player state and commands. Owns the ExoPlayer used by [PlaybackService]. */
class PlayerController(private val ctx: Context, private val app: UnisonApp, private val scope: CoroutineScope) {
    private val audioAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
    private val streams = StreamStore(java.io.File(ctx.cacheDir, "stream"))
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val exo: ExoPlayer = ExoPlayer.Builder(ctx)
        // split chapters (#parts=...) are stitched from their pieces; network files are fetched whole once
        // (the audio host ignores Range requests); downloaded chapters play from disk as before
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            PartsDataSource.Factory(WholeFileDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(ctx), streams))))
        .setAudioAttributes(audioAttrs, app.store.s.settings.otherAudio == "pause")
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(15_000)
        // the playback thread sleeps until the audio renderer can take more data instead of waking every 10 ms
        .experimentalSetDynamicSchedulingEnabled(true)
        .build()
    private var controller: MediaController? = null
    var session: MediaSession? = null
    /** What the notification / lock screen / Android Auto see: the phone player with live subtitles mixed into its metadata. */
    val sessionPlayer = SubtitlePlayer(exo)

    // ---- Google Cast: the session switches between the phone player and the Cast device ----
    private var cast: androidx.media3.cast.CastPlayer? = null
    val casting = MutableStateFlow(false)
    val active: Player get() = if (casting.value) cast ?: exo else exo
    fun initCast() {
        if (cast != null) return
        runCatching {
            val c = androidx.media3.cast.CastPlayer(com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx))
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { if (casting.value) { isPlaying.value = playing; syncShake(); if (playing) startTicker() } }
                override fun onPlaybackStateChanged(state: Int) { if (casting.value && state == Player.STATE_ENDED) onEnded() }
            })
            c.setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
                override fun onCastSessionAvailable() { toCast(c) }
                override fun onCastSessionUnavailable() { fromCast(c) }
            })
            cast = c
        }
    }
    private fun castItem(): MediaItem? {
        val np = now.value ?: return null
        val ch = np.book.chapters.find { it.n == np.n }
        return MediaItem.Builder().setMediaId("${np.book.key}:${np.n}").setUri(np.book.chUrl(np.n, "ogg")).setMimeType("audio/ogg")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch?.title ?: np.title).setArtist(np.book.meta.author).setAlbumTitle(np.book.meta.title)
                .setArtworkUri(np.book.coverUrl?.let(Uri::parse)).build()).build()
    }
    private fun toCast(c: androidx.media3.cast.CastPlayer) {
        val item = castItem() ?: run { casting.value = true; session?.player = c; return }
        val pos = exo.currentPosition; val wasPlaying = exo.isPlaying
        exo.pause()
        casting.value = true
        session?.player = c
        c.setMediaItem(item, pos); c.prepare(); c.playWhenReady = wasPlaying
    }
    private fun fromCast(c: androidx.media3.cast.CastPlayer) {
        val pos = c.currentPosition
        casting.value = false
        session?.player = sessionPlayer
        if (pos > 0) exo.seekTo(pos)
        isPlaying.value = exo.isPlaying
        if (!exo.isPlaying) stopTicker() else startTicker()
    }

    val now = MutableStateFlow<NowPlaying?>(null)
    /** text of the current chapter (for live subtitles) and the sentence being spoken */
    private var curText: io.github.notoboto.unison.data.ChapterText? = null
    val sentence = MutableStateFlow("")
    private var lastSubtitleAt = 0L
    val isPlaying = MutableStateFlow(false)
    val position = MutableStateFlow(0.0)      // seconds; while playing updated at each word start (at most 1 s apart) with the screen in view
    val speed = MutableStateFlow(app.store.s.settings.speed)
    /** sleep timer: remaining ms, -1 = until end of chapter, null = off */
    val sleep = MutableStateFlow<Long?>(null)
    private var sleepJob: Job? = null
    private var tickJob: Job? = null

    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    /** skip long pauses + make quiet voices louder (settings) */
    fun applySound() {
        val s = app.store.s.settings
        exo.skipSilenceEnabled = s.skipSilence
        runCatching {
            val gain = s.voiceBoost * 100 + balanceGainMb          // millibels
            if (gain > 0) {
                if (enhancer == null) enhancer = android.media.audiofx.LoudnessEnhancer(exo.audioSessionId)
                enhancer!!.setTargetGain(gain); enhancer!!.enabled = true
            } else enhancer?.enabled = false
        }
    }

    // ---------- other apps' music (settings: otherAudio, mixBalance) ----------
    // pause: ExoPlayer takes full audio focus, music apps pause (the Android default for audiobooks)
    // duck:  we hold "transient, may duck" focus while playing, so the system / the music app turns music down, and back up on pause
    // mix:   no focus at all, both play; optionally the media volume goes down N steps and the book is boosted back by the same dB,
    //        so only the music gets quieter. Calls are caught via the audio mode (Android 12+), since without focus nothing tells us.
    private val am = ctx.getSystemService(android.media.AudioManager::class.java)
    private val prefs = ctx.getSharedPreferences("other_audio", Context.MODE_PRIVATE)
    private var appliedMode = app.store.s.settings.otherAudio
    private var appliedBalance = app.store.s.settings.mixBalance
    private var focusReq: android.media.AudioFocusRequest? = null
    private var resumeOnGain = false
    private var resumeAfterCall = false
    /** media volume steps taken away for the mix balance; persisted so a killed app still gives them back */
    private var lowered = prefs.getInt("lowered", 0)
        set(v) { field = v; prefs.edit().putInt("lowered", v).apply() }
    private var balanceGainMb = 0

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; exo.pause(); abandonFocus() }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (exo.isPlaying) { resumeOnGain = true; exo.pause() }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; exo.play() }
        }
    }

    private fun requestFocus(): Boolean {
        val m = am ?: return true
        val r = focusReq ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(focusListener).build().also { focusReq = it }
        return m.requestAudioFocus(r) != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }
    private fun abandonFocus() { resumeOnGain = false; focusReq?.let { am?.abandonAudioFocusRequest(it) } }

    /** our phone playback started / stopped */
    private fun onOwnPlaying(playing: Boolean) {
        when (app.store.s.settings.otherAudio) {
            "duck" -> if (playing) { if (!requestFocus()) exo.pause() } else if (!resumeOnGain) abandonFocus()
            "mix" -> if (playing) lowerVolume() else restoreVolume()
        }
    }

    private fun applyOtherAudio() {
        val s = app.store.s.settings
        if (s.otherAudio != appliedMode) {
            appliedMode = s.otherAudio
            if (s.otherAudio != "duck") abandonFocus()
            if (s.otherAudio != "mix") restoreVolume()
            exo.setAudioAttributes(audioAttrs, s.otherAudio == "pause")
            if (exo.isPlaying) onOwnPlaying(true)
        }
        if (s.mixBalance != appliedBalance) {
            appliedBalance = s.mixBalance
            if (lowered > 0 || exo.isPlaying) { restoreVolume(); if (exo.isPlaying && s.otherAudio == "mix") lowerVolume() }
        }
    }

    private fun outputDeviceType(): Int {
        val m = am ?: return android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (android.os.Build.VERSION.SDK_INT >= 33)
            m.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .firstOrNull()?.let { return it.type }
        val prefer = listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
        val outs = m.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return prefer.firstOrNull { it in outs } ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun lowerVolume() {
        val m = am ?: return
        val steps = app.store.s.settings.mixBalance
        if (lowered > 0 || steps <= 0) return
        val st = android.media.AudioManager.STREAM_MUSIC
        val cur = m.getStreamVolume(st)
        val floor = maxOf(1, if (android.os.Build.VERSION.SDK_INT >= 28) m.getStreamMinVolume(st) else 0)
        val n = (cur - floor).coerceIn(0, steps)
        if (n == 0) return
        var db = Float.NaN
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            val dev = outputDeviceType()
            db = m.getStreamVolumeDb(st, cur, dev) - m.getStreamVolumeDb(st, cur - n, dev)
        }
        if (db.isNaN() || db.isInfinite() || db <= 0f) db = n * 3f            // typical step of a 15-step media volume
        runCatching { m.setStreamVolume(st, cur - n, 0) }.onFailure { return }
        lowered = n
        balanceGainMb = (db.coerceAtMost(20f) * 100).toInt()
        applySound()
    }

    private fun restoreVolume() {
        if (lowered > 0) am?.let { m ->
            val st = android.media.AudioManager.STREAM_MUSIC
            runCatching { m.setStreamVolume(st, minOf(m.getStreamVolume(st) + lowered, m.getStreamMaxVolume(st)), 0) }
        }
        lowered = 0
        if (balanceGainMb != 0) { balanceGainMb = 0; applySound() }
    }

    /** mix mode holds no focus: pause for calls ourselves, continue when the call ends (Android 12+) */
    private fun watchCalls() {
        val m = am ?: return
        if (android.os.Build.VERSION.SDK_INT < 31) return
        m.addOnModeChangedListener(ctx.mainExecutor) { mode ->
            if (app.store.s.settings.otherAudio != "mix" || casting.value) return@addOnModeChangedListener
            val inCall = mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                mode == android.media.AudioManager.MODE_RINGTONE || (android.os.Build.VERSION.SDK_INT >= 33 && mode == android.media.AudioManager.MODE_CALL_SCREENING)
            if (inCall && exo.isPlaying) { resumeAfterCall = true; exo.pause() }
            else if (!inCall && mode == android.media.AudioManager.MODE_NORMAL && resumeAfterCall) { resumeAfterCall = false; exo.play() }
        }
    }

    /** start / continue when headphones or a Bluetooth audio device connects (setting) */
    fun watchHeadphones() {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        val startedAt = System.currentTimeMillis()
        val kinds = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET) +
            (if (android.os.Build.VERSION.SDK_INT >= 31) setOf(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet())
        am.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
                if (System.currentTimeMillis() - startedAt < 3000) return          // already connected at app start
                if (!app.store.s.settings.autoStartHeadphones || isPlaying.value) return
                if (added.any { it.isSink && it.type in kinds }) scope.launch { delay(1200); toggleOrResume() }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    init {
        exo.playbackParameters = exo.playbackParameters.withSpeed(speed.value)
        restoreVolume()                    // volume left lowered by a mix session the app did not get to finish
        watchCalls()
        // the big widget skips sentence updates while the screen is off: catch up when it comes on
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(ctx, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = Widgets.onScreenOn(ctx)
            }, android.content.IntentFilter(Intent.ACTION_SCREEN_ON), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // only when the settings change: the state also changes every few seconds while playing (position, statistics)
        scope.launch { app.store.state.map { it.settings }.distinctUntilChanged().collect { applySound(); applyOtherAudio() } }
        // the open chapter of an imported book was text-only; once its voicing finishes, load the audio right away
        scope.launch {
            app.store.state.distinctUntilChanged { a, b -> a.downloaded === b.downloaded }.collect { st ->
                val np = now.value ?: return@collect
                if (!io.github.notoboto.unison.data.isTextSource(np.book.source.url) || exo.mediaItemCount > 0 || casting.value) return@collect
                if (st.downloaded[np.book.key]?.containsKey(np.n) != true) return@collect
                open(app.repo.book(np.book.key) ?: np.book, np.n, autoplay = false)
            }
        }
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                syncShake()
                Widgets.update(ctx)
                if (!playing || !casting.value) onOwnPlaying(playing)
                if (casting.value) return
                if (playing) startTicker() else { stopTicker(); saveNow(); position.value = exo.currentPosition / 1000.0; pausedAt = System.currentTimeMillis() }
            }
            // a seek from the notification, lock screen, headset or car: the paused player has no ticker to notice it, and
            // a ticker sleeping until the next sentence must look again
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (casting.value) return
                if (tickJob?.isActive == true) startTicker()
                else { position.value = exo.currentPosition / 1000.0; refreshSubtitle() }
            }
            override fun onPlaybackStateChanged(state: Int) { if (!casting.value && state == Player.STATE_ENDED) onEnded() }
        })
        scope.launch {
            runCatching {
                controller = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync().await()
            }
        }
    }

    /**
     * Whether the app's screen is in view (MainActivity started). Only then does the position need updating at each word
     * start ([untilNextWord]); with the screen off or another app in front the ticker wakes up only when the spoken sentence changes
     * (live subtitles, big widget) and every [SAVE_MS] to save the position and listening time.
     */
    private var uiVisible = false
    fun setUiVisible(v: Boolean) {
        if (uiVisible == v) return
        uiVisible = v
        if (v) { position.value = active.currentPosition / 1000.0; if (tickJob?.isActive == true) startTicker() }
    }

    /** start of listening time not yet added to the statistics (0 = not playing) */
    private var statFrom = 0L
    private fun addStat(t: Long) {
        if (statFrom > 0) now.value?.let { app.store.addListened(it.book.key, t - statFrom) }
        statFrom = t
    }

    /** Paused phone playback: nothing moves, so no ticking (it used to go on 5 times a second until the app was closed,
     *  counting the paused time as listened). Seeks are caught by onPositionDiscontinuity / [seekTo]. */
    private fun stopTicker() {
        if (tickJob?.isActive == true) { addStat(System.currentTimeMillis()); statFrom = 0 }
        tickJob?.cancel(); tickJob = null
    }

    private var subtitleJob: Job? = null
    /** one-off subtitle update for a paused player (seek, a chapter opened without playing) */
    private fun refreshSubtitle() {
        if (tickJob?.isActive == true) return
        subtitleJob?.cancel()
        subtitleJob = scope.launch { while (isActive) { delay(updateSubtitle() ?: break) } }
    }

    private fun startTicker() {
        tickJob?.cancel(); subtitleJob?.cancel()
        tickJob = scope.launch {
            var lastSave = System.currentTimeMillis()
            if (statFrom == 0L) statFrom = lastSave
            while (isActive) {
                position.value = active.currentPosition / 1000.0
                val retryIn = updateSubtitle()
                val t = System.currentTimeMillis()
                if (t - lastSave >= SAVE_MS) {
                    lastSave = t
                    saveNow(lazy = true)
                    addStat(t)
                }
                delay(if (uiVisible) minOf(untilNextWord(), retryIn ?: Long.MAX_VALUE, SAVE_MS - (t - lastSave)).coerceAtLeast(WORD_TICK_MIN_MS)
                      else minOf(untilNextSentence(), retryIn ?: Long.MAX_VALUE, SAVE_MS - (t - lastSave)).coerceAtLeast(TICK_MS))
            }
        }
    }

    /**
     * Screen in view: ms until the next word starts, so the reader's highlight moves on time (it used to lag 0-200 ms),
     * never more than 1 s of playback (times, sliders, mini player) nor 1 s of clock. No word timings (text still
     * loading, an audiobook without text) or casting: every [TICK_MS], as before.
     */
    private fun untilNextWord(): Long {
        val t = curText
        if (t == null || t.timeline.isEmpty() || casting.value) return TICK_MS
        val pos = position.value
        val next = t.timeline.getOrNull(t.wordAt(pos) + 1)?.first ?: Double.MAX_VALUE
        val sp = active.playbackParameters.speed.coerceAtLeast(0.1f)
        // wordAt counts a word from 50 ms before its time; wake 20 ms past that so an early wake-up does not miss it
        var media = minOf(next - 0.03 - pos, 1.0)
        // the times on screen ("2:17", "−6:29") change at whole seconds of the position and of the time left: when the
        // next word comes more than 0.3 s after such a change, wake for the change, so a time never shows up late by more
        val left = (now.value?.duration ?: 0.0) - pos
        for (b in doubleArrayOf(floor(pos) + 1 - pos, (left - floor(left)).takeIf { it > 0 } ?: 1.0)) {
            val at = b + 0.02
            if (at < media && media - at > 0.3 * sp) media = at
        }
        var wait = media * 1000 / sp
        // skipped silence makes media time run faster than the clock: aim early across a pause and look again
        if (exo.skipSilenceEnabled && wait > 250) wait *= 0.6
        return wait.toLong().coerceIn(WORD_TICK_MIN_MS, 1000L)
    }

    /** ms of playback until the next sentence starts (the subtitle changes then), at most [SAVE_MS] */
    private fun untilNextSentence(): Long {
        val t = curText ?: return SAVE_MS
        val pos = position.value
        val wi = t.wordAt(pos)
        val next: Double = if (wi < 0) t.timeline.firstOrNull()?.first ?: return SAVE_MS else {
            val (_, pi, ti) = t.timeline[wi]
            val para = t.paras[pi]
            var b = ti
            while (b < para.size - 1 && !io.github.notoboto.unison.ui.sentenceEnds(para[b].word)) b++
            var k = wi + 1
            while (k < t.timeline.size && t.timeline[k].second == pi && t.timeline[k].third <= b) k++
            t.timeline.getOrNull(k)?.first ?: return SAVE_MS
        }
        val sp = active.playbackParameters.speed.coerceAtLeast(0.1f)
        // wordAt counts a word from 50 ms before its time. Skipped silence makes media time run faster than the clock:
        // aim early then and look again (the remaining wait shrinks each time).
        val wait = ((next - 0.05 - pos) * 1000 / sp * (if (exo.skipSilenceEnabled) 0.6 else 1.0)).toLong()
        return wait.coerceIn(TICK_MS, SAVE_MS)
    }

    /** Live subtitles: the sentence being spoken goes into the notification ("artist" line) and the big widget. */
    /** Returns in how many ms to look again when the throttle held a change back, else null. */
    private fun updateSubtitle(): Long? {
        val t = curText
        val s = if (t == null) "" else io.github.notoboto.unison.ui.sentenceAt(t, position.value)
        val changed = s != sentence.value
        sentence.value = s
        val line = s.take(160).takeIf { it.isNotBlank() && app.store.s.settings.liveSubtitles && !casting.value }
        if (line == sessionPlayer.line && !changed) return null
        // throttled so the notification is not rebuilt several times a second; a skipped change is picked up on a later tick
        val now0 = System.currentTimeMillis()
        if (line != null && now0 - lastSubtitleAt < 1200) return 1200 - (now0 - lastSubtitleAt)
        lastSubtitleAt = now0
        sessionPlayer.setLine(line)
        Widgets.update(ctx, sentenceOnly = true)
        return null
    }

    fun saveNow(lazy: Boolean = false) {
        val np = now.value ?: return
        val p = active.currentPosition / 1000.0
        if (active.mediaItemCount > 0) app.store.setPos(np.book.key, np.n, p, np.duration, lazy)
    }

    /** Build the media item for a chapter and make it "current" (start position in seconds). Shared with Android Auto. */
    fun resolve(b: Book, n: Int): Pair<MediaItem, Double>? {
        val ch = b.chapters.find { it.n == n } ?: return null
        saveNow()
        val pr = app.store.progress(b.key, n)
        val start = if (pr.done || pr.pos > ch.duration - 3) 0.0 else pr.pos
        val item = MediaItem.Builder().setMediaId("${b.key}:$n").setUri(app.repo.audioUri(b, n))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title).setArtist(b.meta.author).setAlbumTitle(b.meta.title)
                .setArtworkUri(b.coverUrl?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false).build()).build()
        now.value = NowPlaying(b, n, ch.title, ch.duration)
        applyBookSpeed(b.key)
        curText = null; sentence.value = ""; sessionPlayer.setLine(null)
        scope.launch { curText = runCatching { app.repo.chapterText(b, n) }.getOrNull(); refreshSubtitle() }
        position.value = start
        app.store.opened(b.key, n)
        app.downloads.autoAhead(b, n)
        Widgets.update(ctx)
        return item to start
    }

    /** Whether chapter [n] has audio: web books always, text books only once voiced, imported audiobooks always. */
    fun hasAudio(b: Book, n: Int): Boolean =
        !io.github.notoboto.unison.data.isTextSource(b.source.url) || app.store.isDownloaded(b.key, n) ||
            b.chapters.find { it.n == n }?.studio != null ||       // voiced in Unison Studio (§12)
            (b.source.url == io.github.notoboto.unison.data.LOCAL_URL &&
                audioBooks.getOrPut(b.meta.id) { io.github.notoboto.unison.data.Local.isAudio(ctx, b.meta.id) })
    private val audioBooks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()   // imported audiobooks never turn into text

    /** Load a chapter; continue from saved position unless it was finished. */
    fun open(b: Book, n: Int, autoplay: Boolean, switchToStudio: Boolean = true) {
        if (now.value?.book?.key == b.key && now.value?.n == n && exo.mediaItemCount > 0) { if (autoplay) play(); return }
        // voiced by the phone, voiced in Unison Studio since: switch now, at the same word (SOURCES_DESIGN.md §12)
        if (switchToStudio && app.repo.studioWaiting(b, n)) {
            scope.launch {
                app.repo.switchStarted(b, n)
                open(app.repo.book(b.key) ?: b, n, autoplay, switchToStudio = false)
            }
            return
        }
        if (!hasAudio(b, n)) {
            resolve(b, n); exo.clearMediaItems(); return       // text only until the chapter is voiced
        }
        val (item, start) = resolve(b, n) ?: return
        exo.setMediaItem(item, (start * 1000).toLong())
        exo.prepare()
        if (casting.value) cast?.let { c -> castItem()?.let { c.setMediaItem(it, (start * 1000).toLong()); c.prepare() } }
        if (autoplay) play() else active.playWhenReady = false
    }

    /** Play/pause; if nothing is loaded (e.g. after a restart), continue the last chapter from history. */
    fun toggleOrResume() {
        if (now.value != null && exo.mediaItemCount > 0) { toggle(); return }
        val h = app.store.s.history.firstOrNull() ?: return
        val b = app.repo.book(h.book) ?: return
        open(b, h.n, autoplay = true)
    }

    private var pausedAt = 0L
    fun play() {
        val p = active
        if (p.mediaItemCount == 0) {
            now.value?.let { np -> if (io.github.notoboto.unison.data.isTextSource(np.book.source.url)) {
                if (!hasAudio(app.repo.book(np.book.key) ?: np.book, np.n)) {
                    android.widget.Toast.makeText(ctx, "Глава ещё не озвучена: нажмите ⤓ у главы", android.widget.Toast.LENGTH_LONG).show(); return }
                open(app.repo.book(np.book.key) ?: np.book, np.n, autoplay = true); return
            } }
            return
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        val s = app.store.s.settings
        if (s.autoRewindAfterMin > 0 && pausedAt > 0 && System.currentTimeMillis() - pausedAt > s.autoRewindAfterMin * 60_000L)
            p.seekTo((p.currentPosition - s.autoRewindSec * 1000L).coerceAtLeast(0))
        pausedAt = 0
        if (s.sleepScheduleOn && sleep.value == null && inWindow(s.sleepFrom, s.sleepTo)) startSleep(s.sleepScheduleMin * 60_000L)
        p.play()
    }
    private fun inWindow(from: Int, to: Int): Boolean {
        val c = java.util.Calendar.getInstance(); val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return if (from <= to) m in from until to else m >= from || m < to
    }
    fun pause() { active.pause(); if (casting.value) pausedAt = System.currentTimeMillis() }
    fun toggle() = if (active.isPlaying) pause() else play()
    /** Hide the mini player: save the position, stop and unload the chapter. Play from history/widget resumes it. */
    fun close() {
        saveNow(); active.pause()
        if (!casting.value) { exo.stop(); exo.clearMediaItems() }
        stopTicker(); cancelSleep()
        now.value = null; curText = null; sentence.value = ""; sessionPlayer.setLine(null)
        Widgets.update(ctx)
    }
    fun seekTo(sec: Double) { active.seekTo((sec * 1000).toLong()); position.value = sec; refreshSubtitle() }
    fun skip(sec: Int) = seekTo((active.currentPosition / 1000.0 + sec).coerceIn(0.0, now.value?.duration ?: 0.0))
    fun setSpeed(v: Float) {
        speed.value = v; exo.playbackParameters = exo.playbackParameters.withSpeed(v)
        // the ticker's wait was worked out for the old speed
        if (uiVisible && tickJob?.isActive == true) startTicker()
        val bk = now.value?.book?.key
        if (app.store.s.settings.perBookSpeed && bk != null) app.store.settings { it.copy(bookSpeed = it.bookSpeed + (bk to v)) }
        else app.store.settings { it.copy(speed = v) }
    }
    private fun applyBookSpeed(key: String) {
        val s = app.store.s.settings
        val v = if (s.perBookSpeed) s.bookSpeed[key] ?: s.speed else s.speed
        speed.value = v; exo.playbackParameters = exo.playbackParameters.withSpeed(v)
    }
    fun neighbour(d: Int): Int? { val np = now.value ?: return null; val l = np.book.chapters; val i = l.indexOfFirst { it.n == np.n }; return l.getOrNull(i + d)?.n }

    private fun onEnded() {
        val np = now.value ?: return
        app.store.setDone(np.book.key, listOf(np.n), true)
        app.store.setPos(np.book.key, np.n, 0.0, np.duration)
        if (app.store.s.settings.autoDelete && app.store.isDownloaded(np.book.key, np.n)) app.downloads.delete(np.book, listOf(np.n))
        if (sleep.value == -1L) { cancelSleep(); return }          // "until end of chapter"
        val next = neighbour(1) ?: return
        val book = app.repo.book(np.book.key) ?: np.book
        open(book, next, autoplay = true)
        // the chapter just listened to, voiced by the phone, gives way to its Studio version (§12)
        if (io.github.notoboto.unison.data.isPluginSource(book.source.url))
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { app.repo.switchToStudio(book.source.url, book.meta.id) }
    }

    // ---------- sleep timer ----------
    private var shake: SensorEventListener? = null
    fun startSleep(ms: Long) {
        sleepJob?.cancel(); sleep.value = ms; shakeMs = ms; stopShake(); syncShake()
        if (ms < 0) return
        sleepJob = scope.launch {
            while (isActive && (sleep.value ?: 0) > 0) {
                // paused: the timer stands still, so wait for playback instead of looking every second
                if (!isPlaying.value) isPlaying.first { it }
                delay(1000); if (exo.isPlaying) sleep.value = (sleep.value ?: 0) - 1000
                val left = sleep.value ?: 0
                if (left in 1..10_000) exo.volume = left / 10_000f       // fade out over the last 10 s
            }
            if (isActive) { exo.pause(); exo.volume = 1f; cancelSleep() }
        }
    }
    fun cancelSleep() { sleepJob?.cancel(); sleep.value = null; shakeMs = 0; exo.volume = 1f; stopShake() }

    /** The accelerometer runs only while a shake can extend the timer: a minutes timer during playback.
     *  «Until end of chapter» (a shake never did anything there) and a paused player leave it off. */
    private var shakeMs = 0L
    private fun syncShake() {
        val want = shakeMs > 0 && (sleep.value ?: 0) > 0 && isPlaying.value
        if (want && shake == null) listenShake(shakeMs) else if (!want) stopShake()
    }

    private fun listenShake(ms: Long) {
        stopShake()
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        var lastShake = 0L
        shake = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                if (g > 2.3f && System.currentTimeMillis() - lastShake > 1500 && (sleep.value ?: 0) > 0) {
                    lastShake = System.currentTimeMillis()
                    sleep.value = ms; exo.volume = 1f          // shake = restart the timer
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(shake, acc, SensorManager.SENSOR_DELAY_UI)
    }
    private fun stopShake() { shake?.let { ctx.getSystemService(SensorManager::class.java)?.unregisterListener(it) }; shake = null }

    val connected: StateFlow<Boolean> get() = MutableStateFlow(controller != null)
}

/** Android Auto / media browsers: library books -> chapters. */
class AutoLibrary(private val app: UnisonApp) : MediaLibrarySession.Callback {
    private fun browsable(id: String, title: String, sub: String?, art: String?) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art?.let(Uri::parse))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).build()).build()

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?) =
        com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.LibraryResult.ofItem(browsable("root", "Unison", null, null), params))

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
                               params: MediaLibraryService.LibraryParams?): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
        val items: List<MediaItem> = if (parentId == "root") {
            val cont = app.store.s.history.firstOrNull()?.let { h -> app.repo.book(h.book)?.let { b ->
                val ch = b.chapters.find { it.n == h.n }
                MediaItem.Builder().setMediaId("${b.key}:${h.n}").setMediaMetadata(MediaMetadata.Builder().setTitle("Продолжить: ${ch?.title ?: "глава " + h.n}")
                    .setSubtitle(b.shortTitle).setArtworkUri(b.coverSmUrl?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false).build()).build()
            } }
            listOfNotNull(cont) + app.repo.libraryBooks().map { b -> browsable("book:${b.key}", b.meta.title, b.meta.author, b.coverSmUrl) }
        } else if (parentId.startsWith("book:")) {
            val b = app.repo.book(parentId.removePrefix("book:"))
            b?.chapters?.map { ch ->
                val done = app.store.progress(b.key, ch.n).done
                MediaItem.Builder().setMediaId("${b.key}:${ch.n}").setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title)
                    .setSubtitle(if (done) "прослушано" else io.github.notoboto.unison.ui.fmt(ch.duration)).setArtworkUri(b.coverSmUrl?.let(Uri::parse))
                    .setIsPlayable(true).setIsBrowsable(false).build()).build()
            }.orEmpty()
        } else emptyList()
        return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.LibraryResult.ofItemList(items, params))
    }

    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int,
                                 startPositionMs: Long): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val id = mediaItems.firstOrNull()?.mediaId.orEmpty()
        val key = id.substringBeforeLast(':'); val n = id.substringAfterLast(':').toIntOrNull()
        val b = app.repo.book(key)
        val r = if (b != null && n != null) app.player.resolve(b, n) else null
        return com.google.common.util.concurrent.Futures.immediateFuture(
            if (r != null) MediaSession.MediaItemsWithStartPosition(listOf(r.first), 0, (r.second * 1000).toLong())
            else MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
    }
}

private fun PlayerController.noop() = Unit

/**
 * Live subtitles for the notification, lock screen and car: the sentence being spoken replaces the "artist" line.
 * The audio files carry their own artist/title tags, which ExoPlayer puts above the media item's metadata, so changing the
 * item (the old approach) never showed up; here the metadata the session reads is overridden instead.
 */
class SubtitlePlayer(player: Player) : androidx.media3.common.ForwardingPlayer(player) {
    var line: String? = null; private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        // the wrapped player reports its own (tag) metadata when a file loads: re-announce ours right after it
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { main.post { announce() } }
        })
    }

    override fun addListener(listener: Player.Listener) { super.addListener(listener); listeners += listener }
    override fun removeListener(listener: Player.Listener) { super.removeListener(listener); listeners -= listener }

    override fun getMediaMetadata(): MediaMetadata {
        val b = super.getMediaMetadata().buildUpon()
        // our own title / book / author win over whatever tags the audio file has
        currentMediaItem?.mediaMetadata?.let { m ->
            m.title?.let(b::setTitle); m.artist?.let(b::setArtist); m.albumTitle?.let(b::setAlbumTitle); m.artworkUri?.let(b::setArtworkUri)
        }
        line?.let { b.setArtist(it).setSubtitle(it) }
        return b.build()
    }

    fun setLine(text: String?) {
        if (text == line) return
        line = text
        announce()
    }

    private fun announce() {
        val m = mediaMetadata
        val ev = Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        listeners.forEach { runCatching { it.onMediaMetadataChanged(m); it.onEvents(this, ev) } }
    }
}
