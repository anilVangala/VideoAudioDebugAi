package com.test.videophotodebug

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SingleScreen() } }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleScreen(vm: ScreenVM = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity

    // ---- Permissions ----
    val perms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()

    var granted by remember { mutableStateOf(false) }
    val reqPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map -> granted = map.values.all { it } }
    LaunchedEffect(Unit) { reqPerms.launch(perms) }

    // ---- Wire the gallery picker into the VM ----
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let(vm::onVideoSelected) }
    LaunchedEffect(Unit) {
        // hand the launcher to the VM once
        vm.pickVideoLauncher = pickVideo
    }

    // ---- UI ----
    Scaffold(topBar = { TopAppBar(title = { Text("AI Troubleshooter") }) }) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Camera preview card
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Live Video", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (granted) {
                        CameraPreview(
                            lensFacing = vm.lensFacing,
                            onBind = { provider, previewView -> vm.bindCamera(activity, provider, previewView) }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { vm.toggleLens(activity) }) { Text("Switch") }
                            if (!vm.isRecording) {
                                Button(onClick = { vm.startTimedRecording(activity, seconds = 8) }) { Text("Capture 8s") }
                            } else {
                                Button(onClick = { vm.stopRecording() }) { Text("Stop") }
                            }
                            OutlinedButton(
                                onClick = { vm.pickVideoLauncher.launch("video/*") },
                                enabled = !vm.isRecording
                            ) { Text("Upload") }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("Grant camera, mic, and media permissions") }
                    }
                }
            }

            // Selected/Recorded clip preview
            vm.selectedVideo?.let { uri ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Selected Clip", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        VideoPlayer(uri)
                    }
                }
            }

            // Prompt (optional, editable after voice)
            OutlinedTextField(
                value = vm.promptValue,
                onValueChange = { vm.promptValue = it },
                placeholder = { Text("Describe or say the problem…") },
                modifier = Modifier.fillMaxWidth()
            )

            // Voice input row: start voice + capture clip; stop to end early
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.startVoiceAndClip(activity, seconds = 8) },
                    enabled = granted && !vm.loading && !vm.isRecording,
                    modifier = Modifier.weight(1f)
                ) { Text(if (vm.listening) "Listening…" else "🎙️ Start Voice + Clip") }

                OutlinedButton(
                    onClick = { vm.stopVoiceAndClip() },
                    enabled = vm.listening || vm.isRecording,
                    modifier = Modifier.weight(1f)
                ) { Text("🔇 Stop") }
            }
            if (vm.partialTranscript.isNotBlank()) {
                Text("Heard: ${vm.partialTranscript}", style = MaterialTheme.typography.bodySmall)
            }

            // Manual ask buttons (video+prompt / prompt-only)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.askGeminiWithCurrent(ctx) },
                    enabled = (vm.selectedVideo != null && !vm.loading),
                    modifier = Modifier.weight(1f)
                ) { Text(if (vm.loading) "Analyzing…" else "Ask (Video + Prompt)") }

                OutlinedButton(
                    onClick = { vm.askGeminiTextOnly() },
                    enabled = (vm.promptValue.isNotBlank() && !vm.loading),
                    modifier = Modifier.weight(1f)
                ) { Text("Ask (Prompt Only)") }
            }

            if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
            if (vm.answer.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Answer", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(vm.answer)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { vm.speakAnswer(ctx) }, enabled = vm.answer.isNotBlank()) {
                                Text("🔊 Speak Answer")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    lensFacing: Int,
    onBind: suspend (ProcessCameraProvider, PreviewView) -> Unit
) {
    val ctx = LocalContext.current
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(12.dp))
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }

    // Bind preview whenever lens changes
    LaunchedEffect(lensFacing) {
        val provider = ctx.getCameraProvider()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        provider.unbindAll()
        provider.bindToLifecycle(
            ctx as ComponentActivity, selector, preview
        )
        onBind(provider, previewView)
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try { cont.resume(future.get()) }
            catch (t: Throwable) { cont.resumeWithException(t) }
        }, ContextCompat.getMainExecutor(this))
    }

@Composable
private fun VideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        onDispose { player.release() }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color.Black, RoundedCornerShape(12.dp))
    )
}

/** ViewModel: camera + selected clip + Gemini call */
class ScreenVM : ViewModel() {

