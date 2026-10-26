package org.jcastillo.grabaciones.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MegaPasswordFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showingAndHidingPasswordPreservesTextAndCursorSelection() {
        val original = TextFieldValue("correct horse battery", selection = TextRange(8, 8))
        var password by mutableStateOf(original)
        var visible by mutableStateOf(false)

        composeRule.setContent {
            MegaPasswordField(
                value = password,
                onValueChange = { password = it },
                visible = visible,
                onToggleVisibility = { visible = !visible },
                enabled = true,
            )
        }

        composeRule.onNodeWithContentDescription("Mostrar contraseña").performClick()
        composeRule.runOnIdle {
            assertTrue(visible)
            assertEquals(original.text, password.text)
            assertEquals(original.selection, password.selection)
        }

        composeRule.onNodeWithContentDescription("Ocultar contraseña").performClick()
        composeRule.runOnIdle {
            assertFalse(visible)
            assertEquals(original.text, password.text)
            assertEquals(original.selection, password.selection)
        }
    }
}
