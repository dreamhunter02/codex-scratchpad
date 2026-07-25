package dev.codexscratchpad

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
    val prefs = remember { context.getSharedPreferences("scratchpad", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "http://192.168.1.2:8787") ?: "") }
    var caption by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Set your Mac address to connect") }
    var canvas by remember { mutableStateOf<ScratchpadView?>(null) }
    var erasing by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Graphite, surface = Surface)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Graphite) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("</>", color = Amber, fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Codex ", color = Color.White, fontSize = 25.sp)
                    Text("Scratchpad", color = Amber, fontSize = 25.sp)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("● Connected", color = Color(0xFF9BD96C), fontSize = 13.sp)
                        Text("Local Wi-Fi", color = Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; prefs.edit().putString("endpoint", it).apply() },
                    label = { Text("Mac bridge URL") },
                    supportingText = { Text("Example: http://192.168.1.42:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber, focusedLabelColor = Amber)
                )
                Spacer(Modifier.height(12.dp))
                AndroidView(
                    factory = { ScratchpadView(it).also { view -> canvas = view } },
                    update = { it.erasing = erasing },
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                )
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
                        if (png == null || png.isEmpty()) { status = "Draw something first"; return@Button }
                        status = "Pushing to Codex…"
                        thread {
                            val result = pushScribble(endpoint, png, caption)
                            (context as? ComponentActivity)?.runOnUiThread { status = result }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)
                ) { Text("⇧  Push to Codex", fontSize = 18.sp) }
                Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
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
    if (connection.responseCode in 200..299) "Pushed — type /scratchpad in Codex" else "Bridge error ${connection.responseCode}"
} catch (error: Exception) { "Could not reach bridge: ${error.message}" }

private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private class ScratchpadView(context: Context) : View(context) {
    private data class Stroke(val path: Path, val eraser: Boolean, val width: Float)
    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    var erasing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 35, 35); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }

    init { background = ColorDrawable(android.graphics.Color.rgb(247, 242, 233)) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val width = if (isStylus) 3.5f + event.pressure * 6f else 6f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = Stroke(Path().apply { moveTo(event.x, event.y) }, erasing, width).also(strokes::add)
            MotionEvent.ACTION_MOVE -> current?.path?.lineTo(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate(); return true
    }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); strokes.forEach { drawStroke(canvas, it) } }
    private fun drawStroke(canvas: Canvas, stroke: Stroke) { paint.strokeWidth = if (stroke.eraser) 30f else stroke.width; paint.color = if (stroke.eraser) android.graphics.Color.rgb(247,242,233) else android.graphics.Color.rgb(35,35,35); canvas.drawPath(stroke.path, paint) }
    fun clear() { strokes.clear(); invalidate() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    fun png(): ByteArray { if (width == 0 || height == 0) return byteArrayOf(); val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.rgb(247,242,233)); Canvas(bitmap).also { output -> strokes.forEach { drawStroke(output, it) } }; return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() } }
}
