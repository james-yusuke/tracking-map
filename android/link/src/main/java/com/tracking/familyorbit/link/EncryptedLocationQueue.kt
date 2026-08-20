package com.tracking.familyorbit.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class QueuedLocation(
    val id: String,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val batteryLevel: Float,
    val isCharging: Boolean,
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("recordedAt", recordedAt)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy", accuracy.toDouble())
        .put("speed", speed.toDouble())
        .put("batteryLevel", batteryLevel)
        .put("isCharging", isCharging)

    fun toApiJson() = JSONObject()
        .put("recordedAt", java.time.Instant.ofEpochMilli(recordedAt).toString())
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy", accuracy.toDouble())
        .put("speed", speed.toDouble())
        .put("batteryLevel", batteryLevel)
        .put("isCharging", isCharging)

    companion object {
        fun fromJson(value: JSONObject) = QueuedLocation(
            id = value.optString("id", UUID.randomUUID().toString()),
            recordedAt = value.getLong("recordedAt"),
            latitude = value.getDouble("latitude"),
            longitude = value.getDouble("longitude"),
            accuracy = value.getDouble("accuracy").toFloat(),
            speed = value.optDouble("speed").toFloat(),
            batteryLevel = value.optDouble("batteryLevel").toFloat(),
            isCharging = value.optBoolean("isCharging"),
        )
    }
}

class EncryptedLocationQueue(context: Context) {
    private val file = File(context.noBackupFilesDir, "location-queue.bin")
    private val alias = "family-orbit-location-queue"

    @Synchronized
    fun append(location: QueuedLocation) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val current = read().filter { it.recordedAt >= cutoff }.toMutableList()
        current += location
        write(current.takeLast(3_000))
    }

    @Synchronized
    fun pending(): List<QueuedLocation> = read()

    @Synchronized
    fun remove(ids: Set<String>) = write(read().filterNot { it.id in ids })

    @Synchronized
    fun clear() = write(emptyList())

    private fun read(): List<QueuedLocation> = runCatching {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size <= 12) return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        buildList { for (index in 0 until json.length()) add(QueuedLocation.fromJson(json.getJSONObject(index))) }
    }.getOrElse { emptyList() }

    private fun write(values: List<QueuedLocation>) {
        if (values.isEmpty()) {
            file.delete()
            return
        }
        val json = JSONArray().apply { values.forEach { put(it.toJson()) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        file.writeBytes(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
