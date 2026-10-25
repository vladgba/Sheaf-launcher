import org.json.JSONArray
import org.json.JSONObject
import x.vladgba.sheaflauncher.model.WidgetItem
import x.vladgba.sheaflauncher.model.WorkspaceModel
import kotlin.math.abs
import kotlin.random.Random

private var checks = 0
private fun verify(ok: Boolean, message: String) {
    checks++
    check(ok) { message }
}
private fun near(actual: Float, expected: Float, message: String) =
    verify(actual.isFinite() && abs(actual - expected) < 0.001f, "$message: $actual vs $expected")
private fun widget() = WidgetItem(1, 123, "example/Widget").apply {
    x = 40f; y = 60f; w = 100f; h = 200f; removePadding = true
}
private fun model(item: WidgetItem) = WorkspaceModel().apply { items += item }
private fun itemJson(root: JSONObject) = root.getJSONArray("items").getJSONObject(0)
private fun restored(root: JSONObject) = WorkspaceModel.fromJson(JSONObject(root.toString())).widgets().single()
private fun bounds(item: WidgetItem, width: Float, height: Float) {
    verify(listOf(item.x, item.y, item.w, item.h).all { it.isFinite() }, "finite geometry")
    verify(item.w > 0 && item.h > 0 && item.x >= 0 && item.y >= 0, "positive size and position")
    verify(item.x + item.w <= width + 0.01f && item.y + item.h <= height + 0.01f, "inside destination page")
}

fun main() {
    val source = model(widget())
    val backup = source.toJson(400f, 600f)
    verify(backup.getInt("version") == 1, "additive backup format stays compatible")
    verify(itemJson(backup).getJSONArray("rel").length() == 4, "backup contains four page fractions")
    verify(!itemJson(source.toJson()).has("rel"), "ordinary persistence omits fractions")
    for (size in listOf(0f, -1f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
        verify(!itemJson(source.toJson(size, 600f)).has("rel"), "invalid export page omits fractions")
    }
    val scaled = restored(backup)
    scaled.fitToPage(800f, 300f, 4, 5)
    near(scaled.x, 80f, "scaled x"); near(scaled.y, 30f, "scaled y")
    near(scaled.w, 200f, "scaled width"); near(scaled.h, 100f, "scaled height")
    verify(scaled.relative == null, "fractions consumed once")
    verify(scaled.removePadding && scaled.appWidgetId == 123, "non-geometry state retained")
    scaled.fitToPage(1000f, 1000f, 4, 5)
    near(scaled.w, 200f, "repeat fitting does not rescale")

    val legacy = restored(source.toJson())
    legacy.fitToPage(400f, 600f, 4, 5)
    near(legacy.x, 40f, "legacy x retained"); near(legacy.h, 200f, "legacy height retained")
    legacy.fitToPage(80f, 120f, 4, 5)
    near(legacy.w, 80f, "oversized legacy width clamped")
    near(legacy.h, 120f, "oversized legacy height clamped")
    bounds(legacy, 80f, 120f)

    val snapped = widget().apply { snap = true; relative = floatArrayOf(.13f, .17f, .38f, .42f) }
    snapped.fitToPage(800f, 500f, 4, 5)
    near(snapped.x, 200f, "snapped x"); near(snapped.y, 100f, "snapped y")
    near(snapped.w, 400f, "snapped width"); near(snapped.h, 200f, "snapped height")

    val malformed = source.toJson()
    itemJson(malformed).put("x", "NaN").put("y", "-Infinity")
        .put("w", Double.MAX_VALUE).put("h", -20)
    val sanitized = restored(malformed)
    near(sanitized.x, 0f, "NaN position sanitized")
    near(sanitized.y, 0f, "infinite position sanitized")
    near(sanitized.w, 100f, "float-overflowing width sanitized")
    near(sanitized.h, 1f, "negative height sanitized")
    model(sanitized).toJson() // Must remain persistable before fitting, too.
    sanitized.fitToPage(400f, 600f, 4, 5)
    bounds(sanitized, 400f, 600f)

    for (bad in listOf(
        listOf(0, 0, 1), listOf(0, 0, 1, 1, 1), listOf(-1, 0, 1, 1),
        listOf(0, 0, 0, 1), listOf(0, 0, "NaN", 1), listOf(0, 0, "Infinity", 1),
        listOf(0, 0, "wrong", 1), listOf(0, 0, Double.MAX_VALUE, 1),
    )) {
        val json = source.toJson()
        itemJson(json).put("rel", JSONArray(bad))
        val parsed = restored(json)
        verify(parsed.relative == null, "malformed fractions ignored: $bad")
        parsed.fitToPage(400f, 600f, 4, 5)
        near(parsed.w, 100f, "malformed fractions fall back to legacy dp")
    }
    val huge = widget().apply { relative = FloatArray(4) { Float.MAX_VALUE } }
    huge.fitToPage(400f, 600f, 4, 5)
    near(huge.w, 400f, "huge finite fraction clamps before multiplication")
    bounds(huge, 400f, 600f)
    for (bad in listOf(floatArrayOf(1f), floatArrayOf(0f, 0f, Float.NaN, 1f))) {
        val parsed = widget().apply { relative = bad }
        parsed.fitToPage(400f, 600f, 4, 5)
        near(parsed.w, 100f, "invalid in-memory fractions ignored")
    }
    for (size in listOf(0f, -1f, .5f, Float.NaN, Float.POSITIVE_INFINITY)) {
        val deferred = restored(backup)
        deferred.fitToPage(size, 300f, 4, 5)
        verify(deferred.relative != null, "unusable page preserves pending fractions")
        near(deferred.x, 40f, "unusable page does not mutate geometry")
        deferred.fitToPage(800f, 300f, 4, 5)
        near(deferred.x, 80f, "later valid page consumes pending fractions")
    }
    verify(!widget().snapToGrid(400f, 600f, 0, 5), "zero grid rejected")
    verify(!widget().snapToGrid(Float.NaN, 600f, 4, 5), "NaN page rejected by snapping")

    val random = Random(20261025)
    repeat(500) {
        val width = random.nextDouble(50.0, 1000.0).toFloat()
        val height = random.nextDouble(50.0, 1500.0).toFloat()
        val cols = random.nextInt(2, 15)
        val rows = random.nextInt(2, 15)
        val item = widget().apply {
            snap = random.nextBoolean()
            relative = FloatArray(4) { random.nextDouble(.001, 4.0).toFloat() }
        }
        item.fitToPage(width, height, cols, rows)
        bounds(item, width, height)
        if (item.snap) {
            for ((value, cell) in listOf(item.x to width / cols, item.w to width / cols,
                item.y to height / rows, item.h to height / rows)) {
                verify(abs(value / cell - Math.round(value / cell)) < .001f, "grid alignment")
            }
        }
    }
    println("Backup geometry checks passed: $checks")
}
