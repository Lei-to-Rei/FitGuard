package com.example.fitguard.ui.model

data class DashboardItem(
    val title: String,
    val description: String,
    val icon: Int,
    val activityClass: Class<*>
)