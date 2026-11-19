package com.georgv.audioworkstation.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import com.georgv.audioworkstation.ui.theme.appLightColorScheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
class ProjectTransportPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun projectTransportKeepsPlayStopAndShowsTheRuler() {
        compose.setContent {
            ProjectTransportHarness()
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import audio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mix and export project").assertIsDisplayed()
        compose.onNodeWithText("0:12").assertIsDisplayed()
        compose.onNodeWithText("0:30").assertIsDisplayed()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("/tmp/project-transport")
        dir.mkdirs()
        File(dir, "project-panels.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

@Composable
private fun ProjectTransportHarness() {
    MaterialTheme(colorScheme = appLightColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Bg)
                .padding(vertical = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
                    .transportChrome(raised = true)
                    .padding(horizontal = Dimens.Gap, vertical = Dimens.TransportPanelPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MasterOutputPeakIndicator(
                    peakDbText = "-6.0 dB",
                    indicatorLevel = MasterPeakIndicatorLevel.Green,
                )
                Spacer(Modifier.weight(1f))
                ImportAudioButton(enabled = true, onClick = {})
                Spacer(Modifier.width(Dimens.Gap / 2))
                MixdownAudioButton(enabled = true, onClick = {})
            }
            TransportPanel(
                isRecording = false,
                isPlaying = false,
                isPlayEnabled = true,
                isStopEnabled = true,
                stopButtonShowsPause = false,
                playheadTimeLabel = "0:12",
                onPlay = {},
                onStop = {},
                onRecord = {},
                raised = true,
                timeline = {
                    TransportTimeline(
                        playheadPositionMs = 12_000,
                        timelineDurationMs = 125_000,
                        gestures = MixTimelineGestures(
                            onStarted = {},
                            onCancelled = {},
                            onPreview = {},
                            onCommit = {},
                        ),
                        inputLocked = false,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding),
            )
        }
    }
}