    // ------- state -------
    var lensFacing: Int by mutableStateOf(CameraSelector.LENS_FACING_BACK); private set
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    var isRecording by mutableStateOf(false); private set
    var selectedVideo: Uri? by mutableStateOf(null); private set
    var loading by mutableStateOf(false); private set
    var error: String? by mutableStateOf(null); private set
    var answer by mutableStateOf(""); private set

    // ------- prompt / voice -------
    var promptValue by mutableStateOf("")
    var partialTranscript by mutableStateOf("")
    var listening by mutableStateOf(false)

    // Gallery picker launcher (set from Composable)
    lateinit var pickVideoLauncher: ManagedActivityResultLauncher<String, Uri?>

    // ---- Camera binding ----
    suspend fun bindCamera(
        ctx: ComponentActivity,
        provider: ProcessCameraProvider,
        previewView: PreviewView
    ) {
        // Build preview again (we already set surface provider in composable)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        // Build/attach recorder + video capture (once)
        if (recorder == null) {
            recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
        }
        if (videoCapture == null) {
            videoCapture = VideoCapture.withOutput(recorder!!)
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(ctx, selector, preview, videoCapture)
        } catch (_: Throwable) {
            // If already bound, ignore (no-op)
        }
    }

    fun toggleLens(ctx: Context) {
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        // Actual rebind is triggered by LaunchedEffect in CameraPreview
    }

    // ---- Fixed-duration capture (live video snippet) ----
    fun startTimedRecording(ctx: Context, seconds: Int) {
        val vc = videoCapture ?: return
        val content = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "clip_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoPhotoDebug")
        }
        val output = MediaStoreOutputOptions.Builder(
            ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(content).build()

        recording = vc.output.prepareRecording(ctx, output).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> isRecording = true
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        if (!event.hasError()) {
                            selectedVideo = event.outputResults.outputUri
                        } else error = "Recording failed: ${event.error}"
                    }
                }
            }

        // auto-stop after N seconds (no-op if user already stopped)
        viewModelScope.launch {
            delay(seconds * 1000L)
            if (isRecording) stopRecording()
        }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    fun onVideoSelected(uri: Uri) {
        selectedVideo = uri
        answer = ""
        error = null
    }

    // ---- Voice: SpeechRecognizer wrapper (push-to-talk) ----
    private var speech: SpeechRecognizer? = null

    fun startVoiceAndClip(activity: ComponentActivity, seconds: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            error = "Speech recognizer not available on this device."
            return
        }
        if (speech != null) stopVoiceAndClip()
        listening = true

        // Start clip while listening
        startTimedRecording(activity, seconds)

        speech = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(p0: Int) { listening = false }
                override fun onPartialResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { partialTranscript = it }
                }
                override fun onResults(b: Bundle) {
                    listening = false
                    val finalText = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    promptValue = finalText
                    // Wait for recording finalize if needed, then ask
                    viewModelScope.launch {
                        var waited = 0
                        while (selectedVideo == null && waited < 10_000) {
                            delay(200); waited += 200
                        }
                        askGeminiWithCurrent(activity)
                    }
                }
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }
            startListening(intent)
        }
    }

    fun stopVoiceAndClip() {
        speech?.stopListening()
        speech?.destroy()
        speech = null
        listening = false
        if (isRecording) stopRecording()
    }

    // ---- Ask Gemini (current selection) ----
    fun askGeminiWithCurrent(ctx: Context) {
        val uri = selectedVideo ?: run { error = "Record or pick a video first."; return }
        val prompt = promptValue
        loading = true; error = null; answer = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uploaded = GeminiRest.uploadVideo(ctx, uri)
                val text = GeminiRest.askGeminiWithVideo(uploaded, prompt)
                answer = text
            } catch (t: Throwable) {
                error = "Analysis failed: ${t.message}"
            } finally { loading = false }
        }
    }

    fun askGeminiTextOnly() {
        val prompt = promptValue
        if (prompt.isBlank()) { error = "Say or type a prompt."; return }
        loading = true; error = null; answer = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = GeminiRest.askGeminiTextOnly(prompt)
                answer = text
            } catch (t: Throwable) {
                error = "Analysis failed: ${t.message}"
            } finally { loading = false }
        }
    }

    // ---- Voice output: on-device TextToSpeech ----
    private var tts: TextToSpeech? = null
    fun speakAnswer(ctx: Context) {
        val text = answer.ifBlank { return }
        if (tts == null) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ans1")
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ans1")
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
        speech?.destroy()
    }
}
