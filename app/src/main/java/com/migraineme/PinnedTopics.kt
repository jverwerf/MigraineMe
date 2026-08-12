// app/src/main/java/com/migraineme/PinnedTopics.kt
package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

data class PinnedTopicData(
    val id: String,
    val title: String,
    val description: String,
    val drawIcon: DrawScope.(Color) -> Unit
)

object PinnedTopics {

    val all = listOf(
        PinnedTopicData(
            id = "00000000-0000-0000-0000-000000000001",
            title = tSync("What actually works for you?"),
            description = tSync("Share the treatments, habits, or lifestyle changes that have made a real difference for your migraines."),
            drawIcon = { color -> HubIcons.run { drawThumbsUp(color) } }
        ),
        PinnedTopicData(
            id = "00000000-0000-0000-0000-000000000002",
            title = tSync("Your most surprising trigger"),
            description = tSync("Tell us about a trigger you didn't expect — the one that took you ages to figure out."),
            drawIcon = { color -> HubIcons.run { drawTriggerBolt(color) } }
        ),
        PinnedTopicData(
            id = "00000000-0000-0000-0000-000000000003",
            title = tSync("Managing migraines at work"),
            description = tSync("How do you handle migraines in the workplace? Tips on communicating with employers, coping mid-shift, or working from home."),
            drawIcon = { color -> HubIcons.run { drawBriefcase(color) } }
        ),
        PinnedTopicData(
            id = "00000000-0000-0000-0000-000000000004",
            title = tSync("Sleep routines that help"),
            description = tSync("What does your sleep routine look like? Share what's helped you get better rest and fewer morning migraines."),
            drawIcon = { color -> HubIcons.run { drawMoonSleep(color) } }
        ),
        PinnedTopicData(
            id = "00000000-0000-0000-0000-000000000006",
            title = tSync("Food & diet — what do you avoid?"),
            description = tSync("Elimination diets, safe foods, meals that help — share what you've learned about food and migraines."),
            drawIcon = { color -> HubIcons.run { drawForkLeaf(color) } }
        ),
    )

    fun find(id: String): PinnedTopicData? = all.find { it.id == id }
}
