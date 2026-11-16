package com.fieldrepository.app.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ToolArtisanLinkDto
import com.fieldrepository.app.data.ToolDetailDto
import com.fieldrepository.app.data.toolRowLabel
import org.junit.Assert.assertTrue
import com.fieldrepository.app.data.toolArtisanIds
import com.fieldrepository.app.data.toolArtisanLabel
import com.fieldrepository.app.data.toolArtisanNames
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.FieldRepositoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolRowLabelDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun artisan(id: String, name: String) = ArtisanDto(id = id, name = name, place = "Bagru", status = "APPROVED")

    @Test
    fun aSharedToolRowNamesEveryArtisanItIsLinkedTo() {
        val shared = ToolDetailDto(
            id = "t1",
            toolkitName = "Wooden block",
            artisanId = "a1",
            artisanName = "Kamla Devi",
            artisanLinks = listOf(
                ToolArtisanLinkDto("a1", artisan("a1", "Kamla Devi")),
                ToolArtisanLinkDto("a2", artisan("a2", "Ramesh Chhipa")),
            ),
        )
        val solo = ToolDetailDto(id = "t2", toolkitName = "Mallet", artisanId = "a1", artisanName = "Kamla Devi")
        rule.setContent {
            FieldRepositoryTheme {
                Column {
                    Text(shared.toolRowLabel())
                    Text(solo.toolRowLabel())
                }
            }
        }
        rule.onNodeWithText("Wooden block · Kamla Devi, Ramesh Chhipa").assertIsDisplayed()
        rule.onNodeWithText("Mallet · Kamla Devi").assertIsDisplayed()
    }

    @Test
    fun aToolWithTwoLinkedArtisansShowsBothHeadFirstInItsRowDetailAndPicker() {
        val fahim = artisan("a-fahim", "Fahim")
        val moin = artisan("a-moin", "Moin Khan")
        val grinder = ToolDetailDto(
            id = "cmwekvo2d03omcbtag53r4g2x",
            toolkitName = "Grinder",
            artisanId = "a-fahim",
            artisanName = "Fahim",
            artisanLinks = listOf(ToolArtisanLinkDto("a-moin", moin), ToolArtisanLinkDto("a-fahim", fahim)),
        )
        rule.setContent {
            FieldRepositoryTheme {
                Column {
                    Text(grinder.toolRowLabel())
                    Text(grinder.toolArtisanLabel() + ": " + grinder.toolArtisanNames().orEmpty())
                    SearchableMultiSelectField(
                        label = "Artisans",
                        options = listOf(SelectOption("a-moin", "Moin Khan"), SelectOption("a-fahim", "Fahim")),
                        selected = setOf("a-moin", "a-fahim"),
                        chipOrder = grinder.toolArtisanIds(),
                        onSelectedChange = {}
                    )
                }
            }
        }
        rule.onNodeWithText("Grinder · Fahim, Moin Khan").assertIsDisplayed()
        rule.onNodeWithText("Artisans: Fahim, Moin Khan").assertIsDisplayed()
        val head = rule.onNodeWithText("Fahim", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val other = rule.onNodeWithText("Moin Khan", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(head.top < other.top || (head.top == other.top && head.left < other.left))
    }
}
