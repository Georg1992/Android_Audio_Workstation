package com.georgv.audioworkstation.ui.screens.community

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedCardMarksTest {
    @Test
    fun ownerAndMixPublishAgainOnlyWhenTheValueChanges() {
        val marks = PublishedCardMarks()

        assertFalse(marks.ownerUnchanged("project-a", "Ada"))
        marks.markOwner("project-a", "Ada")
        assertTrue(marks.ownerUnchanged("project-a", "Ada"))
        assertFalse(marks.ownerUnchanged("project-a", "Georg"))
        assertFalse(marks.ownerUnchanged("project-b", "Ada"))

        assertFalse(marks.mixUnchanged("project-a", "hash-1"))
        marks.markMix("project-a", "hash-1")
        assertTrue(marks.mixUnchanged("project-a", "hash-1"))
        assertFalse(marks.mixUnchanged("project-a", "hash-2"))
    }
}
