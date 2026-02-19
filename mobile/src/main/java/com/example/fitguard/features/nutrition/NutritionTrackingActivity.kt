package com.example.fitguard.features.nutrition

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fitguard.R
import com.example.fitguard.data.model.FoodEntry
import com.example.fitguard.data.model.MealType
import com.example.fitguard.databinding.ActivityNutritionTrackingBinding
import com.google.android.material.textfield.TextInputEditText

class NutritionTrackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNutritionTrackingBinding
    private val viewModel: NutritionTrackingViewModel by viewModels()

    private lateinit var breakfastAdapter: FoodEntryAdapter
    private lateinit var lunchAdapter: FoodEntryAdapter
    private lateinit var dinnerAdapter: FoodEntryAdapter
    private lateinit var snackAdapter: FoodEntryAdapter

    private var savedFoodsList: List<FoodEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNutritionTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerViews()
        setupAddButtons()
        setupExportButton()
        observeData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    private fun setupRecyclerViews() {
        breakfastAdapter = FoodEntryAdapter { viewModel.deleteFoodEntry(it) }
        lunchAdapter = FoodEntryAdapter { viewModel.deleteFoodEntry(it) }
        dinnerAdapter = FoodEntryAdapter { viewModel.deleteFoodEntry(it) }
        snackAdapter = FoodEntryAdapter { viewModel.deleteFoodEntry(it) }

        binding.rvBreakfast.apply {
            layoutManager = LinearLayoutManager(this@NutritionTrackingActivity)
            adapter = breakfastAdapter
        }
        binding.rvLunch.apply {
            layoutManager = LinearLayoutManager(this@NutritionTrackingActivity)
            adapter = lunchAdapter
        }
        binding.rvDinner.apply {
            layoutManager = LinearLayoutManager(this@NutritionTrackingActivity)
            adapter = dinnerAdapter
        }
        binding.rvSnack.apply {
            layoutManager = LinearLayoutManager(this@NutritionTrackingActivity)
            adapter = snackAdapter
        }
    }

    private fun setupExportButton() {
        binding.btnExportCsv.setOnClickListener {
            viewModel.exportTodayToCsv()
        }
        viewModel.exportStatus.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupAddButtons() {
        binding.btnAddBreakfast.setOnClickListener { showAddFoodDialog(MealType.BREAKFAST) }
        binding.btnAddLunch.setOnClickListener { showAddFoodDialog(MealType.LUNCH) }
        binding.btnAddDinner.setOnClickListener { showAddFoodDialog(MealType.DINNER) }
        binding.btnAddSnack.setOnClickListener { showAddFoodDialog(MealType.SNACK) }
    }

    private fun observeData() {
        viewModel.entries.observe(this) { entries ->
            val grouped = entries.groupBy { it.mealType }
            breakfastAdapter.submitList(grouped[MealType.BREAKFAST.name] ?: emptyList())
            lunchAdapter.submitList(grouped[MealType.LUNCH.name] ?: emptyList())
            dinnerAdapter.submitList(grouped[MealType.DINNER.name] ?: emptyList())
            snackAdapter.submitList(grouped[MealType.SNACK.name] ?: emptyList())
        }

        viewModel.dailyTotals.observe(this) { totals ->
            val goals = viewModel.goals
            binding.apply {
                tvCaloriesValue.text = "${totals.totalCalories} / ${goals.calories}"
                progressCalories.progress = ((totals.totalCalories.toFloat() / goals.calories) * 100).toInt().coerceAtMost(100)

                tvProteinValue.text = "${"%.1f".format(totals.totalProtein)} / ${goals.protein.toInt()}g"
                progressProtein.progress = ((totals.totalProtein / goals.protein) * 100).toInt().coerceAtMost(100)

                tvCarbsValue.text = "${"%.1f".format(totals.totalCarbs)} / ${goals.carbs.toInt()}g"
                progressCarbs.progress = ((totals.totalCarbs / goals.carbs) * 100).toInt().coerceAtMost(100)

                tvFatValue.text = "${"%.1f".format(totals.totalFat)} / ${goals.fat.toInt()}g"
                progressFat.progress = ((totals.totalFat / goals.fat) * 100).toInt().coerceAtMost(100)

                tvSodiumValue.text = "${"%.0f".format(totals.totalSodium)} / ${goals.sodium.toInt()}mg"
                progressSodium.progress = ((totals.totalSodium / goals.sodium) * 100).toInt().coerceAtMost(100)
            }
        }

        viewModel.savedFoods.observe(this) { saved ->
            savedFoodsList = saved
        }
    }

    private fun showAddFoodDialog(mealType: MealType) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_food, null)

        val etFoodName = dialogView.findViewById<TextInputEditText>(R.id.etFoodName)
        val etServingSize = dialogView.findViewById<TextInputEditText>(R.id.etServingSize)
        val etCalories = dialogView.findViewById<TextInputEditText>(R.id.etCalories)
        val etProtein = dialogView.findViewById<TextInputEditText>(R.id.etProtein)
        val etCarbs = dialogView.findViewById<TextInputEditText>(R.id.etCarbs)
        val etFat = dialogView.findViewById<TextInputEditText>(R.id.etFat)
        val etSodium = dialogView.findViewById<TextInputEditText>(R.id.etSodium)
        val etFiber = dialogView.findViewById<TextInputEditText>(R.id.etFiber)
        val etSugar = dialogView.findViewById<TextInputEditText>(R.id.etSugar)
        val etCholesterol = dialogView.findViewById<TextInputEditText>(R.id.etCholesterol)
        val tvAdvancedToggle = dialogView.findViewById<TextView>(R.id.tvAdvancedToggle)
        val layoutAdvanced = dialogView.findViewById<LinearLayout>(R.id.layoutAdvanced)
        val rgMealType = dialogView.findViewById<RadioGroup>(R.id.rgMealType)
        val cbSaveForLater = dialogView.findViewById<CheckBox>(R.id.cbSaveForLater)
        val spinnerSavedFoods = dialogView.findViewById<Spinner>(R.id.spinnerSavedFoods)
        val tvSavedFoodsLabel = dialogView.findViewById<TextView>(R.id.tvSavedFoodsLabel)

        // Set default meal type radio button
        when (mealType) {
            MealType.BREAKFAST -> rgMealType.check(R.id.rbBreakfast)
            MealType.LUNCH -> rgMealType.check(R.id.rbLunch)
            MealType.DINNER -> rgMealType.check(R.id.rbDinner)
            MealType.SNACK -> rgMealType.check(R.id.rbSnack)
        }

        // Advanced fields toggle
        tvAdvancedToggle.setOnClickListener {
            if (layoutAdvanced.visibility == View.GONE) {
                layoutAdvanced.visibility = View.VISIBLE
                tvAdvancedToggle.text = "Hide Advanced Fields"
            } else {
                layoutAdvanced.visibility = View.GONE
                tvAdvancedToggle.text = "Show Advanced Fields"
            }
        }

        // Saved foods dropdown
        if (savedFoodsList.isNotEmpty()) {
            tvSavedFoodsLabel.visibility = View.VISIBLE
            spinnerSavedFoods.visibility = View.VISIBLE

            val foodNames = listOf("-- Select saved food --") + savedFoodsList.map { it.name }
            spinnerSavedFoods.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, foodNames
            )
            spinnerSavedFoods.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val saved = savedFoodsList[position - 1]
                        etFoodName.setText(saved.name)
                        etServingSize.setText(saved.servingSize)
                        etCalories.setText(saved.calories.toString())
                        etProtein.setText(saved.protein.toString())
                        etCarbs.setText(saved.carbs.toString())
                        etFat.setText(saved.fat.toString())
                        etSodium.setText(saved.sodium.toString())
                        etFiber.setText(saved.fiber.toString())
                        etSugar.setText(saved.sugar.toString())
                        etCholesterol.setText(saved.cholesterol.toString())
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Food - ${mealType.displayName()}")
            .setView(dialogView)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Override positive button to prevent auto-dismiss on validation failure
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etFoodName.text?.toString()?.trim() ?: ""
            val servingSize = etServingSize.text?.toString()?.trim() ?: ""
            val caloriesStr = etCalories.text?.toString()?.trim() ?: ""
            val proteinStr = etProtein.text?.toString()?.trim() ?: ""
            val carbsStr = etCarbs.text?.toString()?.trim() ?: ""
            val fatStr = etFat.text?.toString()?.trim() ?: ""
            val sodiumStr = etSodium.text?.toString()?.trim() ?: ""

            // Validate required fields
            if (name.isEmpty() || servingSize.isEmpty() || caloriesStr.isEmpty() ||
                proteinStr.isEmpty() || carbsStr.isEmpty() || fatStr.isEmpty() || sodiumStr.isEmpty()) {
                Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val calories = caloriesStr.toIntOrNull()
            val protein = proteinStr.toFloatOrNull()
            val carbs = carbsStr.toFloatOrNull()
            val fat = fatStr.toFloatOrNull()
            val sodium = sodiumStr.toFloatOrNull()

            if (calories == null || protein == null || carbs == null || fat == null || sodium == null) {
                Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fiber = etFiber.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
            val sugar = etSugar.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
            val cholesterol = etCholesterol.text?.toString()?.trim()?.toFloatOrNull() ?: 0f

            val selectedMealType = when (rgMealType.checkedRadioButtonId) {
                R.id.rbBreakfast -> MealType.BREAKFAST
                R.id.rbLunch -> MealType.LUNCH
                R.id.rbDinner -> MealType.DINNER
                R.id.rbSnack -> MealType.SNACK
                else -> mealType
            }

            viewModel.addFoodEntry(
                name = name,
                servingSize = servingSize,
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                sodium = sodium,
                fiber = fiber,
                sugar = sugar,
                cholesterol = cholesterol,
                mealType = selectedMealType.name,
                isSaved = cbSaveForLater.isChecked
            )

            dialog.dismiss()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
