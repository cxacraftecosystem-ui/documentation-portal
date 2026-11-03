package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberTraceRuntime(): TraceEngineRuntime {
    val app = LocalContext.current.applicationContext
    return remember(app) { traceRuntime(app) }
}

internal fun tracePlateResult(
    decoded: TraceDecoded,
    rgba: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    frameNote: String,
    request: TraceRequest,
): TraceResult {
    var tracePlate: Bitmap? = null
    var photographPlate: Bitmap? = null
    var plateRefusal = ""

    if (decoded.width != sourceWidth || decoded.height != sourceHeight) {
        plateRefusal = traceSentence(
            TraceFailureKind.FRAME_MISMATCH,
            "${decoded.width}x${decoded.height} from a ${sourceWidth}x$sourceHeight photograph",
        )
    } else {
        val (plateWidth, plateHeight) = traceWorkingSize(
            decoded.width,
            decoded.height,
            request.plateLongEdgePx,
        )
        tracePlate = TracePlates.renderTrace(
            geometry = decoded.geometry,
            documentWidth = decoded.width,
            documentHeight = decoded.height,
            plateWidth = plateWidth,
            plateHeight = plateHeight,
        )
        photographPlate = tracePlate?.let {
            TracePlates.photographPlate(
                rgba = rgba,
                width = sourceWidth,
                height = sourceHeight,
                plateWidth = plateWidth,
                plateHeight = plateHeight,
            )
        }
        if (tracePlate == null || photographPlate == null) {
            tracePlate?.recycle()
            photographPlate?.recycle()
            tracePlate = null
            photographPlate = null
            plateRefusal = TRACE_PLATE_MEMORY_REFUSAL
        }
    }

    return TraceResult(
        svg = decoded.svg,
        geometry = decoded.geometry,
        tracePlate = tracePlate,
        photographPlate = photographPlate,
        plateRefusal = plateRefusal,
        width = decoded.width,
        height = decoded.height,
        workingWidth = decoded.workingWidth,
        workingHeight = decoded.workingHeight,
        shapeCount = decoded.shapeCount,
        nodeCount = decoded.nodeCount,
        stages = decoded.stages,
        totalMillis = decoded.totalMillis,
        notes = decoded.notes,
        appliedParams = decoded.appliedParams,
        autoSubjectId = decoded.autoSubjectId,
        suggestedStyleId = decoded.suggestedStyleId,
        frameNote = frameNote,
    )
}
