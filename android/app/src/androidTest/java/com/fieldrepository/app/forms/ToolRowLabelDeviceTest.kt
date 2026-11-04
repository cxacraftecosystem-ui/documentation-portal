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
}
