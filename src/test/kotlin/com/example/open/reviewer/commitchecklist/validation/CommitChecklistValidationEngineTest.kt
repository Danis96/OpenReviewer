package com.example.open.reviewer.commitchecklist.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitChecklistValidationEngineTest {
    @Test
    fun `fails when type of change is missing`() {
        val engine = CommitChecklistValidationEngine(CommitChecklistValidationConfig(requireTypeOfChange = true))

        val result =
            engine.validate(
                CommitChecklistValidationInput(
                    description = "desc",
                    typeOfChange = "  ",
                    checklistItemStates = emptyList(),
                ),
            )

        assertFalse(result.isValid)
        assertEquals(
            CommitChecklistValidationField.TYPE_OF_CHANGE,
            result.firstErrorFor(CommitChecklistValidationField.TYPE_OF_CHANGE)?.field,
        )
    }

    @Test
    fun `fails when required checklist items are unchecked`() {
        val engine =
            CommitChecklistValidationEngine(
                CommitChecklistValidationConfig(
                    requiredChecklistItemIndices = setOf(0, 2),
                ),
            )

        val result =
            engine.validate(
                CommitChecklistValidationInput(
                    description = "desc",
                    typeOfChange = "Feature",
                    checklistItemStates = listOf(false, true, false),
                ),
            )

        assertFalse(result.isValid)
        assertTrue(result.errorForChecklistItem(0) != null)
        assertTrue(result.errorForChecklistItem(2) != null)
    }

    @Test
    fun `fails when description is required and missing`() {
        val engine =
            CommitChecklistValidationEngine(
                CommitChecklistValidationConfig(requireDescription = true),
            )

        val result =
            engine.validate(
                CommitChecklistValidationInput(
                    description = "\n\t ",
                    typeOfChange = "Feature",
                    checklistItemStates = emptyList(),
                ),
            )

        assertFalse(result.isValid)
        assertEquals(
            CommitChecklistValidationField.DESCRIPTION,
            result.firstErrorFor(CommitChecklistValidationField.DESCRIPTION)?.field,
        )
    }

    @Test
    fun `returns valid result when all configured rules pass`() {
        val engine =
            CommitChecklistValidationEngine(
                CommitChecklistValidationConfig(
                    requireTypeOfChange = true,
                    requiredChecklistItemIndices = setOf(0, 1),
                    requireDescription = true,
                ),
            )

        val result =
            engine.validate(
                CommitChecklistValidationInput(
                    description = "Implement feature X",
                    typeOfChange = "Feature",
                    checklistItemStates = listOf(true, true, false),
                ),
            )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
}
