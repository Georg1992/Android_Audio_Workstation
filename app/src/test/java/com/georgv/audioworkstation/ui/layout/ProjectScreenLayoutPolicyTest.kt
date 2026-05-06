package com.georgv.audioworkstation.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectScreenLayoutPolicyTest {

    @Test
    fun `slots increase or stay stable when viewport height grows`() {
        val env = layoutEnvironmentFromScreen(411f, 915f, isLandscape = false, density = 2.625f)
        val shortViewport = projectTrackLayoutSpec(env, 320.dp, 380.dp)
        val tallViewport = projectTrackLayoutSpec(env, 640.dp, 380.dp)

        assertTrue(shortViewport.trackSlotHeight.value > 0f)
        assertTrue(tallViewport.targetVisibleTrackSlots >= shortViewport.targetVisibleTrackSlots)
    }

    @Test
    fun `chosen row height partitions viewport with spacing`() {
        val env = layoutEnvironmentFromScreen(411f, 915f, isLandscape = false, density = 2f)
        val viewport = 508.dp
        val spec = projectTrackLayoutSpec(env, viewport, 400.dp)
        val n = spec.targetVisibleTrackSlots.toFloat()
        val gap = spec.listVerticalSpacing
        val used = spec.trackSlotHeight * n + gap * (n - 1f)

        assertEquals(viewport.value, used.value, 0.001f)
    }

    @Test
    fun `tablet uses max feasible slots when viewport is tall`() {
        val env = layoutEnvironmentFromScreen(900f, 1200f, isLandscape = false, density = 2f)
        val spec = projectTrackLayoutSpec(env, 2000.dp, 400.dp)
        assertEquals(8, spec.targetVisibleTrackSlots)
    }

    @Test
    fun `phone sized screen biases four visible slots when feasible`() {
        val env = layoutEnvironmentFromScreen(411f, 915f, isLandscape = false, density = 2f)
        val wideList = projectTrackLayoutSpec(env, 2000.dp, 400.dp)
        val narrowList = projectTrackLayoutSpec(env, 2000.dp, 320.dp)
        assertEquals(4, wideList.targetVisibleTrackSlots)
        assertEquals(4, narrowList.targetVisibleTrackSlots)
    }
}

class LayoutEnvironmentTest {

    @Test
    fun `landscape swaps width height basis for shortest longest`() {
        val portrait =
            layoutEnvironmentFromScreen(
                screenWidthDp = 420f,
                screenHeightDp = 900f,
                isLandscape = false,
                density = 3f,
            )
        assertEquals(420f.dp, portrait.availableWidth)

        val landscape =
            layoutEnvironmentFromScreen(
                screenWidthDp = 900f,
                screenHeightDp = 420f,
                isLandscape = true,
                density = 3f,
            )
        assertEquals(900f.dp, landscape.availableWidth)
        assertTrue(portrait.availableHeight.value > landscape.availableHeight.value)
    }
}
