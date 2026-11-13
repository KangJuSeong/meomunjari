package com.example.meomunjari

import com.example.meomunjari.core.model.Amenities
import com.example.meomunjari.core.model.CarProfile
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.PowerType
import com.example.meomunjari.core.model.RestrictedZone
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagCount
import com.example.meomunjari.core.model.TagDictionary
import com.example.meomunjari.core.model.UserData
import com.example.meomunjari.core.model.VehicleClass
import com.example.meomunjari.core.model.Visit
import com.example.meomunjari.data.mock.MockSeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 실제 목 데이터(src/main/assets/mock 폴더의 JSON)를 읽어 테스트에 쓴다.
 * 직렬화 플러그인 없이도 돌도록 JsonElement를 직접 매핑한다.
 */
object Fixtures {
    private val dir: File by lazy {
        listOf("src/main/assets/mock", "app/src/main/assets/mock", System.getProperty("mock.dir").orEmpty())
            .map(::File)
            .first { it.resolve("spots.json").exists() }
    }

    private fun read(name: String): JsonElement = Json.parseToJsonElement(dir.resolve(name).readText())

    fun seed(): MockSeed = MockSeed(
        spots = read("spots.json").jsonArray.map { spot(it.jsonObject) },
        reviews = read("reviews.json").jsonArray.map { review(it.jsonObject) },
        reports = read("reports.json").jsonArray.map { report(it.jsonObject) },
        presets = read("presets.json").jsonArray.map { preset(it.jsonObject) },
        user = user(read("user.json").jsonObject),
        tags = tags(read("tags.json").jsonObject),
        zones = read("restricted_zones.json").jsonArray.map { zone(it.jsonObject) },
    )

    private fun JsonObject.s(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.d(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double
    private fun JsonObject.i(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
    private fun JsonObject.b(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun spot(o: JsonObject) = Spot(
        id = o.s("id")!!, name = o.s("name")!!, region = o.s("region")!!, lat = o.d("lat")!!, lng = o.d("lng")!!,
        overnightTemperaturesC = o["overnightTemperaturesC"]?.jsonArray.orEmpty().map { it.jsonPrimitive.double },
        hourlyTemperaturesC = o["hourlyTemperaturesC"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.double },
        radiusM = o.i("radiusM") ?: 1000, description = o.s("description").orEmpty(),
        amenities = o["amenities"]!!.jsonObject.let {
            Amenities(it.b("toilet") ?: false, it.i("convenienceStoreM"), it.s("ground").orEmpty(), it.s("noise").orEmpty(), it.b("water") ?: false)
        },
        tags = o["tags"]!!.jsonArray.map { TagCount(it.jsonObject.s("tag")!!, it.jsonObject.i("count")!!) },
        rating = o.d("rating") ?: 0.0, ratingCount = o.i("ratingCount") ?: 0, visitCount = o.i("visitCount") ?: 0,
        lastVisitedAt = o.s("lastVisitedAt"), createdAt = o.s("createdAt").orEmpty(), registeredBy = o.s("registeredBy").orEmpty(),
        maxVehicleClass = VehicleClass.valueOf(o.s("maxVehicleClass")!!),
        visitedVehicleClasses = o["visitedVehicleClasses"]!!.jsonObject.mapKeys { VehicleClass.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.int },
        liveCampers = o.i("liveCampers") ?: 0, typicalWeekendCampers = o.s("typicalWeekendCampers").orEmpty(),
        photoColors = o.strings("photoColors"),
    )

    private fun review(o: JsonObject) = Review(
        o.s("id")!!, o.s("spotId")!!, o.s("author")!!, o.s("authorGrade").orEmpty(), o.i("rating")!!, o.s("text")!!,
        o.s("createdAt")!!, o.strings("tags"), o.s("reportId"), o.s("presetId"),
    )

    private fun report(o: JsonObject) = MeasurementReport(
        id = o.s("id")!!, spotId = o.s("spotId")!!, month = o.i("month")!!, powerType = PowerType.valueOf(o.s("powerType")!!),
        vehicleClass = VehicleClass.valueOf(o.s("vehicleClass")!!), minCabinTempC = o.d("minCabinTempC")!!,
        avgCabinTempC = o.d("avgCabinTempC"), hvacHours = o.d("hvacHours")!!, energyUsedPct = o.d("energyUsedPct")!!,
        durationHours = o.d("durationHours")!!, rained = o.b("rained")!!, createdAt = o.s("createdAt").orEmpty(),
        author = o.s("author").orEmpty(), outsideMinC = o.d("outsideMinC"), outsideMaxC = o.d("outsideMaxC"),
        devices = o.strings("devices"),
    )

    private fun preset(o: JsonObject) = SettingPreset(
        id = o.s("id")!!, spotId = o.s("spotId"), ownerNickname = o.s("ownerNickname")!!, ownerGrade = o.s("ownerGrade").orEmpty(),
        title = o.s("title").orEmpty(), targetTempC = o.d("targetTempC") ?: 21.0, hvacOn = o.b("hvacOn") ?: true,
        seatMemorySlot = o.i("seatMemorySlot"), backrestAngleDeg = o.i("backrestAngleDeg"), windowOpenPct = o.i("windowOpenPct") ?: 0,
        openDoorArea = o.s("openDoorArea"), ambientOn = o.b("ambientOn") ?: false, ambientBrightness = o.i("ambientBrightness") ?: 30,
        loadCount = o.i("loadCount") ?: 0, ownerRating = o.d("ownerRating") ?: 0.0,
    )

    private fun user(o: JsonObject) = UserData(
        nickname = o.s("nickname")!!,
        car = o["car"]!!.jsonObject.let {
            CarProfile(it.s("model")!!, VehicleClass.valueOf(it.s("vehicleClass")!!), PowerType.valueOf(it.s("powerType")!!),
                it.d("batteryKWh") ?: 77.4, it.d("efficiencyKmPerKWh") ?: 5.0, hvacPowerProfile = it.s("hvacPowerProfile"))
        },
        mySetting = preset(o["mySetting"]!!.jsonObject),
        visits = o["visits"]!!.jsonArray.map { v -> v.jsonObject.let { Visit(it.s("id")!!, it.s("spotId")!!, it.s("date")!!, it.i("month")!!) } },
        registeredSpotIds = o.strings("registeredSpotIds"),
        reportCount = o.i("reportCount") ?: 0, reportCredits = o.i("reportCredits") ?: 0, reviewCount = o.i("reviewCount") ?: 0,
        presetLoadsReceived = o.i("presetLoadsReceived") ?: 0, consentToRecord = o.b("consentToRecord") ?: false,
    )

    private fun tags(o: JsonObject) = TagDictionary(
        categories = o["categories"]!!.jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content } },
        synonyms = o["synonyms"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        seasonal = o["seasonal"]!!.jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content } },
    )

    private fun zone(o: JsonObject) = RestrictedZone(o.s("name")!!, o.d("lat")!!, o.d("lng")!!, o.i("radiusM")!!, o.s("reason")!!)
}
