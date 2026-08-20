package com.tracking.familyorbit.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL

class OrbitUnauthorizedException(message: String) : IllegalStateException(message)

class FamilyOrbitApi(private val baseUrl: String, context: Context? = null) {
    private val applicationContext = context?.applicationContext
    private val isLocalDevelopmentServer = LocalNetworkAccess.isRequiredFor(baseUrl)
    data class LoginResult(val accessToken: String, val refreshToken: String, val userId: String)
    data class PairResult(val deviceToken: String, val childId: String, val pauseRestricted: Boolean)
    data class DeviceConfig(val pauseRestricted: Boolean, val trackingState: String)
    data class CreatedChild(val id: String, val name: String, val color: String)
    data class PairingCode(val code: String, val expiresAt: String, val qrPayload: String, val pauseRestricted: Boolean)

    fun login(email: String, password: String): LoginResult {
        val result = request(
            "/auth/login",
            "POST",
            body = JSONObject().put("email", email).put("password", password).put("clientType", "parent_android"),
        )
        return LoginResult(
            result.getString("accessToken"),
            result.optString("refreshToken"),
            result.optJSONObject("user")?.optString("id").orEmpty(),
        )
    }

    fun refresh(refreshToken: String): LoginResult {
        val result = request(
            "/auth/refresh",
            "POST",
            body = JSONObject().put("token", refreshToken),
        )
        return LoginResult(result.getString("accessToken"), result.optString("refreshToken"), "")
    }

    fun dashboard(accessToken: String): OrbitDashboard = OrbitDashboard.fromJson(request("/dashboard", token = "Bearer $accessToken"))

	fun historyDays(accessToken: String, childId: String): List<OrbitHistoryDay> =
		request("/children/$childId/history-days", token = "Bearer $accessToken").optJSONArray("days").mapObjects {
			OrbitHistoryDay(it.optString("date"), it.optInt("pointCount"), it.optString("firstRecordedAt"), it.optString("lastRecordedAt"))
		}

	fun history(accessToken: String, childId: String, from: String, to: String): List<OrbitHistoryPoint> =
		request("/children/$childId/history?from=${java.net.URLEncoder.encode(from, "UTF-8")}&to=${java.net.URLEncoder.encode(to, "UTF-8")}", token = "Bearer $accessToken")
			.optJSONArray("items").mapObjects {
				OrbitHistoryPoint(it.optDouble("latitude"), it.optDouble("longitude"), it.optDouble("accuracy"), it.optString("recordedAt"), it.optDouble("batteryLevel"))
			}

	fun createZone(accessToken: String, name: String, latitude: Double, longitude: Double, radiusMeters: Double, childIds: List<String>): OrbitZone =
		zoneFromResponse(request("/zones", "POST", "Bearer $accessToken", zoneBody(name, latitude, longitude, radiusMeters, childIds)))

	fun updateZone(accessToken: String, zoneId: String, name: String, latitude: Double, longitude: Double, radiusMeters: Double, childIds: List<String>): OrbitZone =
		zoneFromResponse(request("/zones/$zoneId", "PATCH", "Bearer $accessToken", zoneBody(name, latitude, longitude, radiusMeters, childIds)))

	fun deleteZone(accessToken: String, zoneId: String) {
		request("/zones/$zoneId", "DELETE", "Bearer $accessToken")
	}

	fun sendMessage(accessToken: String, childId: String, clientMessageId: String, body: String): OrbitMessage =
		messageFromJson(request("/children/$childId/messages", "POST", "Bearer $accessToken", JSONObject().put("clientMessageId", clientMessageId).put("body", body)))

	fun messages(accessToken: String, childId: String): List<OrbitMessage> =
		request("/children/$childId/messages", token = "Bearer $accessToken").optJSONArray("messages").mapObjects(::messageFromJson)

	fun registerGuardianPush(accessToken: String, pushToken: String, deviceName: String) {
		request("/devices/push", "POST", "Bearer $accessToken", JSONObject().put("deviceName", deviceName).put("platform", "android").put("pushToken", pushToken))
	}

    fun createChild(accessToken: String, name: String, color: String = "#C9FF4A"): CreatedChild {
        val result = request(
            "/children",
            "POST",
            "Bearer $accessToken",
            JSONObject().put("name", name).put("color", color),
        )
        return CreatedChild(result.getString("id"), result.getString("name"), result.optString("color", color))
    }

    fun createPairingCode(accessToken: String, childId: String, pauseRestricted: Boolean): PairingCode {
        val result = request(
            "/children/$childId/pairing-code",
            "POST",
            "Bearer $accessToken",
            JSONObject().put("pauseRestricted", pauseRestricted),
        )
        return PairingCode(result.getString("code"), result.getString("expiresAt"), result.getString("qrPayload"), result.optBoolean("pauseRestricted"))
    }

    fun deleteChild(accessToken: String, childId: String) {
        request("/children/$childId", "DELETE", "Bearer $accessToken")
    }

    fun deleteAccount(accessToken: String) {
        request("/account", "DELETE", "Bearer $accessToken")
    }

    fun pair(code: String, deviceName: String, platform: String = "android"): PairResult {
        val result = request(
            "/pairing",
            "POST",
            body = JSONObject().put("code", code).put("deviceName", deviceName).put("platform", platform),
        )
        return PairResult(result.getString("deviceToken"), result.getString("childId"), result.optBoolean("pauseRestricted"))
    }

