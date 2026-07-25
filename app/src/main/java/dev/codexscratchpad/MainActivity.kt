package dev.codexscratchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.input.motionprediction.MotionEventPredictor
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val Graphite = Color(0xFF111111)
private val Surface = Color(0xFF1B1B1B)
private val Amber = Color(0xFFFF8A1E)
private val Muted = Color(0xFFAAA7A2)

private enum class Tool { PEN, ERASER, RECTANGLE, ARROW, LINE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScratchpadApp() }
    }
}

@Composable
private fun ScratchpadApp() {
    val context = LocalContext.current
    val pairingStore = remember { PairingStore(context.applicationContext) }
    var endpoint by remember { mutableStateOf(pairingStore.endpoint) }
    var token by remember { mutableStateOf(pairingStore.token) }
    var caption by remember { mutableStateOf("") }
    var pushStatus by remember { mutableStateOf<String?>(null) }
    var canvas by remember { mutableStateOf<ScratchpadView?>(null) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    val discovery = remember { LocalBridgeDiscovery(context.applicationContext) }
    val scanner = remember { GmsBarcodeScanning.getClient(context) }

    fun addImage(bitmap: Bitmap?) {
        if (bitmap == null) { pushStatus = "Could not load that image"; return }
        canvas?.setBackgroundImage(bitmap)
        pushStatus = "Image ready to annotate"
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { selected -> thread {
            val bitmap = context.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it) }
            (context as? ComponentActivity)?.runOnUiThread { addImage(bitmap) }
        } }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> addImage(bitmap) }

    DisposableEffect(discovery) {
        discovery.start { discovered -> endpoint = discovered }
        onDispose { discovery.stop() }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Graphite, surface = Surface)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Graphite) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Text("</>", color = Amber, fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Codex Scratchpad", color = Color.White, fontSize = 21.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (endpoint == null) "○ Looking…" else "● Connected",
                        color = if (endpoint == null) Muted else Color(0xFF6FDB91), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                    AndroidView(
                        factory = { ScratchpadView(it).also { view -> canvas = view } },
                        update = { it.tool = tool },
                        modifier = Modifier.fillMaxSize()
                    )
                    Text("Pinch to zoom · two fingers to pan · S Pen pressure enabled",
                        color = Color(0xFF767676), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
                ToolRow(
                    labels = listOf("Pen" to Tool.PEN, "Eraser" to Tool.ERASER, "Rect" to Tool.RECTANGLE, "Arrow" to Tool.ARROW, "Line" to Tool.LINE),
                    selected = tool, onSelect = { tool = it }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { cameraLauncher.launch(null) }) { Text("Camera", color = Muted, fontSize = 12.sp) }
                    TextButton(onClick = { galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Gallery", color = Muted, fontSize = 12.sp) }
                    TextButton(onClick = {
                        scanner.startScan().addOnSuccessListener { barcode ->
                            val pair = Pairing.parse(barcode.rawValue)
                            if (pair == null) pushStatus = "That QR code is not a Scratchpad pairing code"
                            else {
                                endpoint = pair.endpoint; token = pair.token; pairingStore.save(pair)
                                pushStatus = "Paired with Codex Scratchpad"
                            }
                        }.addOnFailureListener { pushStatus = "QR scan cancelled or unavailable" }
                    }) { Text("Pair QR", color = Amber, fontSize = 12.sp) }
                    TextButton(onClick = { canvas?.undo() }) { Text("Undo", color = Muted, fontSize = 12.sp) }
                    TextButton(onClick = { canvas?.clear() }) { Text("Clear", color = Muted, fontSize = 12.sp) }
                }
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it },
                    label = { Text("What should Codex do with this?") },
                    placeholder = { Text("e.g. Turn this into a Mermaid diagram") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber, focusedLabelColor = Amber)
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val png = canvas?.png(); val bridge = endpoint
                    if (png == null || png.isEmpty()) { pushStatus = "Draw or annotate something first"; return@Button }
                    if (bridge == null) { pushStatus = "Use Pair QR if auto-discovery cannot find your Mac"; return@Button }
                    pushStatus = "Pushing to Codex…"
                    thread {
                        val result = pushScribble(bridge, token, png, caption)
                        (context as? ComponentActivity)?.runOnUiThread { pushStatus = result }
                    }
                }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)) {
                    Text("⇧  Push to Codex", fontSize = 18.sp)
                }
                Text(pushStatus ?: "Use Camera or Gallery to annotate an image; Pair QR is a Wi-Fi fallback.",
                    color = if (pushStatus?.startsWith("Pushed") == true) Color(0xFF9BD96C) else Muted,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

@Composable
private fun ToolRow(labels: List<Pair<String, Tool>>, selected: Tool, onSelect: (Tool) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEach { (label, item) -> TextButton(onClick = { onSelect(item) }) {
            Text(label, color = if (item == selected) Amber else Muted, fontSize = 12.sp)
        } }
    }
}

