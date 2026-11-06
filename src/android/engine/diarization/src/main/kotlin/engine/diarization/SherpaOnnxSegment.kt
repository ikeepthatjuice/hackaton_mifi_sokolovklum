package engine.diarization

/**
 * Raw speech segment emitted by native diarization engine clustering.
 *
 * @param clusterId Internal cluster identifier assigned by clustering algorithm
 * @param startMs Segment start time in milliseconds relative to original audio start
 * @param endMs Segment end time in milliseconds relative to original audio start
 */
data class SherpaOnnxSegment(
    val clusterId: Int,
    val startMs: Long,
    val endMs: Long
)