    fun sendLocations(deviceToken: String, idempotencyKey: String, samples: JSONArray, trackingState: String) {
        request(
            "/device/locations",
            "POST",
            "Device $deviceToken",
            JSONObject().put("idempotencyKey", idempotencyKey).put("trackingState", trackingState).put("samples", samples),
        )
    }

    fun sendTrackingState(deviceToken: String, state: String, reason: String) {
        request(
            "/device/tracking-state",
            "POST",
            "Device $deviceToken",
            JSONObject().put("state", state).put("reason", reason),
        )
    }

    fun deviceZones(deviceToken: String): JSONArray = request("/device/zones", token = "Device $deviceToken").optJSONArray("zones") ?: JSONArray()

	fun registerDevicePush(deviceToken: String, pushToken: String) {
		request("/device/push", "POST", "Device $deviceToken", JSONObject().put("pushToken", pushToken))
	}

	fun deviceMessages(deviceToken: String): List<OrbitMessage> =
		request("/device/messages", token = "Device $deviceToken").optJSONArray("messages").mapObjects(::messageFromJson)

	fun deviceConfig(deviceToken: String): DeviceConfig {
		val result = request("/device/config", token = "Device $deviceToken")
		return DeviceConfig(result.optBoolean("pauseRestricted"), result.optString("trackingState", "paused"))
	}

	fun markMessageRead(deviceToken: String, messageId: String): OrbitMessage =
		messageFromJson(request("/device/messages/$messageId/read", "POST", "Device $deviceToken", JSONObject()))

	fun unpairDevice(deviceToken: String) {
		request("/device", "DELETE", "Device $deviceToken")
	}

    private fun request(path: String, method: String = "GET", token: String? = null, body: JSONObject? = null): JSONObject {
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        var lastConnectError: IOException? = null
        for (attempt in 0..1) {
            val candidate = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            candidate.requestMethod = method
            candidate.connectTimeout = 5_000
            candidate.readTimeout = 15_000
            candidate.setRequestProperty("Accept", "application/json")
            candidate.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) candidate.setRequestProperty("Authorization", token)
            if (payload != null) {
                candidate.doOutput = true
                candidate.setFixedLengthStreamingMode(payload.size)
            }
            try {
                candidate.connect()
                connection = candidate
                break
            } catch (error: IOException) {
                candidate.disconnect()
                if (error !is ConnectException && error !is SocketTimeoutException) throw friendlyConnectionError(error)
                lastConnectError = error
                if (attempt == 0) Thread.sleep(350)
            }
        }
        val activeConnection = connection ?: throw friendlyConnectionError(lastConnectError)
        return try {
            if (payload != null) {
                activeConnection.outputStream.use { it.write(payload) }
            }
            val responseCode = activeConnection.responseCode
            val stream = if (responseCode in 200..299) activeConnection.inputStream else activeConnection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "通信に失敗しました ($responseCode)"
                if (responseCode == 401) throw OrbitUnauthorizedException(message)
                throw IllegalStateException(message)
            }
			if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
        } catch (error: IOException) {
            throw friendlyConnectionError(error)
        } finally {
            activeConnection.disconnect()
        }
    }

    private fun friendlyConnectionError(cause: Throwable?): IllegalStateException {
        if (cause != null) Log.w("FamilyOrbitApi", "API connection failed", cause)
        val localNetworkPermissionDenied =
            Build.VERSION.SDK_INT >= 37 &&
                isLocalDevelopmentServer &&
                applicationContext?.let {
                    ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                        PackageManager.PERMISSION_GRANTED
                } == true
        return IllegalStateException(
            if (localNetworkPermissionDenied) {
                "開発サーバーへ接続するには、端末設定でFamily Orbitの「ローカルネットワーク」権限を許可してください。"
            } else {
                "サーバーに接続できません。Family Orbitサーバーの起動と端末の通信を確認し、もう一度お試しください。"
            },
            cause,
        )
    }

	private fun zoneBody(name: String, latitude: Double, longitude: Double, radiusMeters: Double, childIds: List<String>) = JSONObject()
		.put("name", name).put("latitude", latitude).put("longitude", longitude).put("radiusMeters", radiusMeters)
		.put("color", "#72E8C0").put("childIds", JSONArray(childIds)).put("enabled", true)

	private fun zoneFromResponse(json: JSONObject) = OrbitZone(
		id = json.optString("id", json.optString("_id")), name = json.optString("name"),
		latitude = json.optJSONObject("center")?.optJSONArray("coordinates")?.optDouble(1) ?: json.optDouble("latitude"),
		longitude = json.optJSONObject("center")?.optJSONArray("coordinates")?.optDouble(0) ?: json.optDouble("longitude"),
		radiusMeters = json.optDouble("radiusMeters"), color = json.optString("color", "#72E8C0"), childIds = json.optJSONArray("childIds").mapStrings(),
	)

	private fun messageFromJson(json: JSONObject) = OrbitMessage(
		id = json.optString("id"), childId = json.optString("childId"), clientMessageId = json.optString("clientMessageId"),
		body = json.optString("body"), deliveryState = json.optString("deliveryState"), createdAt = json.optString("createdAt"),
		pushedAt = json.optString("pushedAt").takeIf(String::isNotBlank), readAt = json.optString("readAt").takeIf(String::isNotBlank),
	)
}

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
	if (this == null) return emptyList()
	return buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }
}

private fun JSONArray?.mapStrings(): List<String> {
	if (this == null) return emptyList()
	return buildList { for (index in 0 until length()) add(optString(index)) }
}
