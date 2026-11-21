package com.georgv.audioworkstation.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
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
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
class ProjectCardViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun libraryShortcutHidesTransportUntilOpened() {
        compose.setContent {
            CardHarness {
                ProjectCard(
                    card = ShownCard(
                        title = "Voice note",
                        tags = listOf("live", "session"),
                        imagePath = null,
                        status = "Mixing… 40%",
                        progress = 0.4f,
                        description = "A quiet take from the hallway. Kept beside the name in small type, " +
                            "and cut once four squeezed lines are full.",
                    ),
                    opening = ProjectCardOpening(
                        opened = false,
                        mixAvailable = false,
                        onToggle = {},
                        onOpen = {},
                    ),
                    actions = {},
                    playback = { Text("TRANSPORT") },
                )
            }
        }
        compose.onNodeWithText("Voice note").assertIsDisplayed()
        compose.onNodeWithText(
            "A quiet take from the hallway. Kept beside the name in small type, " +
                "and cut once four squeezed lines are full.",
        ).assertIsDisplayed()
        compose.onNodeWithText("live").assertIsDisplayed()
        compose.onNodeWithText("session").assertIsDisplayed()
        compose.onNodeWithText("Mixing… 40%").assertDoesNotExist()
        compose.onNodeWithText("TRANSPORT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open project").assertDoesNotExist()
        save("library-shortcut")
    }

    @Test
    fun libraryOpenedCardShowsDescriptionWithoutMixTransport() {
        compose.setContent {
            CardHarness {
                ProjectCard(
                    card = ShownCard(
                        title = "Voice note",
                        tags = listOf("live", "session"),
                        imagePath = null,
                        status = "Mixing… 40%",
                        progress = 0.4f,
                        description = "A quiet take",
                    ),
                    opening = ProjectCardOpening(
                        opened = true,
                        mixAvailable = false,
                        onToggle = {},
                        onOpen = {},
                    ),
                    actions = {},
                    playback = { Text("TRANSPORT") },
                )
            }
        }
        compose.onNodeWithText("Voice note").assertIsDisplayed()
        compose.onNodeWithText("A quiet take").assertIsDisplayed()
        compose.onNodeWithText("live").assertIsDisplayed()
        compose.onNodeWithText("session").assertIsDisplayed()
        compose.onNodeWithText("Mixing… 40%").assertIsDisplayed()
        compose.onNodeWithText("No mix has been made.").assertIsDisplayed()
        compose.onNodeWithText("No mix yet").assertIsDisplayed()
        compose.onNodeWithText("00:00/00:00").assertIsDisplayed()
        compose.onNodeWithText("TRANSPORT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open project").assertIsDisplayed()
        save("library-opened-no-mix")
    }

    @Test
    fun openedMixShowsPlayStopAndTimeline() {
        compose.setContent {
            CardHarness {
                ProjectCard(
                    card = ShownCard(
                        title = "Voice note",
                        tags = listOf("live"),
                        imagePath = null,
                    ),
                    opening = ProjectCardOpening(
                        opened = true,
                        mixAvailable = true,
                        onToggle = {},
                        onOpen = {},
                    ),
                    actions = {},
                    playback = {
                        LibraryCardPlayback(
                            playing = false,
                            positionMs = 12_000,
                            durationMs = 125_000,
                            onPlay = {},
                            onStop = {},
                            onScrubStarted = {},
                            onSeek = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record").assertDoesNotExist()
        compose.onNodeWithText("00:12/02:05").assertIsDisplayed()
        compose.onNodeWithText("No mix yet").assertDoesNotExist()
        compose.onNodeWithText("0:12").assertDoesNotExist()
        compose.onNodeWithText("2:00").assertIsDisplayed()
        save("library-opened-mix")
    }

    @Test
    fun communityShortcutShowsSharerAndOpensTheCard() {
        compose.setContent {
            var opened by remember { mutableStateOf(false) }
            CardHarness {
                ProjectCard(
                    card = ShownCard(
                        title = "Night take",
                        tags = listOf("vocal"),
                        imagePath = null,
                        detail = "by Ada",
                        description = "Late vocal",
                    ),
                    opening = ProjectCardOpening(
                        opened = opened,
                        mixAvailable = true,
                        onToggle = { opened = true },
                        onOpen = {},
                    ),
                    actions = {},
                    playback = {
                        CommunityCardPlayback(
                            playing = true,
                            positionMs = 4_000,
                            durationMs = 90_000,
                            onPlay = {},
                            onStop = {},
                            onScrubStarted = {},
                            onSeek = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Night take").assertIsDisplayed()
        compose.onNodeWithText("by Ada").assertIsDisplayed()
        compose.onNodeWithText("Late vocal").assertIsDisplayed()
        compose.onNodeWithText("vocal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open project").assertDoesNotExist()
        save("community-shortcut")
        compose.onNodeWithText("Night take").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Late vocal").assertIsDisplayed()
        compose.onNodeWithText("vocal").assertIsDisplayed()
        compose.onNodeWithText("by Ada").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record").assertDoesNotExist()
        compose.onNodeWithText("00:04/01:30").assertIsDisplayed()
        compose.onNodeWithText("0:04").assertDoesNotExist()
        compose.onNodeWithText("1:30").assertIsDisplayed()
        save("community-opened")
    }

    @Test
    fun openedCardKeepsTitleAndDescriptionOnADarkPicture() {
        val picture = File.createTempFile("card-dark", ".png")
        val dark = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        dark.eraseColor(Color.BLACK)
        picture.outputStream().use { stream ->
            dark.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        compose.setContent {
            CardHarness {
                ProjectCard(
                    card = ShownCard(
                        title = "Voice note",
                        tags = emptyList(),
                        imagePath = picture.absolutePath,
                        detail = "by Ada",
                        description = "A quiet take",
                    ),
                    opening = ProjectCardOpening(
                        opened = true,
                        mixAvailable = false,
                        onToggle = {},
                        onOpen = {},
                    ),
                    actions = {},
                    playback = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Voice note").assertIsDisplayed()
        compose.onNodeWithText("A quiet take").assertIsDisplayed()
        compose.onNodeWithText("by Ada").assertIsDisplayed()
        compose.onNodeWithText("No mix has been made.").assertIsDisplayed()
        compose.onNodeWithText("No mix yet").assertIsDisplayed()
        compose.onNodeWithText("00:00/00:00").assertIsDisplayed()
        save("library-opened-dark")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("/tmp/card-shots")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

@Composable
private fun CardHarness(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appLightColorScheme()) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .background(AppColors.Bg)
                .padding(16.dp),
        ) {
            content()
        }
    }
}
