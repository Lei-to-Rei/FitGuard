package com.example.fitguard.onboarding

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fitguard.MainActivity
import com.example.fitguard.R
import com.example.fitguard.data.db.AppDatabase
import com.example.fitguard.data.model.UserProfile
import com.example.fitguard.data.processing.CsvWriter
import com.example.fitguard.data.repository.UserProfileRepository
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var stepContainer: FrameLayout
    private var currentStep = 0

    // Collected data
    private var profileName = ""
    private var gender = ""
    private var dateOfBirth = ""
    private var heightCm = 0f
    private var currentWeightKg = 0f
    private var targetWeightKg = 0f
    private var fitnessGoal = ""
    private var fitnessLevel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        stepContainer = findViewById(R.id.stepContainer)
        showStep(0)
    }

    private fun showStep(step: Int) {
        currentStep = step
        stepContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val view = when (step) {
            0 -> setupNameStep(inflater)
            1 -> setupGenderStep(inflater)
            2 -> setupBirthdayStep(inflater)
            3 -> setupHeightStep(inflater)
            4 -> setupWeightStep(inflater)
            5 -> setupGoalStep(inflater)
            6 -> setupFitnessLevelStep(inflater)
            else -> return
        }

        stepContainer.addView(view)
    }

    private fun setupNameStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_name, stepContainer, false)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)

        // Pre-fill with Firebase display name if available
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (profileName.isNotEmpty()) {
            etName.setText(profileName)
        } else if (currentUser?.displayName?.isNotEmpty() == true) {
            etName.setText(currentUser.displayName)
        }

        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            profileName = name
            showStep(1)
        }

        return view
    }

    private fun setupGenderStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_gender, stepContainer, false)
        val cardMale = view.findViewById<FrameLayout>(R.id.cardMale)
        val cardFemale = view.findViewById<FrameLayout>(R.id.cardFemale)

        fun selectGender(selected: String) {
            gender = selected
            cardMale.setBackgroundResource(
                if (selected == "Male") R.drawable.bg_card_selected else R.drawable.bg_card_unselected
            )
            cardFemale.setBackgroundResource(
                if (selected == "Female") R.drawable.bg_card_selected else R.drawable.bg_card_unselected
            )
        }

        // Restore previous selection
        if (gender.isNotEmpty()) selectGender(gender)

        cardMale.setOnClickListener { selectGender("Male") }
        cardFemale.setOnClickListener { selectGender("Female") }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(0) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            if (gender.isEmpty()) {
                Toast.makeText(this, "Please select your gender", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showStep(2)
        }

        return view
    }

    private fun setupBirthdayStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_birthday, stepContainer, false)
        val etBirthday = view.findViewById<TextInputEditText>(R.id.etBirthday)

        if (dateOfBirth.isNotEmpty()) etBirthday.setText(dateOfBirth)

        etBirthday.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    dateOfBirth = String.format("%02d / %02d / %04d", month + 1, day, year)
                    etBirthday.setText(dateOfBirth)
                },
                cal.get(Calendar.YEAR) - 20,
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(1) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            if (dateOfBirth.isEmpty()) {
                Toast.makeText(this, "Please select your date of birth", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showStep(3)
        }

        return view
    }

    private fun setupHeightStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_height, stepContainer, false)
        val etHeight = view.findViewById<TextInputEditText>(R.id.etHeight)

        if (heightCm > 0) {
            etHeight.setText(heightCm.toString())
        }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(2) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            val heightText = etHeight.text.toString().trim()
            if (heightText.isEmpty()) {
                Toast.makeText(this, "Please enter your height", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val value = heightText.toFloatOrNull() ?: 0f
            heightCm = value
            showStep(4)
        }

        return view
    }

    private fun setupWeightStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_weight, stepContainer, false)
        val etCurrent = view.findViewById<TextInputEditText>(R.id.etCurrentWeight)
        val etTarget = view.findViewById<TextInputEditText>(R.id.etTargetWeight)

        if (currentWeightKg > 0) etCurrent.setText(currentWeightKg.toString())
        if (targetWeightKg > 0) etTarget.setText(targetWeightKg.toString())

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(3) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            val current = etCurrent.text.toString().trim().toFloatOrNull()
            val target = etTarget.text.toString().trim().toFloatOrNull()
            if (current == null || current <= 0) {
                Toast.makeText(this, "Please enter your current weight", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (target == null || target <= 0) {
                Toast.makeText(this, "Please enter your target weight", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentWeightKg = current
            targetWeightKg = target
            showStep(5)
        }

        return view
    }

    private fun setupGoalStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_goal, stepContainer, false)
        val cardEndurance = view.findViewById<FrameLayout>(R.id.cardEndurance)
        val cardLoseWeight = view.findViewById<FrameLayout>(R.id.cardLoseWeight)
        val cardGainWeight = view.findViewById<FrameLayout>(R.id.cardGainWeight)

        val cards = listOf(
            cardEndurance to "Improve Endurance",
            cardLoseWeight to "Lose Weight",
            cardGainWeight to "Gain Weight"
        )

        fun selectGoal(selected: String) {
            fitnessGoal = selected
            cards.forEach { (card, goal) ->
                card.setBackgroundResource(
                    if (goal == selected) R.drawable.bg_card_selected else R.drawable.bg_card_unselected
                )
            }
        }

        if (fitnessGoal.isNotEmpty()) selectGoal(fitnessGoal)

        cardEndurance.setOnClickListener { selectGoal("Improve Endurance") }
        cardLoseWeight.setOnClickListener { selectGoal("Lose Weight") }
        cardGainWeight.setOnClickListener { selectGoal("Gain Weight") }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(4) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            if (fitnessGoal.isEmpty()) {
                Toast.makeText(this, "Please select a goal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showStep(6)
        }

        return view
    }

    private fun setupFitnessLevelStep(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.step_fitness_level, stepContainer, false)

        val cardSedentary = view.findViewById<FrameLayout>(R.id.cardSedentary)
        val cardLightlyActive = view.findViewById<FrameLayout>(R.id.cardLightlyActive)
        val cardModeratelyActive = view.findViewById<FrameLayout>(R.id.cardModeratelyActive)
        val cardActive = view.findViewById<FrameLayout>(R.id.cardActive)
        val cardVeryActive = view.findViewById<FrameLayout>(R.id.cardVeryActive)

        val cards = listOf(
            cardSedentary to "Sedentary",
            cardLightlyActive to "Lightly Active",
            cardModeratelyActive to "Moderately Active",
            cardActive to "Active",
            cardVeryActive to "Very Active"
        )

        fun selectLevel(selected: String) {
            fitnessLevel = selected
            cards.forEach { (card, level) ->
                card.setBackgroundResource(
                    if (level == selected) R.drawable.bg_card_selected else R.drawable.bg_card_unselected
                )
            }
        }

        if (fitnessLevel.isNotEmpty()) selectLevel(fitnessLevel)

        cardSedentary.setOnClickListener { selectLevel("Sedentary") }
        cardLightlyActive.setOnClickListener { selectLevel("Lightly Active") }
        cardModeratelyActive.setOnClickListener { selectLevel("Moderately Active") }
        cardActive.setOnClickListener { selectLevel("Active") }
        cardVeryActive.setOnClickListener { selectLevel("Very Active") }

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { showStep(5) }
        view.findViewById<ImageButton>(R.id.fabNext).setOnClickListener {
            if (fitnessLevel.isEmpty()) {
                Toast.makeText(this, "Please select your fitness level", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveProfileAndFinish()
        }

        return view
    }

    private fun saveProfileAndFinish() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        val db = AppDatabase.getInstance(applicationContext)
        val repo = UserProfileRepository(db.userProfileDao(), FirebaseFirestore.getInstance())

        CoroutineScope(Dispatchers.IO).launch {
            // Load existing profile or create new
            val existing = db.userProfileDao().getByUid(user.uid)
            val profile = (existing ?: UserProfile(uid = user.uid)).copy(
                displayName = profileName,
                email = user.email ?: "",
                gender = gender,
                dateOfBirth = dateOfBirth,
                heightCm = heightCm,
                currentWeightKg = currentWeightKg,
                targetWeightKg = targetWeightKg,
                fitnessGoal = fitnessGoal,
                fitnessLevel = fitnessLevel,
                profileComplete = true
            )

            repo.saveGoals(profile)
            CsvWriter.writeProfileCsv(profile)

            // Mark profile complete in SharedPreferences
            getSharedPreferences("fitguard_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("profile_complete", true)
                .apply()

            runOnUiThread {
                startActivity(Intent(this@ProfileSetupActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }
}
