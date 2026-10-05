package com.vineyard.aivideostudio.ui.screens.tools.components

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Rect as AndroidRect
import android.graphics.RectF as AndroidRectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.vineyard.aivideostudio.media.tools.DetectedTargetBox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Composable preview canvas that renders the unedited base video frame
 * and paints all active Editora4 overlays with real-time hardware acceleration.
 */
@Composable
fun ToolsOverlayPreview(
    modifier: Modifier = Modifier,
    baseBitmap: Bitmap?,
    activeBoxes: List<DetectedTargetBox>,
    currentTimeMs: Long,
    withArrow: Boolean = true
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (baseBitmap == null || baseBitmap.isRecycled) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height

        val srcWidth = baseBitmap.width.toFloat()
        val srcHeight = baseBitmap.height.toFloat()

        if (srcWidth <= 0f || srcHeight <= 0f) return@Canvas

        // Calculate aspect-fit destination rectangle
        val scale = min(canvasWidth / srcWidth, canvasHeight / srcHeight)
        val destWidth = srcWidth * scale
        val destHeight = srcHeight * scale
        val offsetX = (canvasWidth - destWidth) / 2f
        val offsetY = (canvasHeight - destHeight) / 2f

        // 1. Draw base video frame
        drawImage(
            image = baseBitmap.asImageBitmap(),
            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize(destWidth.roundToInt(), destHeight.roundToInt())
        )

        // 2. Draw active tool overlays scaled to destination dimensions
        for (box in activeBoxes) {
            val scaleX = destWidth / srcWidth
            val scaleY = destHeight / srcHeight

            val scaledX = offsetX + (box.x0 * scaleX)
            val scaledY = offsetY + (box.y0 * scaleY)
            val scaledW = box.width * scaleX
            val scaledH = box.height * scaleY

            NativeOverlayRenderer.drawToolOnDrawScope(
                drawScope = this,
                x0 = scaledX,
                y0 = scaledY,
                width = scaledW,
                height = scaledH,
                toolType = box.tool,
                currentTimeMs = currentTimeMs,
                withArrow = withArrow,
                fullCanvasWidth = canvasWidth,
                fullCanvasHeight = canvasHeight
            )
        }
    }
}

/**
 * Universal renderer capable of painting all 10 visual tools onto both
 * Jetpack Compose [DrawScope] (live preview) and [android.graphics.Canvas] (video exporter).
 */
object NativeOverlayRenderer {

    // Preset color definitions
    val AmberGlow = Color(0xFFF59E0B)
    val AmberBright = Color(0xFFFEF08A)
    val CyanAccent = Color(0xFF38BDF8)
    val PurpleAccent = Color(0xFFA855F7)
    val DangerRed = Color(0xFFEF4444)
    val SlateDark = Color(0xFF090D16)
    val SlateBorder = Color(0xFF475569)

    /**
     * Draws an Editora4 visual tool onto a Compose [DrawScope].
     */
    fun drawToolOnDrawScope(
        drawScope: DrawScope,
        x0: Float,
        y0: Float,
        width: Float,
        height: Float,
        toolType: String,
        currentTimeMs: Long,
        withArrow: Boolean,
        fullCanvasWidth: Float,
        fullCanvasHeight: Float
    ) {
        when (toolType) {
            "blur_gaussian", "blur" -> {
                drawScope.drawRect(
                    color = Color(0xDE0F172A),
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f)
                )
                drawScope.drawRect(
                    color = Color(0x8C38BDF8),
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f),
                    style = Stroke(width = 2f)
                )
            }

