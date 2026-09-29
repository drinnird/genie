package com.geniex.demo.model

import android.content.Context
import com.geniex.demo.bean.ModelData
import kotlinx.serialization.json.Json

object ModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<ModelData> =
        context.assets.open("model_list.json").bufferedReader().use { reader ->
            json.decodeFromString<List<ModelData>>(reader.readText())
        }
}