private data class Pairing(val endpoint: String, val token: String?) {
    companion object {
        fun parse(value: String?): Pairing? = runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme != "codex-scratchpad" || uri.host != "pair") return null
            val endpoint = uri.getQueryParameter("endpoint")?.trimEnd('/') ?: return null
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return null
            Pairing(endpoint, uri.getQueryParameter("token")?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }
}

private class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("codex_scratchpad", Context.MODE_PRIVATE)
    val endpoint: String? get() = prefs.getString("endpoint", null)
    val token: String? get() = prefs.getString("token", null)
    fun save(pair: Pairing) = prefs.edit().putString("endpoint", pair.endpoint).putString("token", pair.token).apply()
}

private fun pushScribble(endpoint: String, token: String?, png: ByteArray, caption: String): String = try {
    val body = """{"id":"${UUID.randomUUID()}","caption":${json(caption)},"png_base64":${json(Base64.getEncoder().encodeToString(png))}}"""
    val connection = (URL(endpoint.trimEnd('/') + "/v1/scribbles").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 12_000; doOutput = true
        setRequestProperty("Content-Type", "application/json")
        token?.let { setRequestProperty("X-Scratchpad-Token", it) }
    }
    connection.outputStream.use { it.write(body.toByteArray()) }
    if (connection.responseCode in 200..299) "Pushed — ask Codex to read your newest scratchpad." else "Bridge error ${connection.responseCode}"
} catch (error: Exception) { "Could not reach bridge: ${error.message}" }

private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private class LocalBridgeDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    fun start(onResolved: (String) -> Unit) {
        if (listener != null) return
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != "_codex-scratchpad._tcp.") return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        onResolved("http://$host:${resolved.port}")
                    }
                })
            }
        }
        nsd.discoverServices("_codex-scratchpad._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
    fun stop() { listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null }
}

