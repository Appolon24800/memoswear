package dev.appolon.memoswear.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A list kept in memory and mirrored to a JSON file. Every update is written to a temporary file
 * and renamed over the old one, so a crash leaves either the previous or the new list, never a
 * half-written one.
 */
class JsonListStore<T>(private val file: File, private val json: Json, private val item: KSerializer<T>) {
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(item)
    private val _flow = MutableStateFlow(load())

    val flow: StateFlow<List<T>> = _flow.asStateFlow()
    val value: List<T> get() = _flow.value

    suspend fun update(transform: (List<T>) -> List<T>) {
        mutex.withLock {
            val next = transform(_flow.value)
            write(next)
            _flow.value = next
        }
    }

    suspend fun replace(items: List<T>) {
        update { items }
    }

    /** A file that cannot be parsed is set aside under a `.corrupt` suffix instead of being
     *  overwritten, so its contents can still be recovered. */
    private fun load(): List<T> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(listSerializer, file.readText())
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".corrupt"))
            emptyList()
        }
    }

    private fun write(items: List<T>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(listSerializer, items))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) temp.delete()
        }
    }
}
