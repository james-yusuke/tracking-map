package com.tracking.familyorbit.core

import org.json.JSONArray
import org.json.JSONObject

data class OrbitLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val recordedAt: String,
    val batteryLevel: Double,
    val isCharging: Boolean,
)

data class OrbitChild(
    val id: String,
    val name: String,
    val color: String,
    val trackingState: String,
    val connectivity: String,
    val location: OrbitLocation?,
)

data class OrbitZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
	val color: String = "#72E8C0",
	val childIds: List<String> = emptyList(),
)

data class OrbitAlert(
    val id: String,
    val type: String,
    val title: String,
    val message: String,
    val occurredAt: String,
)

data class OrbitHistoryDay(
	val date: String,
	val pointCount: Int,
	val firstRecordedAt: String,
	val lastRecordedAt: String,
)

data class OrbitHistoryPoint(
	val latitude: Double,
	val longitude: Double,
	val accuracy: Double,
	val recordedAt: String,
	val batteryLevel: Double,
)

data class OrbitMessage(
	val id: String,
	val childId: String,
	val clientMessageId: String,
	val body: String,
	val deliveryState: String,
	val createdAt: String,
	val pushedAt: String?,
	val readAt: String?,
)

data class OrbitDashboard(
    val familyName: String,
    val children: List<OrbitChild>,
    val zones: List<OrbitZone>,
    val alerts: List<OrbitAlert>,
) {
    companion object {
        fun fromJson(json: JSONObject): OrbitDashboard = OrbitDashboard(
            familyName = json.optJSONObject("family")?.optString("name") ?: json.optString("familyName", "わたしの家族"),
            children = json.optJSONArray("children").mapObjects(::childFromJson),
            zones = json.optJSONArray("zones").mapObjects(::zoneFromJson),
            alerts = json.optJSONArray("alerts").mapObjects(::alertFromJson),
        )
    }
}

private fun childFromJson(json: JSONObject): OrbitChild {
    val location = json.optJSONObject("latestLocation")?.let {
        OrbitLocation(
            latitude = it.optDouble("latitude"),
            longitude = it.optDouble("longitude"),
            accuracy = it.optDouble("accuracy"),
            recordedAt = it.optString("recordedAt"),
            batteryLevel = it.optDouble("batteryLevel"),
            isCharging = it.optBoolean("isCharging"),
        )
    }
    return OrbitChild(
        id = json.optString("id"),
        name = json.optString("name"),
        color = json.optString("color", "#C7FF4A"),
        trackingState = json.optString("trackingState", "offline"),
        connectivity = json.optString("connectivity", "offline"),
        location = location,
    )
}

private fun zoneFromJson(json: JSONObject) = OrbitZone(
    id = json.optString("id"),
    name = json.optString("name"),
    latitude = json.optDouble("latitude"),
    longitude = json.optDouble("longitude"),
    radiusMeters = json.optDouble("radiusMeters"),
	color = json.optString("color", "#72E8C0"),
	childIds = json.optJSONArray("childIds").mapStrings(),
)

private fun alertFromJson(json: JSONObject) = OrbitAlert(
    id = json.optString("id"),
    type = json.optString("type"),
    title = json.optString("title"),
    message = json.optString("message"),
    occurredAt = json.optString("occurredAt"),
)

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) add(transform(getJSONObject(index)))
    }
}

private fun JSONArray?.mapStrings(): List<String> {
	if (this == null) return emptyList()
	return buildList { for (index in 0 until length()) add(optString(index)) }
}
