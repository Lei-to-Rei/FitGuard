package com.example.fitguard.data.model

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK;

    fun displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}
