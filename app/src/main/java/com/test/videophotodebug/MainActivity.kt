package com.test.videophotodebug

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SingleScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleScreen(vm: ScreenVM = viewModel()) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current

    // --- Permissions ---
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

    // --- Gallery picker (video/*) ---
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.onVideoSelected(uri) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Troubleshooter") }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()), // 👈 added scroll
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Camera card
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Live Video", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    if (granted) {
                        CameraPreview(
                            lensFacing = vm.lensFacing,
                            onBind = { provider, previewView ->
                                vm.bindCamera(ctx, provider, previewView)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { vm.toggleLens(ctx) }) { Text("Switch") }

                            if (!vm.isRecording) {
                                Button(onClick = { vm.startRecording(ctx) }) { Text("Record") }
                            } else {
                                Button(onClick = { vm.stopRecording() }) { Text("Stop") }
                            }

                            OutlinedButton(onClick = { pickVideo.launch("video/*") }) {
                                Text("Upload")
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(200.dp)
                                .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("Grant camera, mic, and media permissions") }
                    }
                }
            }

            // Selected clip preview (if any)
            vm.selectedVideo?.let { uri ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Selected Clip", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        VideoPlayer(uri = uri)
                    }
                }
            }

            var prompt by remember { mutableStateOf(TextFieldValue("")) }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Describe the issue… (e.g., 'car won’t start, clicks when key turns')") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.askGemini(ctx, prompt.text) },
                enabled = (vm.selectedVideo != null && !vm.loading),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.loading) "Analyzing…" else "Ask Gemini") }

            if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
            if (vm.answer.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Result", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(vm.answer)
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
    var lensFacing: Int by mutableStateOf(CameraSelector.LENS_FACING_BACK)
        private set

    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    var isRecording: Boolean by mutableStateOf(false); private set
    var selectedVideo: Uri? by mutableStateOf(null); private set

    var loading by mutableStateOf(false); private set
    var error: String? by mutableStateOf(null); private set
    var answer by mutableStateOf("")

    fun toggleLens(ctx: Context) {
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        // Re-bind will happen via LaunchedEffect in CameraPreview
    }

    suspend fun bindCamera(ctx: Context, provider: ProcessCameraProvider, previewView: PreviewView) {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!)
        try {
            provider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.bindToLifecycle(ctx as ComponentActivity, selector, preview, videoCapture)
        } catch (_: Throwable) {
            // preview already bound — ignore; videoCapture may still be valid
        }
    }

    fun askGeminiTextOnly(prompt: String) {
        if (prompt.isBlank()) {
            error = "Enter a prompt."
            return
        }
        loading = true; error = null; answer = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = GeminiRest.askGeminiTextOnly(prompt)
                answer = text
            } catch (t: Throwable) {
                error = "Analysis failed: ${t.message}"
            } finally {
                loading = false
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun startRecording(ctx: Context) {
        val vc = videoCapture ?: return
        val name = "AI_Troubleshoot_${System.currentTimeMillis()}"
        val content = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoPhotoDebug")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(content).build()

        recording = vc.output
            .prepareRecording(ctx, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> isRecording = true
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        if (event.hasError()) {
                            error = "Recording failed: ${event.error}"
                        } else {
                            selectedVideo = event.outputResults.outputUri
                        }
                    }
                }
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

    fun askGemini(ctx: Context, prompt: String) {
        val uri = selectedVideo ?: run {
            error = "Select or record a video first."
            return
        }
        loading = true; error = null; answer = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uploaded = GeminiRest.uploadVideo(ctx, uri)
                val text = GeminiRest.askGeminiWithVideo(uploaded, prompt)
                answer = text
            } catch (t: Throwable) {
                error = "Analysis failed: ${t.message}"
            } finally {
                loading = false
            }
        }
    }
}