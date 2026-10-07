package x.vladgba.sheaflauncher.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Persists the workspace as JSON. AtomicFile keeps the old copy if a write is interrupted. */
class WorkspaceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "workspace.json"))

    fun load(): WorkspaceModel = try {
        WorkspaceModel.fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (e: Exception) {
        WorkspaceModel()
    }

    fun save(model: WorkspaceModel) {
        val out = try { file.startWrite() } catch (e: Exception) { Log.e(TAG, "save", e); return }
        try {
            out.write(model.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "save", e)
        }
    }

    private companion object { const val TAG = "Sheaf" }
}

/** [updated] = package lastUpdateTime, used to tell whether a cached icon is stale. */
class AppInfo(val component: String, val label: String, val icon: Drawable, val updated: Long = 0L) {
    val componentName: ComponentName get() = ComponentName.unflattenFromString(component)!!
    val packageName: String get() = componentName.packageName
}

/** Queries launchable activities. Safe to call off the main thread. */
class AppRepository(private val context: Context) {
    fun query(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val updated = HashMap<String, Long>()
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val ai = it.activityInfo
                val time = updated.getOrPut(ai.packageName) {
                    try { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
                }
                AppInfo(
                    ComponentName(ai.packageName, ai.name).flattenToString(),
                    it.loadLabel(pm).toString(),
                    it.loadIcon(pm),
                    time
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
