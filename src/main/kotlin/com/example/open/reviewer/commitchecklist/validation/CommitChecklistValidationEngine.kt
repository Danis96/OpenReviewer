package com.example.open.reviewer.commitchecklist.validation

data class CommitChecklistValidationConfig(
    val requireTypeOfChange: Boolean = true,
    val requiredChecklistItemIndices: Set<Int> = emptySet(),
    val requireDescription: Boolean = false,
)

data class CommitChecklistValidationInput(
    val description: String,
    val typeOfChange: String?,
    val checklistItemStates: List<Boolean>,
)

enum class CommitChecklistValidationField {
    DESCRIPTION,
    TYPE_OF_CHANGE,
    CHECKLIST_ITEM,
}

data class CommitChecklistValidationError(
    val field: CommitChecklistValidationField,
    val message: String,
    val checklistItemIndex: Int? = null,
)

data class CommitChecklistValidationResult(
    val errors: List<CommitChecklistValidationError>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun firstErrorFor(field: CommitChecklistValidationField): CommitChecklistValidationError? {
        return errors.firstOrNull { it.field == field }
    }

    fun errorForChecklistItem(index: Int): CommitChecklistValidationError? {
        return errors.firstOrNull {
            it.field == CommitChecklistValidationField.CHECKLIST_ITEM && it.checklistItemIndex == index
        }
    }
}

class CommitChecklistValidationEngine(
    private val config: CommitChecklistValidationConfig = CommitChecklistValidationConfig(),
) {
    fun validate(input: CommitChecklistValidationInput): CommitChecklistValidationResult {
        val errors = mutableListOf<CommitChecklistValidationError>()

        if (config.requireTypeOfChange && input.typeOfChange.isNullOrBlank()) {
            errors +=
                CommitChecklistValidationError(
                    field = CommitChecklistValidationField.TYPE_OF_CHANGE,
                    message = "Type of Change must be selected.",
                )
        }

        if (config.requireDescription && input.description.isBlank()) {
            errors +=
                CommitChecklistValidationError(
                    field = CommitChecklistValidationField.DESCRIPTION,
                    message = "Description is required.",
                )
        }

        config.requiredChecklistItemIndices
            .filter { it >= 0 && it < input.checklistItemStates.size }
            .forEach { requiredIndex ->
                if (!input.checklistItemStates[requiredIndex]) {
                    errors +=
                        CommitChecklistValidationError(
                            field = CommitChecklistValidationField.CHECKLIST_ITEM,
                            message = "This checklist item is required.",
                            checklistItemIndex = requiredIndex,
                        )
                }
            }

        return CommitChecklistValidationResult(errors = errors)
    }
}

