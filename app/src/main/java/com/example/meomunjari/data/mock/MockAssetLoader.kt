package com.example.meomunjari.data.mock

import android.content.Context
import com.example.meomunjari.core.model.MeasurementReport
import com.example.meomunjari.core.model.Review
import com.example.meomunjari.core.model.SettingPreset
import com.example.meomunjari.core.model.Spot
import com.example.meomunjari.core.model.TagDictionary
import com.example.meomunjari.core.model.UserData
import kotlinx.serialization.json.Json

/** app/src/main/assets/mock 폴더의 JSON 을 읽는다. */
object MockAssetLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun load(context: Context): MockSeed = MockSeed(
        spots = json.decodeFromString<List<Spot>>(read(context, "spots.json")),
        reviews = json.decodeFromString<List<Review>>(read(context, "reviews.json")),
        reports = json.decodeFromString<List<MeasurementReport>>(read(context, "reports.json")),
        presets = json.decodeFromString<List<SettingPreset>>(read(context, "presets.json")),
        user = json.decodeFromString<UserData>(read(context, "user.json")),
        tags = json.decodeFromString<TagDictionary>(read(context, "tags.json")),
        zones = WaterProtectionZones.read(read(context, WaterProtectionZones.ASSET)),
    )

    private fun read(context: Context, name: String): String =
        context.assets.open("mock/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
