package com.kevshupp.kevmusicplayer.playback

import android.content.Context
import com.kevshupp.kevmusicplayer.data.AudioFile
import org.json.JSONArray
import org.json.JSONObject

val REGEX_SUFFIX_PARENTHESIS = Regex("\\s*\\(\\d+\\)\\.[a-zA-Z0-9]+$")
val REGEX_SUFFIX_COPIA = Regex("\\s*-\\s*[Cc]opia\\.[a-zA-Z0-9]+$")
val REGEX_SUFFIX_UNDERSCORE = Regex("\\s*_\\d+\\.[a-zA-Z0-9]+$")

val REGEX_CLEAN_PARENTHESIS = Regex("\\s*\\(\\d+\\)$")
val REGEX_CLEAN_COPIA = Regex("\\s*-\\s*[Cc]opia$")
val REGEX_CLEAN_UNDERSCORE = Regex("\\s*_\\d+$")

enum class SmartPlaylistRule {
    MOST_PLAYED, RECENTLY_ADDED, PLAYBACK_HISTORY, LONGEST_SONGS, SHORTEST_SONGS, NEVER_PLAYED, RANDOM_MIX
}

enum class LogicalOperator {
    AND, OR
}

enum class RuleField {
    TITLE, ARTIST, ALBUM, GENRE, YEAR, DURATION_SECONDS, PLAY_COUNT, LAST_PLAYED_DAYS, DATE_ADDED_DAYS
}

enum class RuleOperator {
    EQUALS, CONTAINS, GREATER_THAN, LESS_THAN, STARTS_WITH, ENDS_WITH
}

sealed class SmartRuleNode {
    abstract fun evaluate(context: android.content.Context, song: AudioFile): Boolean
    abstract fun toJson(): JSONObject

    companion object {
        fun fromJson(json: JSONObject): SmartRuleNode {
            return if (json.has("operator")) {
                val op = LogicalOperator.valueOf(json.getString("operator"))
                val childrenJson = json.getJSONArray("children")
                val children = mutableListOf<SmartRuleNode>()
                for (i in 0 until childrenJson.length()) {
                    children.add(fromJson(childrenJson.getJSONObject(i)))
                }
                GroupNode(op, children)
            } else {
                val field = RuleField.valueOf(json.getString("field"))
                val op = RuleOperator.valueOf(json.getString("operator_type"))
                val value = json.getString("value")
                ConditionNode(field, op, value)
            }
        }
    }
}

data class ConditionNode(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String
) : SmartRuleNode() {

    override fun evaluate(context: android.content.Context, song: AudioFile): Boolean {
        return try {
            val fieldValue: String = when (field) {
                RuleField.TITLE -> song.title
                RuleField.ARTIST -> song.artist
                RuleField.ALBUM -> song.album
                RuleField.GENRE -> song.genre ?: ""
                RuleField.YEAR -> song.year
                RuleField.DURATION_SECONDS -> (song.duration / 1000).toString()
                RuleField.PLAY_COUNT -> song.playCount.toString()
                RuleField.LAST_PLAYED_DAYS -> {
                    if (song.lastPlayed <= 0L) "-1"
                    else {
                        val diffMs = System.currentTimeMillis() - song.lastPlayed
                        val diffDays = diffMs / (1000L * 60 * 60 * 24)
                        diffDays.toString()
                    }
                }
                RuleField.DATE_ADDED_DAYS -> {
                    val diffMs = System.currentTimeMillis() - song.dateAdded
                    val diffDays = diffMs / (1000L * 60 * 60 * 24)
                    diffDays.toString()
                }
            }

            when (operator) {
                RuleOperator.EQUALS -> fieldValue.equals(value, ignoreCase = true)
                RuleOperator.CONTAINS -> fieldValue.contains(value, ignoreCase = true)
                RuleOperator.STARTS_WITH -> fieldValue.startsWith(value, ignoreCase = true)
                RuleOperator.ENDS_WITH -> fieldValue.endsWith(value, ignoreCase = true)
                RuleOperator.GREATER_THAN -> {
                    val fieldNum = fieldValue.toDoubleOrNull() ?: 0.0
                    val valNum = value.toDoubleOrNull() ?: 0.0
                    fieldNum > valNum
                }
                RuleOperator.LESS_THAN -> {
                    val fieldNum = fieldValue.toDoubleOrNull() ?: 0.0
                    val valNum = value.toDoubleOrNull() ?: 0.0
                    fieldNum < valNum
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("field", field.name)
        json.put("operator_type", operator.name)
        json.put("value", value)
        return json
    }
}

data class GroupNode(
    val operator: LogicalOperator,
    val children: List<SmartRuleNode>
) : SmartRuleNode() {
    override fun evaluate(context: android.content.Context, song: AudioFile): Boolean {
        if (children.isEmpty()) return true
        return when (operator) {
            LogicalOperator.AND -> children.all { it.evaluate(context, song) }
            LogicalOperator.OR -> children.any { it.evaluate(context, song) }
        }
    }

    override fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("operator", operator.name)
        val arr = JSONArray()
        children.forEach { arr.put(it.toJson()) }
        json.put("children", arr)
        return json
    }
}

data class SmartPlaylistConfig(
    val name: String,
    val rule: SmartPlaylistRule,
    val limit: Int = 50,
    val isAdvanced: Boolean = false,
    val advancedRule: SmartRuleNode? = null
)

