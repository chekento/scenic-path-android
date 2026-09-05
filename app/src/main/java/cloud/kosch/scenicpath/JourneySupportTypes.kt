package cloud.kosch.scenicpath

fun isTravelSupportPoint(point: ScenePointUi): Boolean {
    val subtype = point.subtype.orEmpty()
    return subtype.startsWith("overnight_") || subtype.startsWith("ebike_")
}
