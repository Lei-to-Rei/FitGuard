package com.example.fitguard.features.nutrition

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fitguard.R
import com.example.fitguard.data.model.DailyNutritionSummary
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.MealType
import com.example.fitguard.data.model.NutritionGoals
import com.example.fitguard.data.model.WaterIntakeEntry
import com.example.fitguard.databinding.ActivityNutritionTrackingBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NutritionTrackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNutritionTrackingBinding
    private val viewModel: NutritionTrackingViewModel by viewModels()

    private var currentEntries: List<FoodEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNutritionTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupMealCards()
        setupAddMealButton()
        setupWaterGlasses()
        observeData()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val dateFormat = SimpleDateFormat("'Today,' d MMMM, yyyy", Locale.getDefault())
        binding.tvDate.text = dateFormat.format(Date())
    }

    private fun setupMealCards() {
        setupExpandableCard(
            binding.headerBreakfast, binding.expandBreakfast, binding.chevronBreakfast
        )
        setupExpandableCard(
            binding.headerLunch, binding.expandLunch, binding.chevronLunch
        )
        setupExpandableCard(
            binding.headerDinner, binding.expandDinner, binding.chevronDinner
        )
        setupExpandableCard(
            binding.headerSnack, binding.expandSnack, binding.chevronSnack
        )
    }

    private fun setupExpandableCard(
        header: View,
        expandableContent: View,
        chevron: ImageView
    ) {
        header.setOnClickListener {
            if (expandableContent.visibility == View.GONE) {
                expandableContent.visibility = View.VISIBLE
                chevron.rotation = 90f
            } else {
                expandableContent.visibility = View.GONE
                chevron.rotation = 0f
            }
        }
    }

    private fun setupAddMealButton() {
        binding.btnAddMeal.setOnClickListener { showAddMealDialog(MealType.BREAKFAST) }
    }

    private fun setupWaterGlasses() {
        // Initial glasses will be rebuilt once profile loads via observeData()
    }

    private fun buildWaterGlasses(filled: Int, total: Int) {
        val container = binding.waterGlassesContainer

        // If total glasses matches, update in-place to avoid scroll jump
        val existingGlasses = mutableListOf<ImageView>()
        for (r in 0 until container.childCount) {
            val row = container.getChildAt(r) as? LinearLayout ?: continue
            for (g in 0 until row.childCount) {
                (row.getChildAt(g) as? ImageView)?.let { existingGlasses.add(it) }
            }
        }
        if (existingGlasses.size == total && total > 0) {
            for (i in 0 until total) {
                val glass = existingGlasses[i]
                if (i < filled) {
                    glass.setImageResource(R.drawable.ic_water_glass_filled)
                    glass.contentDescription = "Filled glass"
                    glass.setOnClickListener { viewModel.setWaterCount(i) }
                } else {
                    glass.setImageResource(R.drawable.ic_water_glass_empty)
                    glass.contentDescription = "Empty glass"
                    glass.setOnClickListener { viewModel.setWaterCount(i + 1) }
                }
            }
            return
        }

        // Full rebuild only when glass count changes
        val marginPerGlass = dpToPx(4) * 2
        val glassSize = dpToPx(40)

        container.post {
            container.removeAllViews()
            val availableWidth = container.width
            val glassesPerRow = if (availableWidth > 0) {
                maxOf(1, availableWidth / (glassSize + marginPerGlass))
            } else 8

            var currentRow: LinearLayout? = null
            for (i in 0 until total) {
                if (i % glassesPerRow == 0) {
                    currentRow = LinearLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (i > 0) topMargin = dpToPx(4)
                        }
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    container.addView(currentRow)
                }
                val glass = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(glassSize, glassSize).apply {
                        marginEnd = dpToPx(4)
                        marginStart = dpToPx(4)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (i < filled) {
                        setImageResource(R.drawable.ic_water_glass_filled)
                        contentDescription = "Filled glass"
                        setOnClickListener { viewModel.setWaterCount(i) }
                    } else {
                        setImageResource(R.drawable.ic_water_glass_empty)
                        contentDescription = "Empty glass"
                        setOnClickListener { viewModel.setWaterCount(i + 1) }
                    }
                }
                currentRow!!.addView(glass)
            }
        }
    }

    private fun observeData() {
        viewModel.entries.observe(this) { entries ->
            currentEntries = entries
            updateMealCards(entries)
        }

        viewModel.dailyTotals.observe(this) { totals ->
            updateNutritionDisplay(totals)
        }

        viewModel.goals.observe(this) {
            viewModel.dailyTotals.value?.let { totals -> updateNutritionDisplay(totals) }
            // Rebuild water glasses with profile goal
            val water = viewModel.waterIntake.value
            updateWaterDisplay(water)
        }

        viewModel.waterIntake.observe(this) { water ->
            updateWaterDisplay(water)
        }

        viewModel.exportStatus.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateMealCards(entries: List<FoodEntry>) {
        val grouped = entries.groupBy { it.mealType }

        updateMealSection(
            grouped[MealType.BREAKFAST.name] ?: emptyList(),
            binding.tvBreakfastInfo,
            binding.listBreakfast,
            isSnack = false
        )
        updateMealSection(
            grouped[MealType.LUNCH.name] ?: emptyList(),
            binding.tvLunchInfo,
            binding.listLunch,
            isSnack = false
        )
        updateMealSection(
            grouped[MealType.DINNER.name] ?: emptyList(),
            binding.tvDinnerInfo,
            binding.listDinner,
            isSnack = false
        )
        updateMealSection(
            grouped[MealType.SNACK.name] ?: emptyList(),
            binding.tvSnackInfo,
            binding.listSnack,
            isSnack = true
        )
    }

    private fun updateMealSection(
        foods: List<FoodEntry>,
        infoView: TextView,
        listContainer: LinearLayout,
        isSnack: Boolean
    ) {
        listContainer.removeAllViews()

        if (foods.isEmpty()) {
            infoView.text = if (isSnack) "Throughout Day" else "No meals logged"
            return
        }

        val totalCals = foods.sumOf { it.calories }
        val timeStr = if (isSnack) {
            "Throughout Day"
        } else {
            val firstTime = foods.minOf { it.createdAt }
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(firstTime))
        }
        infoView.text = "$timeStr \u2022 $totalCals kcal"

        for (food in foods) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(2), 0, dpToPx(2))
            }

            val deleteBtn = TextView(this).apply {
                text = "−"
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
                setOnClickListener {
                    viewModel.deleteFoodEntry(food)
                    Toast.makeText(context, "${food.name} removed", Toast.LENGTH_SHORT).show()
                }
            }

            val nameView = TextView(this).apply {
                text = food.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_dark))
                setPadding(dpToPx(8), 0, 0, 0)
            }

            row.addView(deleteBtn)
            row.addView(nameView)
            listContainer.addView(row)
        }
    }

    private fun updateNutritionDisplay(totals: DailyNutritionSummary) {
        val goals = viewModel.goals.value ?: NutritionGoals()

        // Circular progress
        binding.circularProgress.setProgress(totals.totalCalories, goals.calories)

        // Remaining
        val remaining = (goals.calories - totals.totalCalories).coerceAtLeast(0)
        binding.tvRemainingCalories.text = "$remaining kcal"

        // Calorie Burned (placeholder - can be hooked to activity data)
        binding.tvCalorieBurned.text = "0 kcal"

        // Macro bars
        val proteinPct = if (goals.protein > 0) ((totals.totalProtein / goals.protein) * 100).toInt().coerceAtMost(100) else 0
        binding.progressProtein.progress = proteinPct
        binding.tvProteinValue.text = "${totals.totalProtein.toInt()}g  of ${goals.protein.toInt()}g"

        val carbsPct = if (goals.carbs > 0) ((totals.totalCarbs / goals.carbs) * 100).toInt().coerceAtMost(100) else 0
        binding.progressCarbs.progress = carbsPct
        binding.tvCarbsValue.text = "${totals.totalCarbs.toInt()}g  of ${goals.carbs.toInt()}g"

        val fatPct = if (goals.fat > 0) ((totals.totalFat / goals.fat) * 100).toInt().coerceAtMost(100) else 0
        binding.progressFat.progress = fatPct
        binding.tvFatValue.text = "${totals.totalFat.toInt()}g  of ${goals.fat.toInt()}g"
    }

    private fun updateWaterDisplay(water: WaterIntakeEntry?) {
        val count = water?.glassCount ?: 0
        val goal = water?.goalGlasses ?: viewModel.getWaterGoal()
        val pct = if (goal > 0) (count * 100) / goal else 0
        val volumeL = count * 0.25 // ~250ml per glass

        buildWaterGlasses(count, goal)
        binding.tvWaterGoal.text = "Goal: $count / $goal glasses ($pct%)"
        binding.tvWaterProgress.text = "$count/$goal"
        binding.progressWater.progress = pct
        binding.tvWaterPercentage.text = "$pct%"
        binding.tvWaterVolume.text = "${"%.1f".format(volumeL)}L"
    }

    // ─── Add Meal Dialog ────────────────────────────────────────────

    private fun showAddMealDialog(defaultMealType: MealType) {
        val dialog = BottomSheetDialog(this, R.style.Theme_FitGuard_BottomSheet)
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_food, null)
        dialog.setContentView(dialogView)

        // Make bottom sheet expanded
        dialog.behavior.skipCollapsed = true

        val etFoodName = dialogView.findViewById<TextInputEditText>(R.id.etFoodName)
        val etCalories = dialogView.findViewById<TextInputEditText>(R.id.etCalories)
        val etProtein = dialogView.findViewById<TextInputEditText>(R.id.etProtein)
        val etCarbs = dialogView.findViewById<TextInputEditText>(R.id.etCarbs)
        val etFat = dialogView.findViewById<TextInputEditText>(R.id.etFat)
        val btnClose = dialogView.findViewById<View>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)
        val btnAddMeal = dialogView.findViewById<View>(R.id.btnAddMealConfirm)
        val quickAddContainer = dialogView.findViewById<LinearLayout>(R.id.quickAddContainer)
        val tvQuickAddLabel = dialogView.findViewById<View>(R.id.tvQuickAddLabel)
        val scrollQuickAdd = dialogView.findViewById<View>(R.id.scrollQuickAdd)

        // Meal type buttons
        val btnBreakfast = dialogView.findViewById<TextView>(R.id.btnMealBreakfast)
        val btnLunch = dialogView.findViewById<TextView>(R.id.btnMealLunch)
        val btnDinner = dialogView.findViewById<TextView>(R.id.btnMealDinner)
        val btnSnack = dialogView.findViewById<TextView>(R.id.btnMealSnack)
        val mealButtons = listOf(btnBreakfast, btnLunch, btnDinner, btnSnack)

        var selectedMealType = defaultMealType

        fun selectMealType(mealType: MealType) {
            selectedMealType = mealType
            for (btn in mealButtons) {
                btn.setBackgroundResource(R.drawable.bg_meal_type_unselected)
            }
            when (mealType) {
                MealType.BREAKFAST -> btnBreakfast.setBackgroundResource(R.drawable.bg_meal_type_selected)
                MealType.LUNCH -> btnLunch.setBackgroundResource(R.drawable.bg_meal_type_selected)
                MealType.DINNER -> btnDinner.setBackgroundResource(R.drawable.bg_meal_type_selected)
                MealType.SNACK -> btnSnack.setBackgroundResource(R.drawable.bg_meal_type_selected)
            }
            // Load quick add foods for this meal type
            viewModel.loadQuickAddForMealType(mealType.name)
        }

        btnBreakfast.setOnClickListener { selectMealType(MealType.BREAKFAST) }
        btnLunch.setOnClickListener { selectMealType(MealType.LUNCH) }
        btnDinner.setOnClickListener { selectMealType(MealType.DINNER) }
        btnSnack.setOnClickListener { selectMealType(MealType.SNACK) }

        // Observe quick add foods
        viewModel.quickAddFoods.observe(this) { foods ->
            quickAddContainer.removeAllViews()
            if (foods.isNotEmpty()) {
                tvQuickAddLabel.visibility = View.VISIBLE
                scrollQuickAdd.visibility = View.VISIBLE

                for (food in foods) {
                    val itemView = layoutInflater.inflate(R.layout.item_quick_add_food, quickAddContainer, false)
                    val tvIcon = itemView.findViewById<TextView>(R.id.tvQuickAddIcon)
                    val tvName = itemView.findViewById<TextView>(R.id.tvQuickAddName)
                    val tvCals = itemView.findViewById<TextView>(R.id.tvQuickAddCalories)

                    tvIcon.text = getFoodEmoji(food.name)
                    tvName.text = food.name
                    tvCals.text = "${food.calories} kcal"

                    itemView.setOnClickListener {
                        etFoodName.setText(food.name)
                        etCalories.setText(food.calories.toString())
                        if (food.protein > 0) etProtein.setText(food.protein.toString())
                        if (food.carbs > 0) etCarbs.setText(food.carbs.toString())
                        if (food.fat > 0) etFat.setText(food.fat.toString())

                        // Highlight selected
                        for (i in 0 until quickAddContainer.childCount) {
                            quickAddContainer.getChildAt(i).setBackgroundResource(R.drawable.bg_quick_add_item)
                        }
                        itemView.setBackgroundResource(R.drawable.bg_quick_add_item_selected)
                    }
                    quickAddContainer.addView(itemView)
                }
            } else {
                tvQuickAddLabel.visibility = View.GONE
                scrollQuickAdd.visibility = View.GONE
            }
        }

        // Set default selection
        selectMealType(defaultMealType)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnAddMeal.setOnClickListener {
            val name = etFoodName.text?.toString()?.trim() ?: ""
            val caloriesStr = etCalories.text?.toString()?.trim() ?: ""

            if (name.isEmpty() || caloriesStr.isEmpty()) {
                Toast.makeText(this, "Please fill in Food Name and Calories", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val calories = caloriesStr.toIntOrNull()
            if (calories == null) {
                Toast.makeText(this, "Please enter a valid calorie value", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val protein = etProtein.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
            val carbs = etCarbs.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
            val fat = etFat.text?.toString()?.trim()?.toFloatOrNull() ?: 0f

            viewModel.addFoodEntry(
                name = name,
                servingSize = "",
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                sodium = 0f,
                fiber = 0f,
                sugar = 0f,
                cholesterol = 0f,
                mealType = selectedMealType.name,
                isSaved = false
            )

            dialog.dismiss()
        }

        dialog.show()
    }

    private fun getFoodEmoji(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("rice") -> "\uD83C\uDF5A"
            lower.contains("egg") -> "\uD83E\uDD5A"
            lower.contains("apple") -> "\uD83C\uDF4E"
            lower.contains("milk") -> "\uD83E\uDD5B"
            lower.contains("bread") -> "\uD83C\uDF5E"
            lower.contains("chicken") -> "\uD83C\uDF57"
            lower.contains("fish") || lower.contains("salmon") -> "\uD83C\uDF1F"
            lower.contains("salad") || lower.contains("vegetable") -> "\uD83E\uDD57"
            lower.contains("banana") -> "\uD83C\uDF4C"
            lower.contains("coffee") -> "\u2615"
            lower.contains("oat") -> "\uD83E\uDD63"
            lower.contains("pasta") || lower.contains("noodle") -> "\uD83C\uDF5D"
            lower.contains("pizza") -> "\uD83C\uDF55"
            lower.contains("sandwich") || lower.contains("burger") -> "\uD83C\uDF54"
            lower.contains("soup") -> "\uD83C\uDF72"
            lower.contains("fruit") || lower.contains("orange") -> "\uD83C\uDF4A"
            lower.contains("cookie") || lower.contains("biscuit") -> "\uD83C\uDF6A"
            lower.contains("cake") -> "\uD83C\uDF70"
            lower.contains("juice") -> "\uD83E\uDDC3"
            lower.contains("water") -> "\uD83D\uDCA7"
            lower.contains("meat") || lower.contains("steak") || lower.contains("beef") -> "\uD83E\uDD69"
            else -> "\uD83C\uDF7D"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
