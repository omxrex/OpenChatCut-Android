package com.openchatcut.android.core

import android.content.Context
import com.openchatcut.android.model.ProjectDoc
import com.openchatcut.android.util.Json
import java.io.File

/** Persists the real project document as JSON in app-private storage. */
object ProjectStore {
    private fun file(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }.let { File(it, "current.json") }

    fun save(context: Context, project: ProjectDoc): Result<Unit> = runCatching {
        val target = file(context)
        val temp = File(target.absolutePath + ".tmp")
        temp.writeText(project.toJson().toString(2))
        if (target.exists() && !target.delete()) error("Cannot replace existing project file")
        if (!temp.renameTo(target)) error("Cannot write project file")
    }

    fun load(context: Context): ProjectDoc? {
        val source = file(context)
        if (!source.exists()) return null
        return runCatching { ProjectDoc.fromJson(Json.parseObject(source.readText())) }.getOrNull()
    }

    fun location(context: Context): String = file(context).absolutePath
}
