package com.speedcards.data.db

import androidx.room.TypeConverter
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.Rating
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun stringsToJson(v: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), v)
    @TypeConverter fun jsonToStrings(v: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), v)

    @TypeConverter fun fieldDefsToJson(v: List<FieldDef>): String = json.encodeToString(ListSerializer(FieldDef.serializer()), v)
    @TypeConverter fun jsonToFieldDefs(v: String): List<FieldDef> = json.decodeFromString(ListSerializer(FieldDef.serializer()), v)

    @TypeConverter fun templatesToJson(v: List<CardTemplateDef>): String =
        json.encodeToString(ListSerializer(CardTemplateDef.serializer()), v)
    @TypeConverter fun jsonToTemplates(v: String): List<CardTemplateDef> =
        json.decodeFromString(ListSerializer(CardTemplateDef.serializer()), v)

    @TypeConverter fun cardStateToInt(v: CardState): Int = v.ordinal
    @TypeConverter fun intToCardState(v: Int): CardState = CardState.entries[v]

    @TypeConverter fun ratingToInt(v: Rating): Int = v.ordinal
    @TypeConverter fun intToRating(v: Int): Rating = Rating.entries[v]
}
