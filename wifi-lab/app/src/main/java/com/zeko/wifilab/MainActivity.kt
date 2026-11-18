package com.zeko.wifilab

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import java.security.SecureRandom

class MainActivity : Activity() {
    private val random = SecureRandom()
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var count: EditText
    private lateinit var length: EditText
    private lateinit var digits: CheckBox
    private lateinit var letters: CheckBox
    private lateinit var symbols: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 14, 28)
        window.navigationBarColor = Color.rgb(8, 14, 28)
        window.decorView.systemUiVisibility = 0
        buildUi()
    }

    private fun buildUi() {
        val bg = Color.rgb(8, 14, 28)
        val panel = Color.rgb(17, 28, 48)
        val blue = Color.rgb(53, 145, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 26, 22, 20)
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = value; textSize = size; setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
            }
        content.addView(text("ZEKO  /  WI-FI LAB", 13f, blue, true))
        content.addView(text("Password pattern simulator", 26f, Color.WHITE, true))
        content.addView(text("مختبر تعليمي لتوليد احتمالات محلية وفهم قوة كلمات المرور. لا يتصل بالشبكات ولا يجرّب كلمات مرور عليها.", 14f, Color.LTGRAY).apply {
            setPadding(0, 10, 0, 22)
        })

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            setBackgroundColor(panel)
        }
        content.addView(settings)
        settings.addView(text("إعدادات المحاكاة", 18f, Color.WHITE, true))
        count = field("عدد النتائج (1–500)", "100")
        length = field("طول كلمة المرور (4–16)", "8")
        settings.addView(count)
        settings.addView(length)
        digits = CheckBox(this).apply { text = "أرقام 0–9"; isChecked = true; setTextColor(Color.WHITE) }
        letters = CheckBox(this).apply { text = "حروف A–Z و a–z"; isChecked = true; setTextColor(Color.WHITE) }
        symbols = CheckBox(this).apply { text = "رموز"; setTextColor(Color.WHITE) }
        settings.addView(digits); settings.addView(letters); settings.addView(symbols)

        val run = Button(this).apply {
            text = "توليد احتمالات تجريبية"
            setTextColor(Color.WHITE); setBackgroundColor(blue)
            setOnClickListener { generate() }
        }
        settings.addView(run)
        status = text("جاهز للمحاكاة", 14f, Color.rgb(120, 190, 255), true)
        content.addView(status.apply { setPadding(0, 18, 0, 10) })
        output = text("ستظهر النتائج هنا بعد بدء المحاكاة.", 13f, Color.WHITE).apply {
            setPadding(14, 14, 14, 14); setBackgroundColor(panel)
        }
        content.addView(output)
        val footer = text("Zeko WiFi Lab • Offline demo • لا توجد محاولات اتصال", 11f, Color.GRAY)
        root.addView(footer)
        setContentView(root)
    }

    private fun field(label: String, value: String): EditText =
        EditText(this).apply {
            hint = label; setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            textSize = 14f; inputType = 2
        }

    private fun generate() {
        val n = count.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 100
        val len = length.text.toString().toIntOrNull()?.coerceIn(4, 16) ?: 8
        val alphabet = buildString {
            if (digits.isChecked) append("0123456789")
            if (letters.isChecked) append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (symbols.isChecked) append("!@#$%&*_-+?")
        }.toList().distinct()
        if (alphabet.isEmpty()) {
            status.text = "اختر مجموعة أحرف واحدة على الأقل"
            return
        }
        val results = LinkedHashSet<String>()
        while (results.size < n) {
            val candidate = buildString { repeat(len) { append(alphabet[random.nextInt(alphabet.size)]) } }
            results.add(candidate)
        }
        status.text = "اكتملت المحاكاة: ${results.size} احتمال • لا يوجد اتصال بالشبكة"
        output.text = results.mapIndexed { index, value -> "${index + 1}.  $value" }.joinToString("\n")
    }
}
