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
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.delay

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
    var showTools by remember { mutableStateOf(false) }
    var showInstruction by remember { mutableStateOf(false) }
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

    LaunchedEffect(pushStatus) {
        if (pushStatus != null && pushStatus != "Sending…") {
            delay(3_000)
            pushStatus = null
        }
    }

    fun pairFromQr() {
        scanner.startScan().addOnSuccessListener { barcode ->
            val pair = Pairing.parse(barcode.rawValue)
            if (pair == null) pushStatus = "That QR code is not a Scratchpad pairing code"
            else {
                endpoint = pair.endpoint
                token = pair.token
                pairingStore.save(pair)
                pushStatus = "Paired with Codex Scratchpad"
            }
        }.addOnFailureListener { pushStatus = "QR scan cancelled or unavailable" }
    }

    fun sendToCodex() {
        val png = canvas?.png()
        val bridge = endpoint
        if (png == null || png.isEmpty()) {
            pushStatus = "Draw or annotate something first"
            return
        }
        if (bridge == null) {
            pushStatus = "Use Pair QR if auto-discovery cannot find your Mac"
            return
        }
        pushStatus = "Sending…"
        thread {
            val result = pushScribble(bridge, token, png, caption)
            (context as? ComponentActivity)?.runOnUiThread { pushStatus = result }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Graphite, surface = Surface)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Graphite) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text("</>", color = Amber, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Scratchpad", color = Color.White, fontSize = 19.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).background(if (endpoint == null) Muted else Color(0xFF56D47B), RoundedCornerShape(50)))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = ::sendToCodex, modifier = Modifier.size(42.dp)) {
                        AppIcon(AppIconType.SEND, Amber)
                    }
                    IconButton(onClick = { showTools = !showTools }, modifier = Modifier.size(42.dp)) {
                        AppIcon(AppIconType.MENU, Color.White)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                    AndroidView(
                        factory = { ScratchpadView(it).also { view -> canvas = view } },
                        update = { it.tool = tool },
                        modifier = Modifier.fillMaxSize()
                    )

                    FloatingToolRail(
                        tool = tool,
                        onTool = { tool = it; showTools = false },
                        onUndo = { canvas?.undo() },
                        onMore = { showTools = !showTools },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp)
                    )

                    if (showTools) {
                        Box(
                            modifier = Modifier.fillMaxSize().clickable { showTools = false }
                        )
                        SecondaryTools(
                            onTool = { tool = it; showTools = false },
                            onCamera = { showTools = false; cameraLauncher.launch(null) },
                            onGallery = {
                                showTools = false
                                galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onPair = { showTools = false; pairFromQr() },
                            onClear = { showTools = false; canvas?.clear() },
                            onInstruction = { showTools = false; showInstruction = true },
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 78.dp, bottom = 10.dp)
                        )
                    }

                    pushStatus?.let { message ->
                        Surface(
                            color = Color(0xE61B1B1B),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                        ) {
                            Text(
                                message,
                                color = if (message.startsWith("Pushed")) Color(0xFF9BD96C) else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInstruction) {
        AlertDialog(
            onDismissRequest = { showInstruction = false },
            title = { Text("Instruction for Codex") },
            text = {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("e.g. Turn this into a Mermaid diagram") },
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber)
                )
            },
            confirmButton = {
                TextButton(onClick = { showInstruction = false }) { Text("Done", color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { caption = ""; showInstruction = false }) { Text("Clear") }
            }
        )
    }
}

@Composable
private fun FloatingToolRail(
    tool: Tool,
    onTool: (Tool) -> Unit,
    onUndo: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xCC242424),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        modifier = modifier.width(58.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
            CompactToolButton(tool == Tool.PEN, "Pen", R.drawable.ic_edit) { onTool(Tool.PEN) }
            CompactToolButton(tool == Tool.ERASER, "Erase", R.drawable.ic_erase) { onTool(Tool.ERASER) }
            CompactToolButton(false, "Undo", R.drawable.ic_undo, onUndo)
            CompactToolButton(false, "More", R.drawable.ic_expand_more, onMore)
        }
    }
}

@Composable
private fun CompactToolButton(
    selected: Boolean,
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit
) {
    val color = if (selected) Amber else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.width(50.dp).height(48.dp)
            .background(if (selected) Color(0x22FF8A1E) else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = color, modifier = Modifier.size(23.dp))
        Text(label, color = color, fontSize = 9.sp, lineHeight = 10.sp)
    }
}

@Composable
private fun SecondaryTools(
    onTool: (Tool) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onPair: () -> Unit,
    onClear: () -> Unit,
    onInstruction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xF21B1B1B),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 12.dp,
        modifier = modifier.width(188.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            ToolMenuRow("Rectangle", { onTool(Tool.RECTANGLE) })
            ToolMenuRow("Arrow", { onTool(Tool.ARROW) })
            ToolMenuRow("Line", { onTool(Tool.LINE) })
            HorizontalDivider(color = Color(0xFF3A3A3A))
            ToolMenuRow("Camera", onCamera)
            ToolMenuRow("Gallery", onGallery)
            ToolMenuRow("Pair QR", onPair)
            ToolMenuRow("Clear", onClear)
            HorizontalDivider(color = Color(0xFF3A3A3A))
            ToolMenuRow("Add instruction", onInstruction, Amber)
        }
    }
}

@Composable
private fun ToolMenuRow(label: String, onClick: () -> Unit, color: Color = Color.White) {
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

private enum class AppIconType { SEND, MENU }

@Composable
private fun AppIcon(type: AppIconType, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val stroke = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
        when (type) {
            AppIconType.SEND -> {
                val path = Path().apply {
                    moveTo(size.width * .12f, size.height * .48f)
                    lineTo(size.width * .88f, size.height * .12f)
                    lineTo(size.width * .62f, size.height * .88f)
                    lineTo(size.width * .45f, size.height * .58f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(size.width * .45f, size.height * .58f), Offset(size.width * .88f, size.height * .12f), stroke.width, StrokeCap.Round)
            }
            AppIconType.MENU -> listOf(.28f, .5f, .72f).forEach { y -> drawCircle(color, radius = 1.8.dp.toPx(), center = Offset(size.width / 2, size.height * y)) }
        }
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