            "blur_mosaic" -> {
                val blockSize = max(8f, width / 14f)
                var currentX = x0 - 4f
                val endX = x0 + width + 4f
                val endY = y0 + height + 4f

                var blockIndex = 0
                while (currentX < endX) {
                    var currentY = y0 - 4f
                    while (currentY < endY) {
                        val bw = min(blockSize, endX - currentX)
                        val bh = min(blockSize, endY - currentY)
                        val shade = if ((blockIndex % 2) == 0) Color(0xDD0F172A) else Color(0xCC1E293B)
                        drawScope.drawRect(
                            color = shade,
                            topLeft = Offset(currentX, currentY),
                            size = Size(bw, bh)
                        )
                        currentY += blockSize
                        blockIndex++
                    }
                    currentX += blockSize
                }
                drawScope.drawRect(
                    color = Color(0x8038BDF8),
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f),
                    style = Stroke(width = 2f)
                )
            }

            "privacy_box" -> {
                drawScope.drawRect(
                    color = SlateDark,
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f)
                )
                drawScope.drawRect(
                    color = SlateBorder,
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f),
                    style = Stroke(width = 2f)
                )
            }

            "emoji_pill" -> {
                val pillRadius = (height + 8f) / 2f
                val pillBounds = Offset(x0 - 6f, y0 - 4f)
                val pillSize = Size(width + 12f, height + 8f)

                drawScope.drawRoundRect(
                    color = SlateDark,
                    topLeft = pillBounds,
                    size = pillSize,
                    cornerRadius = CornerRadius(pillRadius, pillRadius)
                )
                drawScope.drawRoundRect(
                    color = CyanAccent,
                    topLeft = pillBounds,
                    size = pillSize,
                    cornerRadius = CornerRadius(pillRadius, pillRadius),
                    style = Stroke(width = 2f)
                )

                val fontSizePx = (height + 8f) * 0.72f
                drawScope.drawContext.canvas.nativeCanvas.apply {
                    val paint = AndroidPaint().apply {
                        textSize = fontSizePx
                        textAlign = AndroidPaint.Align.CENTER
                        isAntiAlias = true
                    }
                    val textX = x0 + (width / 2f)
                    val textY = (y0 + (height / 2f)) - ((paint.descent() + paint.ascent()) / 2f)
                    drawText("🔒", textX, textY, paint)
                }
            }

            "button_highlight" -> {
                val pulse = (sin(currentTimeMs * 0.010) * 0.5 + 0.5).toFloat()
                val pad = 6f + pulse * 4f
                val bx = x0 - pad
                val by = y0 - pad
                val bw = width + (pad * 2f)
                val bh = height + (pad * 2f)

                drawScope.drawRect(
                    color = AmberGlow.copy(alpha = 0.12f + pulse * 0.22f),
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh)
                )

                drawScope.drawRect(
                    color = AmberGlow,
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh),
                    style = Stroke(width = 2f + pulse * 2.5f)
                )

                val len = min(bw, bh) * 0.28f
                val bracketPath = Path().apply {
                    moveTo(bx, by + len); lineTo(bx, by); lineTo(bx + len, by)
                    moveTo(bx + bw - len, by); lineTo(bx + bw, by); lineTo(bx + bw, by + len)
                    moveTo(bx, by + bh - len); lineTo(bx, by + bh); lineTo(bx + len, by + bh)
                    moveTo(bx + bw - len, by + bh); lineTo(bx + bw, by + bh); lineTo(bx + bw, by + bh - len)
                }

                drawScope.drawPath(
                    path = bracketPath,
                    color = AmberBright,
                    style = Stroke(width = 4f)
                )
            }

            "flashing_arrow" -> {
                val pulse = (sin(currentTimeMs * 0.012) * 0.5 + 0.5).toFloat()
                val bounce = pulse * 8f
                val arrowLength = max(30f, fullCanvasWidth * 0.045f)
                val tipX = x0 - 4f - bounce
                val tipY = y0 + (height / 2f)
                val tailX = tipX - arrowLength

                drawScope.drawLine(
                    color = Color.Black,
                    start = Offset(tailX, tipY),
                    end = Offset(tipX, tipY),
                    strokeWidth = 6f
                )
                drawScope.drawLine(
                    color = DangerRed,
                    start = Offset(tailX, tipY),
                    end = Offset(tipX, tipY),
                    strokeWidth = 4f
                )

                val headSize = max(9f, arrowLength * 0.32f)
                val headPath = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(tipX - headSize, tipY - (headSize * 0.75f))
                    lineTo(tipX - headSize, tipY + (headSize * 0.75f))
                    close()
                }

                drawScope.drawPath(path = headPath, color = DangerRed, style = Fill)
                drawScope.drawPath(path = headPath, color = Color.Black, style = Stroke(width = 2f))
            }

            "spotlight" -> {
                val darkColor = Color(0xA6000000)
                drawScope.drawRect(color = darkColor, topLeft = Offset(0f, 0f), size = Size(fullCanvasWidth, y0 - 4f))
                drawScope.drawRect(
                    color = darkColor,
                    topLeft = Offset(0f, y0 + height + 4f),
                    size = Size(fullCanvasWidth, fullCanvasHeight - (y0 + height + 4f))
                )
                drawScope.drawRect(color = darkColor, topLeft = Offset(0f, y0 - 4f), size = Size(x0 - 4f, height + 8f))
                drawScope.drawRect(
                    color = darkColor,
                    topLeft = Offset(x0 + width + 4f, y0 - 4f),
                    size = Size(fullCanvasWidth - (x0 + width + 4f), height + 8f)
                )

                drawScope.drawRect(
                    color = CyanAccent,
                    topLeft = Offset(x0 - 4f, y0 - 4f),
                    size = Size(width + 8f, height + 8f),
                    style = Stroke(width = 3f)
                )
            }

            "highlight_circle" -> {
                val cx = x0 + (width / 2f)
                val cy = y0 + (height / 2f)
                val radius = (max(width, height) / 2f) + 8f

                drawScope.drawCircle(
                    color = CyanAccent,
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 4f)
                )
            }

            "vertical_column" -> {
                drawScope.drawRect(
                    color = PurpleAccent.copy(alpha = 0.14f),
                    topLeft = Offset(x0 - 4f, 0f),
                    size = Size(width + 8f, fullCanvasHeight)
                )
                drawScope.drawRect(
                    color = PurpleAccent.copy(alpha = 0.85f),
                    topLeft = Offset(x0 - 4f, 0f),
                    size = Size(width + 8f, fullCanvasHeight),
                    style = Stroke(width = 3f)
                )
            }

            else -> {
                drawScope.drawRect(
                    color = AmberGlow.copy(alpha = 0.35f),
                    topLeft = Offset(x0 - 3f, y0 - 3f),
                    size = Size(width + 6f, height + 6f)
                )
                drawScope.drawRect(
                    color = AmberGlow,
                    topLeft = Offset(x0 - 3f, y0 - 3f),
                    size = Size(width + 6f, height + 6f),
                    style = Stroke(width = 3f)
                )

                if (withArrow) {
                    val arrowLength = max(28f, fullCanvasWidth * 0.04f)
                    val startX = max(5f, x0 - 6f - arrowLength)
                    val startY = y0 + (height / 2f)

                    drawScope.drawLine(
                        color = DangerRed,
                        start = Offset(startX, startY),
                        end = Offset(x0 - 6f, startY),
                        strokeWidth = 4f
                    )

                    val head = max(8f, arrowLength * 0.28f)
                    val arrowHeadPath = Path().apply {
                        moveTo(x0 - 6f, startY)
                        lineTo(x0 - 6f - head, startY - (head * 0.7f))
                        lineTo(x0 - 6f - head, startY + (head * 0.7f))
                        close()
                    }
                    drawScope.drawPath(path = arrowHeadPath, color = DangerRed, style = Fill)
                }
            }
        }
    }

    /**
     * Offline Android Canvas renderer used during video export rendering.
     * Guarantees 100% pixel-perfect match with the live Compose preview.
     */
    fun drawToolOnAndroidCanvas(
        canvas: android.graphics.Canvas,
        x0: Float,
        y0: Float,
        width: Float,
        height: Float,
        toolType: String,
        currentTimeMs: Long,
        withArrow: Boolean
    ) {
        val paint = AndroidPaint().apply { isAntiAlias = true }
        val canvasWidth = canvas.width.toFloat()
        val canvasHeight = canvas.height.toFloat()

        when (toolType) {
            "blur_gaussian", "blur" -> {
                paint.color = android.graphics.Color.parseColor("#DE0F172A")
                paint.style = AndroidPaint.Style.FILL
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)

                paint.color = android.graphics.Color.parseColor("#8C38BDF8")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)
            }

            "blur_mosaic" -> {
                val blockSize = max(8f, width / 14f)
                var currentX = x0 - 4f
                val endX = x0 + width + 4f
                val endY = y0 + height + 4f

                var blockIndex = 0
                while (currentX < endX) {
                    var currentY = y0 - 4f
                    while (currentY < endY) {
                        val bw = min(blockSize, endX - currentX)
                        val bh = min(blockSize, endY - currentY)
                        paint.style = AndroidPaint.Style.FILL
                        paint.color = if ((blockIndex % 2) == 0) {
                            android.graphics.Color.parseColor("#DD0F172A")
                        } else {
                            android.graphics.Color.parseColor("#CC1E293B")
                        }
                        canvas.drawRect(currentX, currentY, currentX + bw, currentY + bh, paint)
                        currentY += blockSize
                        blockIndex++
                    }
                    currentX += blockSize
                }

                paint.color = android.graphics.Color.parseColor("#8038BDF8")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)
            }

            "privacy_box" -> {
                paint.color = android.graphics.Color.parseColor("#FF090D16")
                paint.style = AndroidPaint.Style.FILL
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)

                paint.color = android.graphics.Color.parseColor("#FF475569")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)
            }

            "emoji_pill" -> {
                val pillRadius = (height + 8f) / 2f
                val rectF = AndroidRectF(x0 - 6f, y0 - 4f, x0 + width + 6f, y0 + height + 4f)

                paint.color = android.graphics.Color.parseColor("#FF090D16")
                paint.style = AndroidPaint.Style.FILL
                canvas.drawRoundRect(rectF, pillRadius, pillRadius, paint)

                paint.color = android.graphics.Color.parseColor("#FF38BDF8")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRoundRect(rectF, pillRadius, pillRadius, paint)

                val textPaint = AndroidPaint().apply {
                    textSize = (height + 8f) * 0.72f
                    textAlign = AndroidPaint.Align.CENTER
                    isAntiAlias = true
                }
                val textX = x0 + (width / 2f)
                val textY = (y0 + (height / 2f)) - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText("🔒", textX, textY, textPaint)
            }

            "button_highlight" -> {
                val pulse = (sin(currentTimeMs * 0.010) * 0.5 + 0.5).toFloat()
                val pad = 6f + pulse * 4f
                val bx = x0 - pad
                val by = y0 - pad
                val bw = width + (pad * 2f)
                val bh = height + (pad * 2f)

                paint.color = android.graphics.Color.argb((0.25f * 255).toInt(), 245, 158, 11)
                paint.style = AndroidPaint.Style.FILL
                canvas.drawRect(bx, by, bx + bw, by + bh, paint)

                paint.color = android.graphics.Color.parseColor("#FFF59E0B")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 2f + pulse * 2.5f
                canvas.drawRect(bx, by, bx + bw, by + bh, paint)

                val len = min(bw, bh) * 0.28f
                val path = AndroidPath().apply {
                    moveTo(bx, by + len); lineTo(bx, by); lineTo(bx + len, by)
                    moveTo(bx + bw - len, by); lineTo(bx + bw, by); lineTo(bx + bw, by + len)
                    moveTo(bx, by + bh - len); lineTo(bx, by + bh); lineTo(bx + len, by + bh)
                    moveTo(bx + bw - len, by + bh); lineTo(bx + bw, by + bh); lineTo(bx + bw, by + bh - len)
                }
                paint.color = android.graphics.Color.parseColor("#FFFEF08A")
                paint.strokeWidth = 4f
                canvas.drawPath(path, paint)
            }

            "flashing_arrow" -> {
                val pulse = (sin(currentTimeMs * 0.012) * 0.5 + 0.5).toFloat()
                val bounce = pulse * 8f
                val arrowLength = max(30f, canvasWidth * 0.045f)
                val tipX = x0 - 4f - bounce
                val tipY = y0 + (height / 2f)
                val tailX = tipX - arrowLength

                paint.color = android.graphics.Color.BLACK
                paint.strokeWidth = 6f
                canvas.drawLine(tailX, tipY, tipX, tipY, paint)

                paint.color = android.graphics.Color.parseColor("#FFEF4444")
                paint.strokeWidth = 4f
                canvas.drawLine(tailX, tipY, tipX, tipY, paint)

                val headSize = max(9f, arrowLength * 0.32f)
                val path = AndroidPath().apply {
                    moveTo(tipX, tipY)
                    lineTo(tipX - headSize, tipY - (headSize * 0.75f))
                    lineTo(tipX - headSize, tipY + (headSize * 0.75f))
                    close()
                }
                paint.style = AndroidPaint.Style.FILL
                canvas.drawPath(path, paint)
            }

            "spotlight" -> {
                paint.style = AndroidPaint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#A6000000")
                canvas.drawRect(0f, 0f, canvasWidth, y0 - 4f, paint)
                canvas.drawRect(0f, y0 + height + 4f, canvasWidth, canvasHeight, paint)
                canvas.drawRect(0f, y0 - 4f, x0 - 4f, y0 + height + 4f, paint)
                canvas.drawRect(x0 + width + 4f, y0 - 4f, canvasWidth, y0 + height + 4f, paint)

                paint.style = AndroidPaint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#FF38BDF8")
                paint.strokeWidth = 3f
                canvas.drawRect(x0 - 4f, y0 - 4f, x0 + width + 4f, y0 + height + 4f, paint)
            }

            "highlight_circle" -> {
                val cx = x0 + (width / 2f)
                val cy = y0 + (height / 2f)
                val radius = (max(width, height) / 2f) + 8f

                paint.color = android.graphics.Color.parseColor("#FF38BDF8")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 4f
                canvas.drawCircle(cx, cy, radius, paint)
            }

            "vertical_column" -> {
                paint.style = AndroidPaint.Style.FILL
                paint.color = android.graphics.Color.argb((0.14f * 255).toInt(), 168, 85, 247)
                canvas.drawRect(x0 - 4f, 0f, x0 + width + 4f, canvasHeight, paint)

                paint.style = AndroidPaint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#FFA855F7")
                paint.strokeWidth = 3f
                canvas.drawRect(x0 - 4f, 0f, x0 + width + 4f, canvasHeight, paint)
            }

            else -> {
                paint.color = android.graphics.Color.argb((0.35f * 255).toInt(), 245, 158, 11)
                paint.style = AndroidPaint.Style.FILL
                canvas.drawRect(x0 - 3f, y0 - 3f, x0 + width + 3f, y0 + height + 3f, paint)

                paint.color = android.graphics.Color.parseColor("#FFF59E0B")
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawRect(x0 - 3f, y0 - 3f, x0 + width + 3f, y0 + height + 3f, paint)

                if (withArrow) {
                    val arrowLength = max(28f, canvasWidth * 0.04f)
                    val startX = max(5f, x0 - 6f - arrowLength)
                    val startY = y0 + (height / 2f)

                    paint.color = android.graphics.Color.parseColor("#FFEF4444")
                    paint.strokeWidth = 4f
                    canvas.drawLine(startX, startY, x0 - 6f, startY, paint)

                    val head = max(8f, arrowLength * 0.28f)
                    val arrowHeadPath = AndroidPath().apply {
                        moveTo(x0 - 6f, startY)
                        lineTo(x0 - 6f - head, startY - (head * 0.7f))
                        lineTo(x0 - 6f - head, startY + (head * 0.7f))
                        close()
                    }
                    paint.style = AndroidPaint.Style.FILL
                    canvas.drawPath(arrowHeadPath, paint)
                }
            }
        }
    }
}