private class ScratchpadView(context: Context) : View(context) {
    private sealed interface Mark {
        data class Ink(val samples: MutableList<Sample>, val eraser: Boolean) : Mark
        data class Shape(val startX: Float, val startY: Float, var endX: Float, var endY: Float, val tool: Tool) : Mark
    }
    private data class Sample(val x: Float, val y: Float, val width: Float)
    private val marks = mutableListOf<Mark>()
    private var active: Mark? = null
    var tool = Tool.PEN
    private var image: Bitmap? = null
    private var imageRect: RectF? = null
    private var scale = 1f; private var panX = 0f; private var panY = 0f
    private var transforming = false; private var lastFocusX = 0f; private var lastFocusY = 0f; private var lastSpan = 0f
    private var stylusUntil = 0L
    private val motionPredictor = MotionEventPredictor.newInstance(this)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        motionPredictor.record(event)
        if (isStylus) stylusUntil = event.eventTime + 750L
        if (!isStylus && event.eventTime < stylusUntil && event.pointerCount == 1) return true // basic palm rejection
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                transforming = false
                val point = toWorld(event.x, event.y)
                active = when (tool) {
                    Tool.PEN, Tool.ERASER -> Mark.Ink(mutableListOf(Sample(point.first, point.second, brushWidth(event, isStylus))), tool == Tool.ERASER)
                    else -> Mark.Shape(point.first, point.second, point.first, point.second, tool)
                }.also(marks::add)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { active = null; beginTransform(event) }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) updateTransform(event)
                else if (!transforming) {
                    val point = toWorld(event.x, event.y)
                    when (val mark = active) {
                        is Mark.Ink -> mark.samples += Sample(point.first, point.second, brushWidth(event, isStylus))
                        is Mark.Shape -> { mark.endX = point.first; mark.endY = point.second }
                        null -> Unit
                    }
                    if (isStylus && active is Mark.Ink) {
                        motionPredictor.predict()?.let { predicted -> try {
                            val next = toWorld(predicted.x, predicted.y)
                            (active as Mark.Ink).samples += Sample(next.first, next.second, brushWidth(predicted, true))
                        } finally { predicted.recycle() } }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) transforming = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = null; transforming = false }
        }
        invalidate(); return true
    }

    fun setBackgroundImage(bitmap: Bitmap) {
        image = bitmap
        val imageWidth = 1000f; val imageHeight = imageWidth * bitmap.height / bitmap.width
        imageRect = RectF(0f, 0f, imageWidth, imageHeight)
        marks.clear(); panX = 24f; panY = 24f
        if (width > 0) scale = min((width - 48f) / imageWidth, (height - 48f) / imageHeight).coerceIn(.25f, 2f)
        invalidate()
    }
    private fun brushWidth(event: MotionEvent, isStylus: Boolean): Float = if (isStylus) (2.5f + event.pressure.coerceIn(.15f, 1.4f) * 7f) else 6f
    private fun beginTransform(event: MotionEvent) { transforming = true; lastFocusX = (event.getX(0) + event.getX(1)) / 2f; lastFocusY = (event.getY(0) + event.getY(1)) / 2f; lastSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)) }
    private fun updateTransform(event: MotionEvent) {
        val x = (event.getX(0) + event.getX(1)) / 2f; val y = (event.getY(0) + event.getY(1)) / 2f; val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        if (!transforming || lastSpan == 0f) { beginTransform(event); return }
        val worldX = (lastFocusX - panX) / scale; val worldY = (lastFocusY - panY) / scale
        scale = min(4f, max(.25f, scale * span / lastSpan)); panX = x - worldX * scale; panY = y - worldY * scale
        lastFocusX = x; lastFocusY = y; lastSpan = span
    }
    private fun toWorld(x: Float, y: Float) = Pair((x - panX) / scale, (y - panY) / scale)
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawDocument(canvas) }
    private fun drawDocument(canvas: Canvas) {
        canvas.drawColor(AndroidColor.rgb(247, 242, 233)); drawGrid(canvas)
        canvas.save(); canvas.translate(panX, panY); canvas.scale(scale, scale)
        image?.let { bitmap -> imageRect?.let { canvas.drawBitmap(bitmap, null, it, paint) } }
        marks.forEach { drawMark(canvas, it) }; canvas.restore()
    }
    private fun drawGrid(canvas: Canvas) {
        val spacing = 28f * scale; val offsetX = ((panX % spacing) + spacing) % spacing; val offsetY = ((panY % spacing) + spacing) % spacing
        paint.style = Paint.Style.FILL; paint.color = AndroidColor.rgb(211, 218, 224)
        var x = offsetX; while (x < width) { var y = offsetY; while (y < height) { canvas.drawCircle(x, y, 1f, paint); y += spacing }; x += spacing }; paint.style = Paint.Style.STROKE
    }
    private fun drawMark(canvas: Canvas, mark: Mark) {
        paint.color = AndroidColor.rgb(35, 35, 35); paint.style = Paint.Style.STROKE
        when (mark) {
            is Mark.Ink -> {
                paint.color = if (mark.eraser) AndroidColor.rgb(247, 242, 233) else AndroidColor.rgb(35, 35, 35)
                mark.samples.zipWithNext().forEach { (a, b) -> paint.strokeWidth = (a.width + b.width) / 2f; canvas.drawLine(a.x, a.y, b.x, b.y, paint) }
                mark.samples.firstOrNull()?.takeIf { mark.samples.size == 1 }?.let { canvas.drawCircle(it.x, it.y, it.width / 2f, paint.apply { style = Paint.Style.FILL }); paint.style = Paint.Style.STROKE }
            }
            is Mark.Shape -> {
                paint.strokeWidth = 5f
                when (mark.tool) {
                    Tool.RECTANGLE -> canvas.drawRect(mark.startX, mark.startY, mark.endX, mark.endY, paint)
                    Tool.LINE -> canvas.drawLine(mark.startX, mark.startY, mark.endX, mark.endY, paint)
                    Tool.ARROW -> drawArrow(canvas, mark)
                    else -> Unit
                }
            }
        }
    }
    private fun drawArrow(canvas: Canvas, mark: Mark.Shape) {
        canvas.drawLine(mark.startX, mark.startY, mark.endX, mark.endY, paint)
        val angle = kotlin.math.atan2(mark.endY - mark.startY, mark.endX - mark.startX); val length = 25f
        canvas.drawLine(mark.endX, mark.endY, mark.endX - length * kotlin.math.cos(angle - .55f), mark.endY - length * kotlin.math.sin(angle - .55f), paint)
        canvas.drawLine(mark.endX, mark.endY, mark.endX - length * kotlin.math.cos(angle + .55f), mark.endY - length * kotlin.math.sin(angle + .55f), paint)
    }
    fun clear() { marks.clear(); image = null; imageRect = null; invalidate() }
    fun undo() { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) else { image = null; imageRect = null }; invalidate() }
    fun png(): ByteArray {
        if (width == 0 || height == 0) return byteArrayOf()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); Canvas(bitmap).also(::drawDocument)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    }
}
