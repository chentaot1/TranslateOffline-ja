package com.yomu.pipeline.bubble

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.yomu.ml.OnnxRuntime

data class Bubble(
    val id: Int,
    val boundingBox: RectF,
    val confidence: Float,
    val textRegion: RectF? = null
)

data class MangaLayout(
    val panels: List<RectF>,
    val bubbles: List<Bubble>
)

class BubbleDetector(private val onnxRuntime: OnnxRuntime) {

    companion object {
        private const val TAG = "BubbleDetector"
        /** The shipped threshold; the reader tunes it in Settings (#227), benchmarks never do. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.25f
        internal const val NMS_IOU_THRESHOLD = 0.80f
        // #88 bug B: a small box sitting almost entirely inside a bigger one has low IoU, so IoU-NMS
        // keeps both — two boxes over the same balloon, whose translated texts then paint on top of
        // each other. Of two boxes where one is at least this fraction contained in the other, keep
        // one. 0.90: a box that merges two bubbles plus a second box over just one of them
        // measured 0.92 contained on device, and both translations painted over each other; the
        // merged box's OCR already carries that text. Genuinely-close distinct balloons the model
        // under-separates are still the detector's problem (#57), not this heuristic's.
        internal const val NMS_CONTAINMENT_THRESHOLD = 0.90f
    }

    private var isLoaded = false
    private var modelPath: String? = null

    fun loadModel(modelPath: String): Boolean {
        if (!isLoaded) {
            this.modelPath = modelPath
            isLoaded = onnxRuntime.loadModel(modelPath)
        }
        return isLoaded
    }

    fun isModelLoaded(): Boolean = isLoaded

    fun detectLayout(bitmap: Bitmap, confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD): MangaLayout {
        if (!isLoaded) return MangaLayout(emptyList(), emptyList())
        val path = modelPath ?: return MangaLayout(emptyList(), emptyList())

        val layoutDetections = onnxRuntime.runLayoutInference(path, bitmap, confidenceThreshold)

        val panels = layoutDetections
            .filter { it.classId == 0 }
            .map { RectF(it.bbox[0], it.bbox[1], it.bbox[2], it.bbox[3]) }

        val bubbleDetections = layoutDetections
            .filter { it.classId == 1 }
            .map { OnnxRuntime.Detection(label = "bubble", confidence = it.confidence, bbox = it.bbox) }
        val kept = keptDetections(bubbleDetections, confidenceThreshold)

        Log.d(
            TAG,
            "Layout detection raw=${layoutDetections.size} panels=${panels.size} bubblesRaw=${bubbleDetections.size} kept=${kept.size}"
        )
        kept.forEachIndexed { index, detection ->
            Log.d(
                TAG,
                "Bubble kept index=$index confidence=${detection.confidence} bbox=${detection.bbox.joinToString(",")}"
            )
        }

        val bubbles = kept.mapIndexed { index, detection ->
            val (x1, y1, x2, y2) = detection.bbox
            Bubble(
                id = index + 1,
                boundingBox = RectF(x1, y1, x2, y2),
                confidence = detection.confidence,
                textRegion = RectF(x1, y1, x2, y2)
            )
        }

        return MangaLayout(panels, bubbles)
    }

    fun detect(bitmap: Bitmap, confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD): List<Bubble> {
        return detectLayout(bitmap, confidenceThreshold).bubbles
    }

    fun release() {
        modelPath?.let { onnxRuntime.releaseModel(it) }
        isLoaded = false
        modelPath = null
    }
}

internal fun keptDetections(
    detections: List<OnnxRuntime.Detection>,
    confidenceThreshold: Float
): List<OnnxRuntime.Detection> = nonMaxSuppressDetections(
    detections.filter { it.confidence >= confidenceThreshold },
    BubbleDetector.NMS_IOU_THRESHOLD,
    BubbleDetector.NMS_CONTAINMENT_THRESHOLD
)

internal fun nonMaxSuppressDetections(
    detections: List<OnnxRuntime.Detection>,
    iouThreshold: Float,
    // Above 1 disables containment suppression (a fraction is never > 1), keeping IoU-only behaviour.
    containmentThreshold: Float = Float.MAX_VALUE
): List<OnnxRuntime.Detection> {
    if (detections.isEmpty()) return emptyList()
    val sorted = detections.sortedByDescending { it.confidence }
    val kept = mutableListOf<OnnxRuntime.Detection>()

    fun isRedundant(candidate: OnnxRuntime.Detection, others: List<OnnxRuntime.Detection>) =
        others.any { other ->
            detectionIou(candidate, other) > iouThreshold ||
                detectionContainedFraction(candidate, other) >= containmentThreshold ||
                detectionContainedFraction(other, candidate) >= containmentThreshold
        }

    for (candidate in sorted) {
        // Boxes are visited highest-confidence first, so a suppressed candidate loses to a box we
        // already trust more. IoU catches near-equal boxes; the containment checks catch the
        // #88 case where one box nearly sits inside the other (either direction) but IoU stays low.
        if (!isRedundant(candidate, kept)) {
            kept.add(candidate)
        }
    }

    // #370: a dropped box that swallows a kept one carries text the kept box misses, such as a
    // bubble the model only boxes merged with its neighbour. One merged read beats losing that
    // bubble, but bubbles the model boxed separately stay separate.
    for (candidate in sorted.filterNot { it in kept }) {
        val swallowed = kept.firstOrNull {
            detectionContainedFraction(it, candidate) >= containmentThreshold
        } ?: continue
        if (!isRedundant(candidate, kept - swallowed)) {
            kept[kept.indexOf(swallowed)] = candidate
        }
    }

    return kept
}

internal fun detectionContainedFraction(
    inner: OnnxRuntime.Detection,
    outer: OnnxRuntime.Detection
): Float {
    val ix1 = minOf(inner.bbox[0], inner.bbox[2])
    val iy1 = minOf(inner.bbox[1], inner.bbox[3])
    val ix2 = maxOf(inner.bbox[0], inner.bbox[2])
    val iy2 = maxOf(inner.bbox[1], inner.bbox[3])

    val ox1 = minOf(outer.bbox[0], outer.bbox[2])
    val oy1 = minOf(outer.bbox[1], outer.bbox[3])
    val ox2 = maxOf(outer.bbox[0], outer.bbox[2])
    val oy2 = maxOf(outer.bbox[1], outer.bbox[3])

    val interWidth = maxOf(0f, minOf(ix2, ox2) - maxOf(ix1, ox1))
    val interHeight = maxOf(0f, minOf(iy2, oy2) - maxOf(iy1, oy1))
    val interArea = interWidth * interHeight
    if (interArea <= 0f) return 0f

    val innerArea = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
    if (innerArea <= 0f) return 0f

    return interArea / innerArea
}

internal fun detectionIou(a: OnnxRuntime.Detection, b: OnnxRuntime.Detection): Float {
    val ax1 = minOf(a.bbox[0], a.bbox[2])
    val ay1 = minOf(a.bbox[1], a.bbox[3])
    val ax2 = maxOf(a.bbox[0], a.bbox[2])
    val ay2 = maxOf(a.bbox[1], a.bbox[3])

    val bx1 = minOf(b.bbox[0], b.bbox[2])
    val by1 = minOf(b.bbox[1], b.bbox[3])
    val bx2 = maxOf(b.bbox[0], b.bbox[2])
    val by2 = maxOf(b.bbox[1], b.bbox[3])

    val interLeft = maxOf(ax1, bx1)
    val interTop = maxOf(ay1, by1)
    val interRight = minOf(ax2, bx2)
    val interBottom = minOf(ay2, by2)

    val interWidth = maxOf(0f, interRight - interLeft)
    val interHeight = maxOf(0f, interBottom - interTop)
    val interArea = interWidth * interHeight
    if (interArea <= 0f) return 0f

    val areaA = maxOf(0f, ax2 - ax1) * maxOf(0f, ay2 - ay1)
    val areaB = maxOf(0f, bx2 - bx1) * maxOf(0f, by2 - by1)
    val union = areaA + areaB - interArea
    if (union <= 0f) return 0f

    return interArea / union
}

/** The bubbles whose box center lies in none of [regions], so a manga bubble the floating button only partly covers stays. */
internal fun List<Bubble>.centeredOutside(regions: List<RectF>): List<Bubble> = filterNot { bubble ->
    regions.any { it.contains(bubble.boundingBox.centerX(), bubble.boundingBox.centerY()) }
}
