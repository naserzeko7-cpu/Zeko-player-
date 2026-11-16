package com.zeko.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class VocabularyActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("zeko_vocabulary", MODE_PRIVATE) }
    private lateinit var entriesBox: LinearLayout
    private val bg = Color.parseColor("#0E0E12")
    private val cardBg = Color.parseColor("#1A1A22")
    private val accent = Color.parseColor("#FF6D00")
    private val muted = Color.parseColor("#9A9AA8")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.parseColor("#14141A")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(18), dp(4))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "My Vocabulary"
            textSize = 22f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val add = TextView(this).apply {
            text = "+ Add"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(accent, 22)
            setOnClickListener { showAddDialog() }
        }
        header.addView(back)
        header.addView(title)
        header.addView(add)

        entriesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(entriesBox)
        }
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderEntries()
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun field(hintText: String, multiline: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(cardBg, 12)
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP
            } else {
                setSingleLine(true)
            }
        }

    private fun showAddDialog() {
        val word = field("Word or phrase")
        val meaning = field("Meaning / translation")
        val example = field("Example sentence (optional)", true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(word)
            addView(meaning, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
            addView(example, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Save a word")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val w = word.text.toString().trim()
                val m = meaning.text.toString().trim()
                val e = example.text.toString().trim()
                if (w.isEmpty() || m.isEmpty()) {
                    Toast.makeText(this, "Enter a word and its meaning", Toast.LENGTH_SHORT).show()
                } else {
                    val item = JSONObject().apply {
                        put("id", System.currentTimeMillis())
                        put("word", w)
                        put("meaning", m)
                        put("example", e)
                        put("savedAt", System.currentTimeMillis())
                    }
                    val data = readEntries()
                    data.put(item)
                    prefs.edit().putString("entries", data.toString()).apply()
                    renderEntries()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readEntries(): JSONArray = try {
        JSONArray(prefs.getString("entries", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun renderEntries() {
        entriesBox.removeAllViews()
        val data = readEntries()
        if (data.length() == 0) {
            val empty = TextView(this).apply {
                text = "Your saved words will appear here.\n\nTap + Add to start building your vocabulary."
                textSize = 16f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(48), dp(28), dp(48))
            }
            entriesBox.addView(empty)
            return
        }

        for (index in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(index) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
                background = rounded(cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(12)
                }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val word = TextView(this).apply {
                text = item.optString("word")
                textSize = 19f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val remove = TextView(this).apply {
                text = "Delete"
                textSize = 13f
                setTextColor(accent)
                setPadding(dp(10), dp(6), 0, dp(6))
                setOnClickListener { confirmDelete(item.optLong("id")) }
            }
            top.addView(word)
            top.addView(remove)
            card.addView(top)
            card.addView(TextView(this).apply {
                text = item.optString("meaning")
                textSize = 16f
                setTextColor(accent)
                setPadding(0, dp(5), 0, 0)
            })
            val exampleText = item.optString("example")
            if (exampleText.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = exampleText
                    textSize = 14f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, 0)
                })
            }
            entriesBox.addView(card)
        }
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setMessage("Delete this saved word?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val old = readEntries()
                val updated = JSONArray()
                for (i in 0 until old.length()) {
                    val item = old.optJSONObject(i) ?: continue
                    if (item.optLong("id") != id) updated.put(item)
                }
                prefs.edit().putString("entries", updated.toString()).apply()
                renderEntries()
            }
            .show()
    }
}
