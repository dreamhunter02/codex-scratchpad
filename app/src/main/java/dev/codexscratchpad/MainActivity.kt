package dev.codexscratchpad

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
private val CanvasCream = Color(0xFFF7F2E9)
private val Amber = Color(0xFFFF8A1E)
private val Muted = Color(0xFFAAA7A2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScratchpadApp() }
    }
}

@Composable
private fun ScratchpadApp() {
    val context = LocalContext.current
    var endpoint by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var pushStatus by remember { mutableStateOf<String?>(null) }
    var canvas by remember { mutableStateOf<ScratchpadView?>(null) }
    var erasing by remember { mutableStateOf(false) }
    val discovery = remember { LocalBridgeDiscovery(context.applicationContext) }

    DisposableEffect(discovery) {
        discovery.start { discovered ->
            endpoint = discovered
        }
        onDispose { discovery.stop() }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Graphite, surface = Surface)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Graphite) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                ) {
                    Text("</>", color = Amber, fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Codex Scratchpad", color = Color.White, fontSize = 21.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (endpoint == null) "○ Looking…" else "● Connected",
                        color = if (endpoint == null) Muted else Color(0xFF6FDB91),
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                    AndroidView(
                        factory = { ScratchpadView(it).also { view -> canvas = view } },
                        update = { it.erasing = erasing },
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        "Pinch to zoom · two fingers to pan",
                        color = Color(0xFF8A8A8A),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { erasing = false }) { Text("Pen", color = if (!erasing) Amber else Muted) }
                    TextButton(onClick = { erasing = true }) { Text("Eraser", color = if (erasing) Amber else Muted) }
                    TextButton(onClick = { canvas?.undo() }) { Text("Undo", color = Muted) }
                    TextButton(onClick = { canvas?.clear() }) { Text("Clear", color = Muted) }
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("What should Codex do with this?") },
                    placeholder = { Text("e.g. Turn this into a Mermaid diagram") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber, focusedLabelColor = Amber)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val png = canvas?.png()
                        val bridge = endpoint
                        if (png == null || png.isEmpty()) { pushStatus = "Draw something first"; return@Button }
                        if (bridge == null) { pushStatus = "Still looking for Codex Scratchpad on this Wi-Fi…"; return@Button }
                        pushStatus = "Pushing to Codex…"
                        thread {
                            val result = pushScribble(bridge, png, caption)
                            (context as? ComponentActivity)?.runOnUiThread { pushStatus = result }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)
                ) { Text("⇧  Push to Codex", fontSize = 18.sp) }
                Text(
                    pushStatus ?: "Ready — keep this phone and Mac on the same Wi-Fi.",
                    color = if (pushStatus?.startsWith("Pushed") == true) Color(0xFF9BD96C) else Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
        }
    }
}

private fun pushScribble(endpoint: String, png: ByteArray, caption: String): String = try {
    val body = """{"id":"${UUID.randomUUID()}","caption":${json(caption)},"png_base64":${json(Base64.getEncoder().encodeToString(png))}}"""
    val connection = (URL(endpoint.trimEnd('/') + "/v1/scribbles").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 12_000; doOutput = true
        setRequestProperty("Content-Type", "application/json")
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

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }
}

private class ScratchpadView(context: Context) : View(context) {
    private data class Stroke(val path: Path, val eraser: Boolean, val width: Float)
    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    var erasing = false
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var transforming = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 35, 35); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }

    init { background = ColorDrawable(android.graphics.Color.rgb(247, 242, 233)) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val width = if (isStylus) 3.5f + event.pressure * 6f else 6f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                transforming = false
                val point = toWorld(event.x, event.y)
                current = Stroke(Path().apply { moveTo(point.first, point.second) }, erasing, width).also(strokes::add)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                current = null
                beginTransform(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) updateTransform(event)
                else if (!transforming) {
                    val point = toWorld(event.x, event.y)
                    current?.path?.lineTo(point.first, point.second)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) transforming = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                transforming = false
            }
        }
        invalidate(); return true
    }

    private fun beginTransform(event: MotionEvent) {
        transforming = true
        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
        lastSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun updateTransform(event: MotionEvent) {
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        if (!transforming || lastSpan == 0f) { beginTransform(event); return }
        val worldX = (lastFocusX - panX) / scale
        val worldY = (lastFocusY - panY) / scale
        scale = min(4f, max(.5f, scale * (span / lastSpan)))
        panX = focusX - worldX * scale
        panY = focusY - worldY * scale
        lastFocusX = focusX
        lastFocusY = focusY
        lastSpan = span
    }

    private fun toWorld(x: Float, y: Float): Pair<Float, Float> = Pair((x - panX) / scale, (y - panY) / scale)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scale, scale)
        strokes.forEach { drawStroke(canvas, it) }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = 28f * scale
        val offsetX = ((panX % spacing) + spacing) % spacing
        val offsetY = ((panY % spacing) + spacing) % spacing
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(211, 218, 224)
        val radius = min(1.45f, max(.8f, scale))
        var x = offsetX
        while (x < width) {
            var y = offsetY
            while (y < height) { canvas.drawCircle(x, y, radius, paint); y += spacing }
            x += spacing
        }
        paint.style = Paint.Style.STROKE
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        paint.strokeWidth = if (stroke.eraser) 30f else stroke.width
        paint.color = if (stroke.eraser) android.graphics.Color.rgb(247,242,233) else android.graphics.Color.rgb(35,35,35)
        canvas.drawPath(stroke.path, paint)
    }
    fun clear() { strokes.clear(); invalidate() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    fun png(): ByteArray {
        if (width == 0 || height == 0) return byteArrayOf()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(247, 242, 233))
        Canvas(bitmap).also { output ->
            drawGrid(output)
            output.save()
            output.translate(panX, panY)
            output.scale(scale, scale)
            strokes.forEach { drawStroke(output, it) }
            output.restore()
        }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    }
}
