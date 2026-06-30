package com.modularmisfits.watchface

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

const val PREFS_NAME = "misfit_goals"
const val PREF_STEP_GOAL = "step_goal"
const val PREF_ACTIVE_MIN_GOAL = "active_min_goal"

fun loadStepGoal(context: Context): Int =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(PREF_STEP_GOAL, 10_000)

fun loadActiveMinGoal(context: Context): Int =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(PREF_ACTIVE_MIN_GOAL, 60)

class WatchFaceSettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var stepInput: EditText
    private lateinit var activeInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(160, 200, 220))
            textSize = 12f
            setPadding(0, 20, 0, 4)
        }

        fun field(hint: String, currentVal: Int) = EditText(this).apply {
            this.hint = hint
            setText(currentVal.toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(20, 30, 40))
            setPadding(16, 12, 16, 12)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 16f
        }

        val title = TextView(this).apply {
            text = "Misfit Goals"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        stepInput = field("10000", loadStepGoal(this))
        activeInput = field("60", loadActiveMinGoal(this))

        val saveBtn = Button(this).apply {
            text = "Save"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(70, 210, 130))
            setPadding(0, 16, 0, 16)
        }

        saveBtn.setOnClickListener {
            val stepGoal = stepInput.text.toString().toIntOrNull()?.coerceIn(1000, 100_000) ?: 10_000
            val activeGoal = activeInput.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 60
            prefs.edit()
                .putInt(PREF_STEP_GOAL, stepGoal)
                .putInt(PREF_ACTIVE_MIN_GOAL, activeGoal)
                .apply()
            Toast.makeText(this, "Goals saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        layout.addView(title)
        layout.addView(label("Daily Step Goal"))
        layout.addView(stepInput)
        layout.addView(label("Active Minutes Goal"))
        layout.addView(activeInput)
        layout.addView(View(this).apply { minimumHeight = 24 })
        layout.addView(saveBtn)

        root.addView(layout)
        setContentView(root)
    }
}
