package com.kevshupp.kevmusicplayer.playback

import com.kevshupp.kevmusicplayer.data.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRulesTest {

    private fun createSong(
        id: Long = 1L,
        title: String = "Test Song",
        artist: String = "Test Artist",
        album: String = "Test Album",
        genre: String = "Rock",
        duration: Long = 180_000L, // 180s = 3m
        playCount: Int = 10,
        year: String = "2024"
    ): AudioFile {
        return AudioFile(
            id = id,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            duration = duration,
            uriString = "content://media/external/audio/media/$id",
            playCount = playCount,
            year = year
        )
    }

    @Test
    fun conditionNode_evaluatesStringOperators() {
        val song = createSong(title = "Bohemian Rhapsody", artist = "Queen", genre = "Classic Rock")

        val equalsCond = ConditionNode(RuleField.ARTIST, RuleOperator.EQUALS, "Queen")
        assertTrue(equalsCond.evaluate(null, song))

        val containsCond = ConditionNode(RuleField.TITLE, RuleOperator.CONTAINS, "Rhapsody")
        assertTrue(containsCond.evaluate(null, song))

        val startsWithCond = ConditionNode(RuleField.GENRE, RuleOperator.STARTS_WITH, "Classic")
        assertTrue(startsWithCond.evaluate(null, song))

        val endsWithCond = ConditionNode(RuleField.TITLE, RuleOperator.ENDS_WITH, "Rhapsody")
        assertTrue(endsWithCond.evaluate(null, song))

        val failCond = ConditionNode(RuleField.ARTIST, RuleOperator.EQUALS, "Metallica")
        assertFalse(failCond.evaluate(null, song))
    }

    @Test
    fun conditionNode_evaluatesNumericOperators() {
        val song = createSong(duration = 240_000L, playCount = 15) // 240 seconds

        val gtDuration = ConditionNode(RuleField.DURATION_SECONDS, RuleOperator.GREATER_THAN, "200")
        assertTrue(gtDuration.evaluate(null, song))

        val ltDuration = ConditionNode(RuleField.DURATION_SECONDS, RuleOperator.LESS_THAN, "300")
        assertTrue(ltDuration.evaluate(null, song))

        val gtPlayCount = ConditionNode(RuleField.PLAY_COUNT, RuleOperator.GREATER_THAN, "20")
        assertFalse(gtPlayCount.evaluate(null, song))

        val ltPlayCount = ConditionNode(RuleField.PLAY_COUNT, RuleOperator.LESS_THAN, "20")
        assertTrue(ltPlayCount.evaluate(null, song))
    }

    @Test
    fun groupNode_evaluatesAndOrLogic() {
        val song = createSong(title = "Stairway to Heaven", artist = "Led Zeppelin", playCount = 25)

        val cond1 = ConditionNode(RuleField.ARTIST, RuleOperator.EQUALS, "Led Zeppelin")
        val cond2 = ConditionNode(RuleField.PLAY_COUNT, RuleOperator.GREATER_THAN, "20")
        val cond3 = ConditionNode(RuleField.TITLE, RuleOperator.EQUALS, "Wrong Title")

        val andGroup = GroupNode(LogicalOperator.AND, listOf(cond1, cond2))
        assertTrue(andGroup.evaluate(null, song))

        val failingAndGroup = GroupNode(LogicalOperator.AND, listOf(cond1, cond3))
        assertFalse(failingAndGroup.evaluate(null, song))

        val orGroup = GroupNode(LogicalOperator.OR, listOf(cond1, cond3))
        assertTrue(orGroup.evaluate(null, song))
    }

    @Test
    fun smartRuleNode_jsonSerializationRoundTrip() {
        val cond1 = ConditionNode(RuleField.ARTIST, RuleOperator.EQUALS, "Pink Floyd")
        val cond2 = ConditionNode(RuleField.PLAY_COUNT, RuleOperator.GREATER_THAN, "5")
        val group = GroupNode(LogicalOperator.AND, listOf(cond1, cond2))

        val json = group.toJson()
        val restoredNode = SmartRuleNode.fromJson(json)

        assertTrue(restoredNode is GroupNode)
        val restoredGroup = restoredNode as GroupNode
        assertEquals(LogicalOperator.AND, restoredGroup.operator)
        assertEquals(2, restoredGroup.children.size)

        val restoredCond1 = restoredGroup.children[0] as ConditionNode
        assertEquals(RuleField.ARTIST, restoredCond1.field)
        assertEquals(RuleOperator.EQUALS, restoredCond1.operator)
        assertEquals("Pink Floyd", restoredCond1.value)
    }
}
